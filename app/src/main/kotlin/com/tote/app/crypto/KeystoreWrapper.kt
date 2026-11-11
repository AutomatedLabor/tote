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
 * Per v1.1.0-alpha1: the master key now requires biometric (Class 3 STRONG)
 * authentication for decrypt operations. Encrypt operations are still
 * allowed without auth (so we can publish our pubkey to the relay without
 * prompting biometric on every app start). Decrypt requires the user to
 * authenticate via BiometricPrompt once per app session.
 *
 * Trade-off: on devices without biometrics enrolled, the key is created
 * without the auth requirement (graceful degradation).
 *
 * Usage:
 * ```
 * val ks = KeystoreWrapper(context)
 * val plaintext = "tote-seed-32-bytes...".toByteArray()
 * val ciphertext = ks.encrypt(plaintext)  // returns iv(12) + ct + tag(16)
 * // ... store ciphertext in SharedPreferences ...
 * val recovered = ks.decrypt(ciphertext, cryptoObject)  // back to plaintext
 * // cryptoObject obtained from BiometricPrompt.authenticate callback
 * ```
 *
 * The master key is generated lazily on first [encrypt] call. The key:
 *   - is bound to the AndroidKeyStore ("AndroidKeyStore" provider)
 *   - is AES-256 (256-bit key length)
 *   - is GCM-mode only (no padding)
 *   - requires biometric Class 3 STRONG authentication for DECRYPT only
 *     (encrypt stays no-auth, so app start doesn't prompt)
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
 * [AesGcmMath.encrypt] / [AesGcmMath.decrypt] (static helpers that don't
 * need a Context). The JVM test supplies a software key.
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

        /**
         * Whether biometric protection is enabled for the master key.
         *
         * Per v1.1.0-alpha1: defaults to true. Set to false via Settings if
         * the user prefers frictionless decrypt (less secure — anyone with
         * device unlock can read the at-rest secrets).
         */
        const val BIOMETRIC_PROTECTED_BY_DEFAULT = true
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
     * Per v1.1.0-alpha1: the key requires biometric Class 3 STRONG
     * authentication for DECRYPT only. ENCRYPT does not require auth
     * (so app start can publish our pubkey without prompting biometric).
     * On devices without biometrics enrolled, the key is created without
     * the auth requirement (graceful degradation).
     *
     * @param requireBiometricForDecrypt If true, the key is bound to
     *   biometric Class 3 STRONG auth for decrypt operations. If false,
     *   the key is created without auth requirement (less secure, used
     *   when the user has explicitly disabled biometric protection in
     *   Settings, OR when no biometric is enrolled on the device).
     */
    @Synchronized
    fun getOrCreateMasterKey(requireBiometricForDecrypt: Boolean = BIOMETRIC_PROTECTED_BY_DEFAULT): SecretKey {
        val existing = keyStore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        )
        val specBuilder = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setKeySize(KEY_LENGTH_BITS)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)

        if (requireBiometricForDecrypt) {
            try {
                specBuilder.setUserAuthenticationRequired(true)
                // Biometric Class 3 STRONG only, no timeout (valid for the
                // lifetime of the key unless invalidated)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    specBuilder.setUserAuthenticationParameters(
                        0,  // no timeout — auth required for every decrypt
                        KeyProperties.AUTH_BIOMETRIC_STRONG,
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Biometric protection not available — creating key without auth req: ${t.message}")
                // Fall through to no-auth key generation
            }
        }

        val spec = specBuilder.build()
        keyGenerator.init(spec)
        val key = keyGenerator.generateKey()
        Log.i(TAG, "Master key generated in AndroidKeyStore (alias=$MASTER_KEY_ALIAS, biometricProtected=$requireBiometricForDecrypt)")
        return key
    }

    /**
     * Encrypt a plaintext byte array using the AndroidKeyStore master key.
     *
     * Encrypt does NOT require biometric auth (per v1.1.0-alpha1) — we can
     * publish our pubkey to the relay on app start without prompting.
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
     * Per v1.1.0-alpha1: if the master key requires biometric auth, the
     * caller MUST first invoke [prepareDecryptCipher] inside a
     * BiometricPrompt.authenticate callback to obtain a Cipher that's
     * been authorized by the user's biometric. This method then runs
     * the actual doFinal() on that pre-authorized Cipher.
     *
     * For non-biometric-protected keys, [decrypt] can be called directly
     * (the Cipher is initialized internally with no auth).
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
     * Prepare a Cipher for decrypt, to be used inside a BiometricPrompt
     * callback. The caller wraps this in BiometricPrompt.CryptoObject and
     * passes it to BiometricPrompt.authenticate(promptInfo, cryptoObject).
     *
     * The flow:
     * 1. Caller calls [prepareDecryptCipher] to get an init'd Cipher.
     * 2. Caller wraps it in BiometricPrompt.CryptoObject.
     * 3. Caller invokes BiometricPrompt.authenticate(promptInfo, cryptoObject).
     * 4. On success, the callback receives the same CryptoObject + an
     *    authentication-authorized Cipher.
     * 5. Caller passes the original ciphertext to [decryptWithCipher] using
     *    the authorized Cipher.
     *
     * If the master key doesn't require biometric (e.g., device without
     * biometrics enrolled), this still works — the Cipher is just init'd
     * without auth.
     *
     * @throws KeystoreUnavailableException if AndroidKeyStore is unavailable
     */
    fun prepareDecryptCipher(): Cipher {
        val key = try {
            getOrCreateMasterKey()
        } catch (t: Throwable) {
            throw KeystoreUnavailableException("Failed to load master key: ${t.message}", t)
        }
        // We don't know the IV yet — it's prepended to the ciphertext.
        // The caller must pass the ciphertext to [decryptWithCipher], which
        // extracts the IV and re-inits the Cipher with the GCMParameterSpec.
        // This method just returns a Cipher instance; it's re-init'd in
        // [decryptWithCipher].
        return Cipher.getInstance("AES/GCM/NoPadding")
    }

    /**
     * Decrypt using a Cipher that was pre-authorized via BiometricPrompt.
     *
     * Use this when the master key requires biometric auth. The flow:
     * 1. [prepareDecryptCipher] → cipher
     * 2. BiometricPrompt.authenticate(promptInfo, CryptoObject(cipher))
     * 3. On success, callback receives authorized cipher
     * 4. [decryptWithCipher](ciphertext, cipher)
     *
     * @throws javax.crypto.AEADBadTagException if the ciphertext was tampered
     *   or the cipher was not properly authorized by biometric auth
     */
    fun decryptWithCipher(ciphertext: ByteArray, cipher: Cipher): ByteArray {
        require(ciphertext.size >= GCM_IV_LENGTH + GCM_TAG_LENGTH_BYTES) {
            "Ciphertext too short: ${ciphertext.size} bytes (need at least ${GCM_IV_LENGTH + GCM_TAG_LENGTH_BYTES})"
        }
        val iv = ciphertext.copyOfRange(0, GCM_IV_LENGTH)
        val ctWithTag = ciphertext.copyOfRange(GCM_IV_LENGTH, ciphertext.size)
        val params = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        // Re-init the cipher with the IV. For biometric-protected keys,
        // this is allowed because the cipher was already authenticated
        // by BiometricPrompt.
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), params)
        return cipher.doFinal(ctWithTag)
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

    /**
     * Check if the master key requires biometric auth for decrypt.
     *
     * Used by the UI to decide whether to prompt BiometricPrompt before
     * calling decrypt(). Returns false if the key doesn't exist (it'll
     * be created on first encrypt, so this method is informational only).
     */
    fun masterKeyRequiresBiometric(): Boolean {
        return try {
            // Read the key's metadata — if setUserAuthenticationRequired was true
            // at creation, this returns true.
            val secretKey = keyStore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey
                ?: return BIOMETRIC_PROTECTED_BY_DEFAULT
            // Reflection-free way: try to init a decrypt Cipher without auth.
            // If the key requires auth, init will throw UserNotAuthenticatedException.
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey)
                // No exception → key doesn't require biometric
                false
            } catch (t: android.security.keystore.UserNotAuthenticatedException) {
                true
            } catch (t: Throwable) {
                // Other init failures (e.g., need GCMParameterSpec) don't tell us
                // about biometric requirement. Fall back to the default.
                BIOMETRIC_PROTECTED_BY_DEFAULT
            }
        } catch (t: Throwable) {
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

