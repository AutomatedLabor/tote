package com.tote.app.crypto

import com.goterl.lazysodium.interfaces.Sign
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import java.security.SecureRandom

/**
 * Epoch ratchet state (Variant B: random epoch keys, sender-authenticated distribution).
 * Per CRYPTO-SPEC-FINAL.md §2.
 *
 * The membership list is a list of (deviceId, idPub, addedAtEpoch) entries signed
 * by a key derived from the pairing root. Every epoch advance includes the new
 * key AND the new membership list, both signed by the sender's Ed25519 key.
 *
 * The receiver verifies the signature against the prior membership list before
 * applying the new key. This closes the PCS gap that "naive" epoch ratchets have.
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
    /**
     * Verify the membership list's signature against the pairing-root-derived
     * signing key. The signing key is derived via HKDF(pairingRoot, "membership-signing").
     */
    fun verify(signingPubKey: ByteArray): Boolean {
        // Re-derive what was signed: members || version (CBOR-encoded minus signature)
        val unsigned = this.copy(signature = ByteArray(0))
        val msg = Cbor.encodeToByteArray(unsigned)
        return DeviceIdentity.verify(msg, signature, signingPubKey)
    }

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

/**
 * Mutable per-device epoch state.
 *
 * NOT thread-safe — callers must hold a lock if multiple threads touch the same
 * instance. In practice the relay client is single-threaded so this is fine.
 */
class EpochState(val pairingRoot: ByteArray, val identity: DeviceIdentity) {
    var currentEpoch: Int = 0
        private set

    /** Current epoch key (32 bytes). */
    var currentKey: ByteArray
        private set

    /** Current membership list. */
    var membership: SignedMembershipList
        private set

    /**
     * Signing public key derived from the pairing root — used to verify
     * SignedMembershipList signatures.
     */
    val membershipSigningPub: ByteArray by lazy {
        // Derive an Ed25519 keypair from HKDF(pairingRoot, "membership-signing")
        val signingSeed = HKDF.derive(
            ikm = pairingRoot,
            salt = ByteArray(0),
            info = "membership-signing".toByteArray(Charsets.US_ASCII),
            length = 32
        )
        val pk = ByteArray(Sign.ED25519_PUBLICKEYBYTES)
        val sk = ByteArray(Sign.ED25519_SECRETKEYBYTES)
        val ok = LazysodiumBootstrap.get().cryptoSignSeedKeypair(pk, sk, signingSeed)
        check(ok) { "cryptoSignSeedKeypair(membership) returned false" }
        pk
    }

    init {
        currentKey = HKDF.derive(
            ikm = pairingRoot,
            salt = "epoch-${currentEpoch}".toByteArray(Charsets.US_ASCII),
            info = "epoch-e".toByteArray(Charsets.US_ASCII),
            length = 32
        )
        // Bootstrap membership list: just this device, epoch 0, signed by the
        // pairing-root-derived signing key.
        membership = signMembership(
            members = listOf(MemberEntry(identity.ed25519Pub, identity.ed25519Pub, 0)),
            version = 0
        )
    }

    /**
     * Advance to the next epoch: generate a new random key, sign the new state,
     * return a message that can be broadcast to peers.
     */
    fun advanceEpoch(): EpochAdvanceMessage {
        val newKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val newEpoch = currentEpoch + 1
        val newMembership = signMembership(
            members = membership.members,
            version = membership.version + 1
        )
        val unsigned = EpochAdvanceMessage(
            newEpoch = newEpoch,
            newEpochKey = newKey,
            senderDeviceId = identity.ed25519Pub,
            senderSignature = ByteArray(0),
            membershipList = newMembership
        )
        val msg = Cbor.encodeToByteArray(unsigned)
        val sig = identity.sign(msg)
        val signed = unsigned.copy(senderSignature = sig)

        currentEpoch = newEpoch
        currentKey = newKey
        membership = newMembership
        return signed
    }

    /**
     * Apply an incoming epoch-advance message from a peer.
     * Returns true if applied successfully, false if rejected (bad sig,
     * out-of-order, sender not in prior membership).
     */
    fun applyEpochAdvance(adv: EpochAdvanceMessage): Boolean {
        // Monotonicity check
        if (adv.newEpoch <= currentEpoch) return false
        // Sender must be in prior membership
        val sender = membership.members.find { it.deviceId.contentEquals(adv.senderDeviceId) }
            ?: return false
        // Verify sender's Ed25519 signature on the unsigned message
        val unsigned = adv.copy(senderSignature = ByteArray(0))
        val msg = Cbor.encodeToByteArray(unsigned)
        if (!DeviceIdentity.verify(msg, adv.senderSignature, sender.idPub)) return false
        // Verify the new membership list is signed by the pairing-root-derived key
        if (!adv.membershipList.verify(membershipSigningPub)) return false
        // Apply
        currentEpoch = adv.newEpoch
        currentKey = adv.newEpochKey.copyOf()
        membership = adv.membershipList
        return true
    }

    private fun signMembership(members: List<MemberEntry>, version: Int): SignedMembershipList {
        val unsigned = SignedMembershipList(members, version, signature = ByteArray(0))
        val msg = Cbor.encodeToByteArray(unsigned)
        // Sign with pairing-root-derived key (need both seed → keypair)
        val signingSeed = HKDF.derive(
            ikm = pairingRoot,
            salt = ByteArray(0),
            info = "membership-signing".toByteArray(Charsets.US_ASCII),
            length = 32
        )
        // Use DeviceIdentity(seed).sign(...) — it already wraps lazysodium correctly
        val sig = DeviceIdentity(signingSeed).sign(msg)
        return unsigned.copy(signature = sig)
    }
}
