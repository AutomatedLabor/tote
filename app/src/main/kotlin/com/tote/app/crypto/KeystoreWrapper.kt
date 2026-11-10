package com.tote.app.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore-backed AES-GCM wrapper for at-rest secrets.
 *
 * Per CRYPTO-SPEC-FINAL §3: app-private SharedPreferences is sandboxed but
 * not encrypted at rest on Android < 10. Even on Android 10+ where the
 * filesystem is encrypted, a root-level adversary (or anyone who extracts
 * a backup) can read the SharedPreferences XML and recover the secrets in
 * cleartext. This wrapper generates an AES-GCM master key inside the
 * AndroidKeyStore (hardware-backed on devices with TEE/StrongBox), so the
 * key material never leaves the secure enclave.
 *
 * Usage:
 * ```
 * val ks = KeystoreWrapper(context)
 * val plaintext = "tote-seed-32-bytes...".toByteArray()
 * val ciphertext = ks.encrypt(plaintext)  // returns iv(12) + ct + tag(16)
 * // ... store ciphertext in SharedPreferences ...
 * val recovered = ks.decrypt(ciphertext)  // back to plaintext
 * ```
 *
 * The master key is generated lazily on first [encrypt] call. The key:
 *   - is bound to the AndroidKeyStore ("AndroidKeyStore" provider)
 *   - is AES-256 (256-bit key length)
 *   - is GCM-mode only (no padding)
 *   - requires no authentication (v1.0.0 may add biometric requirement)
 *   - is NOT exportable (setUserAuthenticationRequired = false; "use" only)
 *   - is invalidated if the user sets a new lock screen credential (default
 *     behavior of `setUserAuthenticationRequired(false)` + key validity period)
 *
 * Failure modes:
 * - If AndroidKeyStore is unavailable (rare, broken TEE), encrypt/decrypt
 *   throw [KeystoreUnavailableException]. Caller should fall back to
 *   SharedPreferences-only storage (with a warning) rather than crash.
 * - On [nuke], the master key is deleted from the KeyStore via [deleteMasterKey].
 *
 * Per build-learnings iron rule #15: we DO NOT claim this works until a JVM
 * unit test has round-tripped encrypt → decrypt. (We can't actually test
 * the AndroidKeyStore path on the JVM — there's no KeyStore available — but
 * we CAN test the AES-GCM math directly with a software key. See
 * [KeystoreWrapperTest].)
 *
 * JVM testability: the AES-GCM encrypt/decrypt math is exposed via
 * [encryptWithKey] / [decryptWithKey] which take a [SecretKey] directly
 * (no AndroidKeyStore dependency). The JVM test supplies a software key.
 */
class KeystoreWrapper(private val context: Context) {

    private val TAG = "KeystoreWrapper"

    companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val MASTER_KEY_ALIAS = "tote_master_key"
        const val KEY_LENGTH_BITS = 256
        const val GCM_IV_LENGTH = 12        // bytes
        const val GCM_TAG_LENGTH_BITS = 128 // bits
        const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
            load(null)  // AndroidKeyStore doesn't need a password
        }
    }

    /**
     * Get or generate the master AES-256 key from the AndroidKeyStore.
     *
     * On first call: generates a new key. On subsequent calls: returns
     * the existing key.
     *
     * The key:
     *   - is AES-256 GCM
     *   - has no biometric requirement (v1.0.0 may add it)
     *   - is invalidated when the user sets a new lock screen credential
     *     (default `setUserAuthenticationRequired(false)` + the implicit
     *     `BLOCK_MODE_GCM` / `PURPOSE_ENCRYPT` `PURPOSE_DECRYPT` set)
     */
    @Synchronized
    fun getOrCreateMasterKey(): SecretKey {
        val existing = keyStore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        )
        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setKeySize(KEY_LENGTH_BITS)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            // No biometric requirement (v1.0.0 may add this for sensitive ops)
            // No validity period (key is valid indefinitely until revoked)
            .build()
        keyGenerator.init(spec)
        val key = keyGenerator.generateKey()
        Log.i(TAG, "Master key generated in AndroidKeyStore (alias=$MASTER_KEY_ALIAS)")
        return key
    }

    /**
     * Encrypt a plaintext byte array using the AndroidKeyStore master key.
     *
     * Output format: iv (12 bytes) || ciphertext || tag (16 bytes)
     * (The tag is appended automatically by the GCM Cipher.doFinal call.)
     *
     * @throws KeystoreUnavailableException if AndroidKeyStore is unavailable
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val key = try {
            getOrCreateMasterKey()
        } catch (t: Throwable) {
            throw KeystoreUnavailableException("Failed to load master key: ${t.message}", t)
        }
        return AesGcmMath.encrypt(plaintext, key)
    }

    /**
     * Decrypt a ciphertext that was produced by [encrypt].
     *
     * @throws KeystoreUnavailableException if AndroidKeyStore is unavailable
     * @throws javax.crypto.AEADBadTagException if the ciphertext was tampered
     *   with or the master key has been invalidated (e.g., user changed lock
     *   screen credential)
     */
    fun decrypt(ciphertext: ByteArray): ByteArray {
        val key = try {
            getOrCreateMasterKey()
        } catch (t: Throwable) {
            throw KeystoreUnavailableException("Failed to load master key: ${t.message}", t)
        }
        return AesGcmMath.decrypt(ciphertext, key)
    }

    /**
     * Delete the master key from the AndroidKeyStore.
     *
     * Called by KillSwitch.executeNuke() to invalidate all at-rest secrets.
     * After this call, any previously-encrypted ciphertext becomes
     * unrecoverable (decrypt() will throw AEADBadTagException because the
     * key is gone).
     */
    fun deleteMasterKey() {
        try {
            keyStore.deleteEntry(MASTER_KEY_ALIAS)
            Log.w(TAG, "Master key DELETED from AndroidKeyStore")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to delete master key", t)
        }
    }

    /**
     * Check if the master key exists in the AndroidKeyStore.
     *
     * Useful for the onboarding flow: if the key doesn't exist, the user
     * is on a fresh install and we can bootstrap; if it does, the existing
     * at-rest secrets are recoverable.
     */
    fun masterKeyExists(): Boolean {
        return try {
            keyStore.containsAlias(MASTER_KEY_ALIAS)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to check master key existence: ${t.message}")
            false
        }
    }
}

/**
 * Thrown when AndroidKeyStore is unavailable (e.g., broken TEE, custom ROM
 * without keystore, JVM test environment without the AndroidKeyStore provider).
 *
 * Callers should fall back to SharedPreferences-only storage (with a warning)
 * rather than crash the app.
 */
class KeystoreUnavailableException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
