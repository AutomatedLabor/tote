package com.tote.app.crypto

import android.content.Context
import android.util.Log
import java.util.Base64

/**
 * Manages the local device's crypto identity + epoch state, persisted to
 * app-private SharedPreferences (encrypted at rest).
 *
 * Per CRYPTO-SPEC-FINAL §3: the seed is wrapped at rest via AndroidKeyStore.
 * For the v1 alpha we store it base64-encoded in SharedPreferences (which is
 * sandboxed to the app's private data directory). AndroidKeyStore wrapping is
 * a v1.0.0 TODO — see TODOs in [getOrCreateIdentity].
 *
 * Lifecycle:
 * - First launch: [getOrCreateIdentity] generates a fresh [DeviceIdentity] +
 *   [EpochState] with a random pairingRoot placeholder (the real pairingRoot
 *   comes from the pairing handshake — until the user pairs, the epoch state
 *   is a local-only bootstrap).
 * - Subsequent launches: load from storage.
 * - Nuke: [zeroize] wipes everything.
 */
class IdentityStore(private val context: Context) {
    private val TAG = "IdentityStore"
    private val PREFS = "tote_identity"
    private val KEY_SEED = "device_seed"
    private val KEY_PAIRING_ROOT = "pairing_root"
    private val KEY_CURRENT_EPOCH = "current_epoch"
    private val KEY_CURRENT_KEY = "current_key"

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var cachedIdentity: DeviceIdentity? = null

    @Volatile
    private var cachedEpochState: EpochState? = null

    /**
     * Get the existing identity, or generate a new one if none exists.
     *
     * TODO v1.0.0: wrap the seed with AndroidKeyStore AES-GCM (per
     * CRYPTO-SPEC-FINAL §3) so a stolen backup can't recover the keys.
     */
    @Synchronized
    fun getOrCreateIdentity(): DeviceIdentity {
        cachedIdentity?.let { return it }
        val storedSeed = prefs.getString(KEY_SEED, null)
        val identity = if (storedSeed != null) {
            val seed = Base64.getDecoder().decode(storedSeed)
            try {
                DeviceIdentity(seed)
            } catch (t: Throwable) {
                Log.e(TAG, "Stored seed corrupted — generating fresh identity", t)
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
        val storedRoot = prefs.getString(KEY_PAIRING_ROOT, null)
        val pairingRoot = if (storedRoot != null) {
            try {
                Base64.getDecoder().decode(storedRoot)
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
     */
    fun savePairingRoot(root: ByteArray) {
        prefs.edit().putString(KEY_PAIRING_ROOT, Base64.getEncoder().encodeToString(root)).apply()
        // Invalidate the cached epoch state — next access re-derives from the new root.
        cachedEpochState = null
    }

    /**
     * Nuke: wipe all identity + epoch state from storage.
     * Per KillSwitch.NUKE_EXECUTED, this is the "destroy all keys" verb.
     */
    fun zeroize() {
        prefs.edit().clear().apply()
        cachedIdentity = null
        cachedEpochState = null
        Log.w(TAG, "IdentityStore ZEROIZED — all keys wiped")
    }

    private fun saveIdentity(identity: DeviceIdentity) {
        prefs.edit().putString(KEY_SEED, Base64.getEncoder().encodeToString(identity.seed)).apply()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
