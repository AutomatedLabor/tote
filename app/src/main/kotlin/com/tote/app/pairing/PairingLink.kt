package com.tote.app.pairing

import kotlinx.serialization.Serializable

enum class PairingDirection { SENDER, RECEIVER, BIDIRECTIONAL }

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
        fun derivePairingKey(pairingRoot: ByteArray, localProfileId: String, remoteDeviceId: ByteArray, remoteProfileId: String): ByteArray =
            TODO("HKDF-SHA256: salt=localProfileId+remoteProfileId+remoteDeviceId, info='pairing-key-v1', IKM=pairingRoot")
    }
}
