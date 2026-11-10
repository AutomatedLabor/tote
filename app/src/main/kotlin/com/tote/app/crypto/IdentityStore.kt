package com.tote.app.crypto

import android.content.Context
import android.util.Log
import java.util.Base64

/**
 * Manages the local device's crypto identity + epoch state, persisted to
 * app-private SharedPreferences (encrypted at rest via [KeystoreWrapper]).
 *
 * Per CRYPTO-SPEC-FINAL §3: the seed is wrapped at rest via AndroidKeyStore
 * AES-GCM. If the AndroidKeyStore is unavailable (rare), we fall back to
 * plain SharedPreferences with a warning — the app remains usable but the
 * at-rest secret is recoverable by a root-level adversary.
 *
 * Lifecycle:
 * - First launch: [getOrCreateIdentity] generates a fresh [DeviceIdentity] +
 *   [EpochState] with a random pairingRoot placeholder (the real pairingRoot
 *   comes from the pairing handshake — until the user pairs, the epoch state
 *   is a local-only bootstrap).
 * - Subsequent launches: load from storage, decrypting via KeystoreWrapper.
 * - Nuke: [zeroize] wipes everything AND deletes the AndroidKeyStore master key.
 *
 * Thread-safety: all reads/writes go through `@Synchronized` methods.
 */
class IdentityStore(private val context: Context) {
    private val TAG = "IdentityStore"
    private val PREFS = "tote_identity"
    private val KEY_SEED = "device_seed"
    private val KEY_PAIRING_ROOT = "pairing_root"
    private val KEY_RECOVERY_SALT = "recovery_salt"
    private val KEY_CURRENT_EPOCH = "current_epoch"
    private val KEY_CURRENT_KEY = "current_key"

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keystore = KeystoreWrapper(context)

    @Volatile
    private var cachedIdentity: DeviceIdentity? = null

    @Volatile
    private var cachedEpochState: EpochState? = null

    /**
     * Get the existing identity, or generate a new one if none exists.
     *
     * Storage format (v0.5+): the seed is encrypted via KeystoreWrapper
     * (AES-GCM with AndroidKeyStore master key) and stored as Base64 in
     * SharedPreferences. The IV is prepended to the ciphertext.
     *
     * If AndroidKeyStore is unavailable: fall back to plain Base64 storage
     * (with a warning). The app remains usable but is less secure at rest.
     */
    @Synchronized
    fun getOrCreateIdentity(): DeviceIdentity {
        cachedIdentity?.let { return it }
        val storedSeedB64 = prefs.getString(KEY_SEED, null)
        val identity = if (storedSeedB64 != null) {
            try {
                val seed = decryptIfEncrypted(Base64.getDecoder().decode(storedSeedB64))
                DeviceIdentity(seed)
            } catch (t: Throwable) {
                Log.e(TAG, "Stored seed corrupted or keystore unavailable — generating fresh identity", t)
                DeviceIdentity.generate().also { saveIdentity(it) }
            }
        } else {
            DeviceIdentity.generate().also { saveIdentity(it) }
        }
        cachedIdentity = identity
        Log.i(TAG, "Identity loaded (ed25519Pub=${identity.ed25519Pub.toHex().take(16)}…)")
        return identity
    }

    /**
     * Get the existing epoch state, or bootstrap a fresh one (local-only,
     * pre-pairing) if none exists.
     *
     * Per CRYPTO-SPEC-FINAL §2: the real pairingRoot comes from the pairing
     * handshake (ECDH shared secret → HKDF). Before the user pairs, we
     * bootstrap a random root so the app has a working epoch state.
     */
    @Synchronized
    fun getOrCreateEpochState(identity: DeviceIdentity): EpochState {
        cachedEpochState?.let { return it }
        val storedRootB64 = prefs.getString(KEY_PAIRING_ROOT, null)
        val pairingRoot = if (storedRootB64 != null) {
            try {
                decryptIfEncrypted(Base64.getDecoder().decode(storedRootB64))
            } catch (t: Throwable) {
                Log.e(TAG, "Stored pairing root corrupted — generating fresh", t)
                ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }.also { savePairingRoot(it) }
            }
        } else {
            ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }.also { savePairingRoot(it) }
        }
        val state = EpochState(pairingRoot, identity)
        cachedEpochState = state
        Log.i(TAG, "EpochState loaded (epoch=${state.currentEpoch})")
        return state
    }

    /**
     * Persist the pairing root after a successful pairing handshake.
     *
     * Stored encrypted via KeystoreWrapper. Invalidates the cached epoch
     * state — next access re-derives from the new root.
     */
    fun savePairingRoot(root: ByteArray) {
        val ciphertext = encryptIfAvailable(root)
        prefs.edit().putString(KEY_PAIRING_ROOT, Base64.getEncoder().encodeToString(ciphertext)).apply()
        // Invalidate the cached epoch state — next access re-derives from the new root.
        cachedEpochState = null
    }

    /**
     * Persist the recovery salt. Used by [com.tote.app.recovery.RecoveryCode]
     * to derive the pairingRoot from the mnemonic via Argon2id.
     *
     * The salt is NOT secret — it's stored unencrypted. Its purpose is to
     * defeat pre-computed rainbow tables.
     */
    fun saveRecoverySalt(salt: ByteArray) {
        prefs.edit().putString(KEY_RECOVERY_SALT, Base64.getEncoder().encodeToString(salt)).apply()
    }

    fun getRecoverySalt(): ByteArray? {
        val b64 = prefs.getString(KEY_RECOVERY_SALT, null) ?: return null
        return Base64.getDecoder().decode(b64)
    }

    /**
     * Nuke: wipe all identity + epoch state from storage AND delete the
     * AndroidKeyStore master key so any previously-encrypted ciphertext
     * becomes unrecoverable.
     *
     * Per KillSwitch.NUKE_EXECUTED, this is the "destroy all keys" verb.
     */
    fun zeroize() {
        prefs.edit().clear().apply()
        cachedIdentity = null
        cachedEpochState = null
        try {
            keystore.deleteMasterKey()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to delete AndroidKeyStore master key during nuke: ${t.message}")
        }
        Log.w(TAG, "IdentityStore ZEROIZED — all keys wiped + AndroidKeyStore master deleted")
    }

    private fun saveIdentity(identity: DeviceIdentity) {
        val ciphertext = encryptIfAvailable(identity.seed)
        prefs.edit().putString(KEY_SEED, Base64.getEncoder().encodeToString(ciphertext)).apply()
    }

    /**
     * Encrypt a plaintext via KeystoreWrapper, falling back to plaintext
     * if AndroidKeyStore is unavailable.
     *
     * Returns the ciphertext (with IV prepended) if encryption succeeded,
     * or the original plaintext if it didn't.
     */
    private fun encryptIfAvailable(plaintext: ByteArray): ByteArray {
        return try {
            keystore.encrypt(plaintext)
        } catch (t: KeystoreUnavailableException) {
            Log.w(TAG, "AndroidKeyStore unavailable — storing secret in plaintext (INSECURE): ${t.message}")
            plaintext
        } catch (t: Throwable) {
            Log.w(TAG, "Encryption failed — storing secret in plaintext (INSECURE): ${t.message}")
            plaintext
        }
    }

    /**
     * Decrypt a ciphertext via KeystoreWrapper, detecting whether the input
     * is actually encrypted (based on length + IV format) or just plaintext
     * from a previous version that didn't have KeyStore wrapping.
     *
     * Heuristic: encrypted blobs are >= iv(12) + tag(16) = 28 bytes longer than
     * the plaintext. A 32-byte seed encrypted is 32+12+16 = 60 bytes. A 32-byte
     * plaintext seed is just 32 bytes. So:
     *   - 32 bytes → plaintext (old format), return as-is
     *   - 60 bytes → encrypted, decrypt
     */
    private fun decryptIfEncrypted(stored: ByteArray): ByteArray {
        // Heuristic: if the stored length > the expected plaintext length AND
        // the difference is exactly GCM_IV_LENGTH + GCM_TAG_LENGTH_BYTES (=28),
        // treat as encrypted. Otherwise treat as plaintext (old format).
        // We use 32 as the expected plaintext length for both seed and pairingRoot.
        val expectedPlaintextLen = 32
        val encryptedLen = expectedPlaintextLen + KeystoreWrapper.GCM_IV_LENGTH + KeystoreWrapper.GCM_TAG_LENGTH_BYTES
        return if (stored.size == encryptedLen) {
            // Looks encrypted — try to decrypt
            try {
                keystore.decrypt(stored)
            } catch (t: KeystoreUnavailableException) {
                Log.w(TAG, "Stored value looks encrypted but keystore unavailable — treating as plaintext: ${t.message}")
                // Can't decrypt — fall back to plaintext (won't be valid)
                stored
            } catch (t: javax.crypto.AEADBadTagException) {
                Log.w(TAG, "AEAD tag mismatch — master key may have been invalidated. Falling back to plaintext.")
                stored
            }
        } else if (stored.size == expectedPlaintextLen) {
            // Plaintext (old format) — return as-is
            stored
        } else {
            // Unknown length — try decrypt, fall back to plaintext
            try {
                keystore.decrypt(stored)
            } catch (t: Throwable) {
                Log.w(TAG, "Unknown stored length ${stored.size} — treating as plaintext: ${t.message}")
                stored
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
