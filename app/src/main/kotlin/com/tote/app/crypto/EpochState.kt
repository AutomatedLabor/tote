package com.tote.app.crypto

import kotlinx.serialization.Serializable

/**
 * Epoch ratchet state (Variant B: random epoch keys, sender-authenticated distribution).
 * Per CRYPTO-SPEC-FINAL.md §2.
 */
@Serializable
data class MemberEntry(
    val deviceId: ByteArray,
    val idPub: ByteArray,
    val addedAtEpoch: Int,
) {
    override fun equals(other: Any?): Boolean = other is MemberEntry && deviceId.contentEquals(other.deviceId)
    override fun hashCode(): Int = deviceId.contentHashCode()
}

@Serializable
data class SignedMembershipList(
    val members: List<MemberEntry>,
    val version: Int,
    val signature: ByteArray,
) {
    fun verify(signingPubKey: ByteArray): Boolean = TODO("libsodium: crypto_sign_verify_detached")
    override fun equals(other: Any?): Boolean = other is SignedMembershipList && version == other.version
    override fun hashCode(): Int = version
}

@Serializable
data class EpochAdvanceMessage(
    val newEpoch: Int,
    val newEpochKey: ByteArray,
    val senderDeviceId: ByteArray,
    val senderSignature: ByteArray,
    val membershipList: SignedMembershipList,
) {
    override fun equals(other: Any?): Boolean = other is EpochAdvanceMessage && newEpoch == other.newEpoch
    override fun hashCode(): Int = newEpoch
}

class EpochState(val pairingRoot: ByteArray, val identity: DeviceIdentity) {
    var currentEpoch: Int = 0
    var currentKey: ByteArray = TODO("HKDF(pairingRoot, 'epoch-0', 'epoch-e', 32)")
    var membership: SignedMembershipList = TODO("initial membership list signed by pairing_root-derived key")

    fun advanceEpoch(): EpochAdvanceMessage {
        val newKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val newEpoch = currentEpoch + 1
        val msg = TODO("serialize EpochAdvanceMessage(newEpoch, newKey, identity)")
        val sig = identity.sign(msg)
        currentEpoch = newEpoch
        currentKey = newKey
        return EpochAdvanceMessage(newEpoch, newKey, TODO("identity.deviceId"), sig, membership)
    }

    fun applyEpochAdvance(adv: EpochAdvanceMessage, priorMembership: SignedMembershipList): Boolean {
        TODO("verify sender signature against prior membership list, check monotonicity, apply")
    }
}
