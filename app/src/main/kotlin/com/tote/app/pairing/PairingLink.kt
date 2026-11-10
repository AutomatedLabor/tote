package com.tote.app.pairing

import com.tote.app.crypto.HKDF
import kotlinx.serialization.Serializable

enum class PairingDirection { SENDER, RECEIVER, BIDIRECTIONAL }

/**
 * A persisted pairing link between two devices.
 *
 * The pairing key is derived from the pairing root (the shared ECDH secret
 * from the QR-code handshake) plus identifying context for both devices. This
 * means the same root shared across two different device pairs produces
 * *different* per-pair keys — preventing cross-talk if a user pairs their
 * profile with multiple devices.
 *
 * Per CRYPTO-SPEC-FINAL.md §3 the derivation is:
 * ```
 * HKDF-SHA256(
 *   IKM    = pairingRoot,
 *   salt   = localProfileId || remoteProfileId || remoteDeviceId,
 *   info   = "pairing-key-v1",
 *   length = 32
 * )
 * ```
 * Order matters: salt MUST be the lexicographically-smaller profile ID first
 * so both devices derive the same key. We achieve that by sorting.
 */
@Serializable
data class PairingLink(
    val localProfileId: String,
    val remoteDeviceId: ByteArray,
    val remoteProfileId: String,
    val pairingKeyId: ByteArray,
    val lastSyncEpoch: Int = 0,
    val direction: PairingDirection = PairingDirection.BIDIRECTIONAL,
    val createdAtUtc: Long = System.currentTimeMillis(),
    val lastSyncAtUtc: Long? = null,
) {
    override fun equals(other: Any?): Boolean = other is PairingLink && localProfileId == other.localProfileId
    override fun hashCode(): Int = localProfileId.hashCode()

    companion object {
        /**
         * Derive a 32-byte pairing key from the shared pairing root, scoped to
         * (localProfileId, remoteProfileId, remoteDeviceId).
         *
         * Both devices in a pair must call this with the same inputs (modulo
         * "local" vs "remote" perspective) to derive the same key.
         */
        fun derivePairingKey(
            pairingRoot: ByteArray,
            localProfileId: String,
            remoteDeviceId: ByteArray,
            remoteProfileId: String,
        ): ByteArray {
            // Salt: lexicographically-sorted profile IDs + remoteDeviceId.
            // Sorting both sides agree on the same salt regardless of which
            // device is "local" vs "remote".
            val ids = listOf(localProfileId, remoteProfileId).sorted()
            val salt = (ids[0] + "|" + ids[1] + "|").toByteArray(Charsets.US_ASCII) + remoteDeviceId
            return HKDF.derive(
                ikm = pairingRoot,
                salt = salt,
                info = "pairing-key-v1".toByteArray(Charsets.US_ASCII),
                length = 32
            )
        }
    }
}
