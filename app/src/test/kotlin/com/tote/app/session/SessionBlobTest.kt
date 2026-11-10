package com.tote.app.session

import com.google.common.truth.Truth.assertThat
import com.tote.app.crypto.DeviceIdentity
import com.tote.app.crypto.HKDF
import org.junit.Test

/**
 * JVM unit test for [SessionBlob] — the wire format for encrypted cookie
 * bundles exchanged between paired devices.
 *
 * Tests the full round-trip:
 * 1. Sender encrypts plaintext + signs (header || nonce || ct_len || ct)
 * 2. Receiver verifies signature, then decrypts
 * 3. Tamper-detection: flipping any byte in header/nonce/ciphertext/signature
 *    must fail either signature verification or AEAD authentication.
 */
class SessionBlobTest {

    private fun newEpochKey(): ByteArray {
        // Use HKDF to derive a 32-byte key from a random IKM.
        val ikm = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        return HKDF.derive(ikm, ByteArray(0), "epoch-test".toByteArray(), 32)
    }

    private fun newProfileId(): ByteArray =
        ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }

    @Test
    fun `encode then decode round-trip preserves plaintext`() {
        val sender = DeviceIdentity.generate()
        val epochKey = newEpochKey()
        val profileId = newProfileId()
        val senderId = newProfileId()
        val plaintext = "{\"cookies\":[{\"name\":\"session\",\"value\":\"abc123\"}]}".toByteArray()

        val blob = SessionBlob.encode(
            profileId = profileId,
            senderDeviceId = senderId,
            sequence = 1L,
            ttl = 86400,
            plaintext = plaintext,
            epochKey = epochKey,
            identity = sender,
        )

        val decoded = SessionBlob.decode(blob, epochKey, sender.ed25519Pub)
        assertThat(decoded).isEqualTo(plaintext)
    }

    @Test
    fun `encode then decode round-trip works for empty plaintext`() {
        val sender = DeviceIdentity.generate()
        val epochKey = newEpochKey()

        val blob = SessionBlob.encode(
            profileId = newProfileId(),
            senderDeviceId = newProfileId(),
            sequence = 0L,
            ttl = 3600,
            plaintext = ByteArray(0),
            epochKey = epochKey,
            identity = sender,
        )

        val decoded = SessionBlob.decode(blob, epochKey, sender.ed25519Pub)
        assertThat(decoded).isEqualTo(ByteArray(0))
    }

    @Test
    fun `encode then decode round-trip works for 4 MB plaintext`() {
        val sender = DeviceIdentity.generate()
        val epochKey = newEpochKey()

        val plaintext = ByteArray(4 * 1024 * 1024) { (it and 0xFF).toByte() }
        val blob = SessionBlob.encode(
            profileId = newProfileId(),
            senderDeviceId = newProfileId(),
            sequence = 1L,
            ttl = 86400,
            plaintext = plaintext,
            epochKey = epochKey,
            identity = sender,
        )

        val decoded = SessionBlob.decode(blob, epochKey, sender.ed25519Pub)
        assertThat(decoded.size).isEqualTo(plaintext.size)
        assertThat(decoded).isEqualTo(plaintext)
    }

    @Test
    fun `decode rejects blob with wrong epoch key`() {
        val sender = DeviceIdentity.generate()
        val epochKey = newEpochKey()
        val wrongKey = newEpochKey()
        // Ensure keys differ (extremely unlikely to collide, but check).
        assertThat(wrongKey).isNotEqualTo(epochKey)

        val plaintext = "secret cookies".toByteArray()
        val blob = SessionBlob.encode(
            profileId = newProfileId(),
            senderDeviceId = newProfileId(),
            sequence = 1L,
            ttl = 3600,
            plaintext = plaintext,
            epochKey = epochKey,
            identity = sender,
        )

        try {
            SessionBlob.decode(blob, wrongKey, sender.ed25519Pub)
            error("Expected IllegalStateException (decrypt fail)")
        } catch (e: IllegalStateException) {
            assertThat(e.message).contains("returned false")
        }
    }

    @Test
    fun `decode rejects blob with wrong sender public key`() {
        val sender = DeviceIdentity.generate()
        val attacker = DeviceIdentity.generate()
        val epochKey = newEpochKey()

        val plaintext = "secret cookies".toByteArray()
        val blob = SessionBlob.encode(
            profileId = newProfileId(),
            senderDeviceId = newProfileId(),
            sequence = 1L,
            ttl = 3600,
            plaintext = plaintext,
            epochKey = epochKey,
            identity = sender,
        )

        try {
            SessionBlob.decode(blob, epochKey, attacker.ed25519Pub)
            error("Expected IllegalArgumentException (sig verify fail)")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("Signature verify failed")
        }
    }

    @Test
    fun `decode rejects tampered header byte 0 (magic)`() {
        val sender = DeviceIdentity.generate()
        val epochKey = newEpochKey()

        val blob = SessionBlob.encode(
            profileId = newProfileId(),
            senderDeviceId = newProfileId(),
            sequence = 1L,
            ttl = 3600,
            plaintext = "secret cookies".toByteArray(),
            epochKey = epochKey,
            identity = sender,
        )

        val tampered = blob.copyOf()
        tampered[0] = (tampered[0] + 1).toByte()  // break magic

        try {
            SessionBlob.decode(tampered, epochKey, sender.ed25519Pub)
            error("Expected IllegalArgumentException (bad magic)")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("Bad magic")
        }
    }

    @Test
    fun `decode rejects tampered ciphertext byte`() {
        val sender = DeviceIdentity.generate()
        val epochKey = newEpochKey()

        val plaintext = "secret cookies".toByteArray()
        val blob = SessionBlob.encode(
            profileId = newProfileId(),
            senderDeviceId = newProfileId(),
            sequence = 1L,
            ttl = 3600,
            plaintext = plaintext,
            epochKey = epochKey,
            identity = sender,
        )

        // Tamper with a byte deep in the ciphertext.
        val ctStart = SessionBlob.HEADER_LEN + SessionBlob.NONCE_LEN + SessionBlob.CT_LEN_FIELD
        val tampered = blob.copyOf()
        tampered[ctStart + 5] = (tampered[ctStart + 5] + 1).toByte()

        // Either signature check fails (sig covers ct) or AEAD auth fails.
        try {
            SessionBlob.decode(tampered, epochKey, sender.ed25519Pub)
            error("Expected IllegalArgumentException or IllegalStateException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("Signature verify failed")
        } catch (e: IllegalStateException) {
            assertThat(e.message).contains("returned false")
        }
    }

    @Test
    fun `decode rejects tampered signature byte`() {
        val sender = DeviceIdentity.generate()
        val epochKey = newEpochKey()

        val plaintext = "secret cookies".toByteArray()
        val blob = SessionBlob.encode(
            profileId = newProfileId(),
            senderDeviceId = newProfileId(),
            sequence = 1L,
            ttl = 3600,
            plaintext = plaintext,
            epochKey = epochKey,
            identity = sender,
        )

        val tampered = blob.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1] + 1).toByte()

        try {
            SessionBlob.decode(tampered, epochKey, sender.ed25519Pub)
            error("Expected IllegalArgumentException (sig fail)")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("Signature verify failed")
        }
    }

    @Test
    fun `encode rejects profileId longer than 16 bytes`() {
        val sender = DeviceIdentity.generate()
        val epochKey = newEpochKey()

        try {
            SessionBlob.encode(
                profileId = ByteArray(17),
                senderDeviceId = ByteArray(16),
                sequence = 0L,
                ttl = 3600,
                plaintext = ByteArray(0),
                epochKey = epochKey,
                identity = sender,
            )
            error("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("16 bytes")
        }
    }

    @Test
    fun `encode rejects epochKey of wrong length`() {
        val sender = DeviceIdentity.generate()

        try {
            SessionBlob.encode(
                profileId = ByteArray(16),
                senderDeviceId = ByteArray(16),
                sequence = 0L,
                ttl = 3600,
                plaintext = ByteArray(0),
                epochKey = ByteArray(31),  // wrong length
                identity = sender,
            )
            error("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("32 bytes")
        }
    }

    @Test
    fun `idempotency key is deterministic for same inputs`() {
        val epochKey = newEpochKey()
        val senderId = newProfileId()
        val profileId = newProfileId()
        val ct = ByteArray(32) { it.toByte() }

        val k1 = SessionBlob.idempotencyKey(epochKey, senderId, 42L, profileId, ct)
        val k2 = SessionBlob.idempotencyKey(epochKey, senderId, 42L, profileId, ct)
        assertThat(k1).isEqualTo(k2)
        assertThat(k1.size).isEqualTo(32)
    }

    @Test
    fun `idempotency key differs when sequence changes`() {
        val epochKey = newEpochKey()
        val senderId = newProfileId()
        val profileId = newProfileId()
        val ct = ByteArray(32) { it.toByte() }

        val k1 = SessionBlob.idempotencyKey(epochKey, senderId, 1L, profileId, ct)
        val k2 = SessionBlob.idempotencyKey(epochKey, senderId, 2L, profileId, ct)
        assertThat(k1).isNotEqualTo(k2)
    }
}
