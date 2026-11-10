package com.tote.app.session

import com.goterl.lazysodium.interfaces.AEAD
import com.goterl.lazysodium.interfaces.Sign
import com.tote.app.crypto.DeviceIdentity
import com.tote.app.crypto.LazysodiumBootstrap
import java.nio.ByteBuffer

/**
 * Session blob wire format (per CRYPTO-SPEC-FINAL.md §4).
 *
 * Layout:
 * ```
 *  +-----------+---------+--------+------------+-----+
 *  | header(54)| nonce(24)|ct_len(4)|ciphertext|sig(64)
 *  +-----------+---------+--------+------------+-----+
 * ```
 * - `header` (54B): magic(4) + version(1) + reserved(1) + profileId(16) +
 *   senderDeviceId(16) + sequence(8) + timestamp(4) + ttl(4)
 * - `nonce` (24B): XChaCha20-Poly1305-IETF nonce, randomly generated
 * - `ct_len` (4B): big-endian int — length of `ciphertext` (plaintext.size + 16)
 * - `ciphertext` (variable): plaintext.size + 16 (Poly1305 tag) — AAD = header
 * - `sig` (64B): Ed25519 detached signature over (header || nonce || ct_len || ciphertext)
 *
 * AAD for the AEAD = the 54-byte header. Tampering with the header causes
 * decryption to fail (forged-AAD detection) IN ADDITION to the signature check.
 */
object SessionBlob {
    const val MAGIC = "BST1"
    const val VERSION: Byte = 1
    const val HEADER_LEN = 54
    const val NONCE_LEN = AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES  // 24
    const val CT_LEN_FIELD = 4
    const val SIG_LEN = Sign.ED25519_BYTES  // 64
    const val AEAD_TAG_LEN = AEAD.XCHACHA20POLY1305_IETF_ABYTES  // 16

    fun encode(
        profileId: ByteArray,
        senderDeviceId: ByteArray,
        sequence: Long,
        ttl: Int,
        plaintext: ByteArray,
        epochKey: ByteArray,
        identity: DeviceIdentity,
    ): ByteArray {
        require(profileId.size <= 16) { "profileId must be ≤16 bytes (got ${profileId.size})" }
        require(senderDeviceId.size <= 16) { "senderDeviceId must be ≤16 bytes (got ${senderDeviceId.size})" }
        require(epochKey.size == AEAD.XCHACHA20POLY1305_IETF_KEYBYTES) {
            "epochKey must be 32 bytes (got ${epochKey.size})"
        }

        val nonce = ByteArray(NONCE_LEN).also {
            java.security.SecureRandom().nextBytes(it)
        }

        // Build the 54-byte header. profileId and senderDeviceId are right-padded
        // with zeros if shorter than 16 bytes.
        val paddedProfile = ByteArray(16).also { profileId.copyInto(it, endIndex = profileId.size) }
        val paddedSender = ByteArray(16).also { senderDeviceId.copyInto(it, endIndex = senderDeviceId.size) }
        val header = ByteBuffer.allocate(HEADER_LEN)
            .put(MAGIC.toByteArray(Charsets.US_ASCII))  // 4
            .put(VERSION)                                // 1
            .put(0.toByte())                              // 1 (reserved)
            .put(paddedProfile)                            // 16
            .put(paddedSender)                            // 16
            .putLong(sequence)                            // 8
            .putInt((System.currentTimeMillis() / 1000).toInt())  // 4
            .putInt(ttl)                                  // 4
            .array()
        check(header.size == HEADER_LEN) { "header is ${header.size} bytes, expected $HEADER_LEN" }

        // XChaCha20-Poly1305-IETF encrypt (header used as AAD).
        // Output is plaintext.size + 16 (Poly1305 tag) bytes.
        val ct = ByteArray(plaintext.size + AEAD_TAG_LEN)
        val cLen = longArrayOf(0L)
        val nSec: ByteArray? = null  // XChaCha20-Poly1305 has no secret nonce
        val ok = LazysodiumBootstrap.get().cryptoAeadXChaCha20Poly1305IetfEncrypt(
            /* c = */ ct,
            /* cLen = */ cLen,
            /* m = */ plaintext,
            /* mLen = */ plaintext.size.toLong(),
            /* ad = */ header,
            /* adLen = */ header.size.toLong(),
            /* nSec = */ nSec,
            /* nPub = */ nonce,
            /* k = */ epochKey,
        )
        check(ok) { "cryptoAeadXChaCha20Poly1305IetfEncrypt returned false" }
        // Sanity check: lazysodium should have written exactly ct.size bytes.
        check(cLen[0].toInt() == ct.size) {
            "cLen mismatch: cLen[0]=${cLen[0]} ct.size=${ct.size}"
        }

        val nonceAndLen = nonce + ByteBuffer.allocate(CT_LEN_FIELD).putInt(ct.size).array()
        val signedRange = header + nonceAndLen + ct
        val sig = identity.sign(signedRange)
        return signedRange + sig
    }

    fun decode(blob: ByteArray, epochKey: ByteArray, senderPub: ByteArray): ByteArray {
        require(blob.size >= HEADER_LEN + NONCE_LEN + CT_LEN_FIELD + SIG_LEN + AEAD_TAG_LEN) {
            "Blob too small: ${blob.size} bytes"
        }
        require(epochKey.size == AEAD.XCHACHA20POLY1305_IETF_KEYBYTES) {
            "epochKey must be 32 bytes (got ${epochKey.size})"
        }
        require(senderPub.size == Sign.ED25519_PUBLICKEYBYTES) {
            "senderPub must be 32 bytes (got ${senderPub.size})"
        }

        // Verify magic
        val magic = blob.copyOfRange(0, 4)
        require(String(magic, Charsets.US_ASCII) == MAGIC) { "Bad magic: ${String(magic)}" }

        // Verify version
        val version = blob[4]
        require(version == VERSION) { "Unsupported version: $version" }

        val header = blob.copyOfRange(0, HEADER_LEN)
        val nonce = blob.copyOfRange(HEADER_LEN, HEADER_LEN + NONCE_LEN)
        val ctLenOffset = HEADER_LEN + NONCE_LEN
        val ctLen = ByteBuffer.wrap(blob.copyOfRange(ctLenOffset, ctLenOffset + CT_LEN_FIELD)).int
        val ctStart = ctLenOffset + CT_LEN_FIELD
        val ctEnd = ctStart + ctLen
        require(ctEnd + SIG_LEN == blob.size) {
            "Blob layout mismatch: ctEnd=$ctEnd, blob.size=${blob.size}, SIG_LEN=$SIG_LEN"
        }
        val ct = blob.copyOfRange(ctStart, ctEnd)
        val sig = blob.copyOfRange(blob.size - SIG_LEN, blob.size)
        val signed = blob.copyOfRange(0, blob.size - SIG_LEN)

        require(DeviceIdentity.verify(signed, sig, senderPub)) { "Signature verify failed" }

        val pt = ByteArray(ct.size - AEAD_TAG_LEN)
        val mLen = longArrayOf(0L)
        val nSec: ByteArray? = null
        val ok = LazysodiumBootstrap.get().cryptoAeadXChaCha20Poly1305IetfDecrypt(
            /* m = */ pt,
            /* mLen = */ mLen,
            /* nSec = */ nSec,
            /* c = */ ct,
            /* cLen = */ ct.size.toLong(),
            /* ad = */ header,
            /* adLen = */ header.size.toLong(),
            /* nPub = */ nonce,
            /* k = */ epochKey,
        )
        check(ok) { "cryptoAeadXChaCha20Poly1305IetfDecrypt returned false (forged or wrong key)" }
        check(mLen[0].toInt() == pt.size) {
            "mLen mismatch: mLen[0]=${mLen[0]} pt.size=${pt.size}"
        }
        return pt
    }

    /**
     * Idempotency key: HMAC(epochKey, senderDeviceId || seq || profileId || sha256(ct)).
     * Used to dedupe replayed blobs on the receiver side.
     */
    fun idempotencyKey(epochKey: ByteArray, senderDeviceId: ByteArray, seq: Long, profileId: ByteArray, ct: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(epochKey, "HmacSHA256"))
        val data = senderDeviceId + seq.toString().toByteArray() + profileId +
            java.security.MessageDigest.getInstance("SHA-256").digest(ct)
        return mac.doFinal(data)
    }
}
