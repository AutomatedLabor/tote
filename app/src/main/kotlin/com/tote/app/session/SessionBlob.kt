package com.tote.app.session

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Session blob wire format (per CRYPTO-SPEC-FINAL.md §4).
 * header(54) + nonce(24) + ct_len(4) + ciphertext + sig(64)
 */
object SessionBlob {
    const val MAGIC = "BST1"
    const val VERSION = 1

    fun encode(
        profileId: ByteArray,
        senderDeviceId: ByteArray,
        sequence: Long,
        ttl: Int,
        plaintext: ByteArray,
        epochKey: ByteArray,
        identity: com.tote.app.crypto.DeviceIdentity,
    ): ByteArray {
        val nonce = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
        val header = ByteBuffer.allocate(54)
            .put(MAGIC.toByteArray()).put(VERSION.toByte()).put(0.toByte())
            .put(profileId.copyOf(16)).put(senderDeviceId.copyOf(16))
            .putLong(sequence).putInt((System.currentTimeMillis() / 1000).toInt()).putInt(ttl)
            .array()
        // XChaCha20-Poly1305 encrypt (TODO: lazysodium)
        val ct = TODO("crypto_aead_xchacha20poly1305_ietf_encrypt(plaintext, header, nonce, epochKey)")
        val nonceAndLen = nonce + ByteBuffer.allocate(4).putInt(ct.size).array()
        val signedRange = header + nonceAndLen + ct
        val sig = identity.sign(signedRange)
        return signedRange + sig
    }

    fun decode(blob: ByteArray, epochKey: ByteArray, senderPub: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(blob)
        val magic = ByteArray(4).also { buf.get(it) }
        require(String(magic) == MAGIC) { "Bad magic: ${String(magic)}" }
        val hdr = blob.copyOfRange(0, 54)
        val nonce = blob.copyOfRange(54, 78)
        val ctLen = ByteBuffer.wrap(blob.copyOfRange(78, 82)).int
        val ct = blob.copyOfRange(82, 82 + ctLen)
        val sig = blob.copyOfRange(blob.size - 64, blob.size)
        val signed = blob.copyOfRange(0, blob.size - 64)
        require(com.tote.app.crypto.DeviceIdentity.verify(signed, sig, senderPub)) { "Sig verify failed" }
        return TODO("crypto_aead_xchacha20poly1305_ietf_decrypt(ct, hdr, nonce, epochKey)")
    }

    fun idempotencyKey(epochKey: ByteArray, senderDeviceId: ByteArray, seq: Long, profileId: ByteArray, ct: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(epochKey, "HmacSHA256"))
        val data = senderDeviceId + seq.toString().toByteArray() + profileId +
            MessageDigest.getInstance("SHA-256").digest(ct)
        return mac.doFinal(data)
    }
}
