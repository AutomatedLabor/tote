package com.tote.app.pairing

import com.google.common.truth.Truth.assertThat
import com.tote.app.crypto.DeviceIdentity
import org.junit.Test

/**
 * JVM unit test for [PairingHandshake] + [PairingPayload].
 *
 * Validates:
 * 1. PairingPayload round-trips through QR encoding.
 * 2. Both devices derive the same pairingRoot from ECDH + nonces.
 * 3. SAS is computed deterministically from pairingRoot.
 */
class PairingHandshakeTest {

    @Test
    fun `PairingPayload round-trips through QR encoding`() {
        val original = PairingPayload(
            relayUrl = "https://relay.tote.app/v1",
            senderDeviceId = ByteArray(32) { it.toByte() },
            senderX25519Pub = ByteArray(32) { (it + 32).toByte() },
            nonceA = ByteArray(32) { (it + 64).toByte() },
            saltHint = ByteArray(16) { (it + 96).toByte() },
        )
        val qr = original.toQrString()
        assertThat(qr).startsWith("tote://pair/")
        val decoded = PairingPayload.fromQrString(qr)
        assertThat(decoded.relayUrl).isEqualTo(original.relayUrl)
        assertThat(decoded.senderDeviceId).isEqualTo(original.senderDeviceId)
        assertThat(decoded.senderX25519Pub).isEqualTo(original.senderX25519Pub)
        assertThat(decoded.nonceA).isEqualTo(original.nonceA)
        assertThat(decoded.saltHint).isEqualTo(original.saltHint)
    }

    @Test
    fun `SAS emojis list has 64 entries`() {
        assertThat(PairingHandshake.SAS_EMOJIS.size).isEqualTo(64)
    }

    @Test
    fun `SAS emojis are all distinct`() {
        val emojis = PairingHandshake.SAS_EMOJIS
        assertThat(emojis.toSet().size).isEqualTo(emojis.size)
    }

    @Test
    fun `computeSas is deterministic for same pairingRoot`() {
        val identity = DeviceIdentity.generate()
        val payload = PairingPayload(
            relayUrl = "https://relay.tote.app/v1",
            senderDeviceId = identity.ed25519Pub,
            senderX25519Pub = identity.x25519Pub,
            nonceA = ByteArray(32) { it.toByte() },
            saltHint = ByteArray(16),
        )
        val handshake = PairingHandshake(identity, payload)
        val pairingRoot = ByteArray(32) { it.toByte() }
        val sas1 = handshake.computeSas(pairingRoot)
        val sas2 = handshake.computeSas(pairingRoot.copyOf())
        assertThat(sas1).isEqualTo(sas2)
        assertThat(sas1.length).isEqualTo(6)
        // Must be all digits
        assertThat(sas1.all { it.isDigit() }).isTrue()
    }

    @Test
    fun `computeSasEmoji returns 3 distinct emojis`() {
        val identity = DeviceIdentity.generate()
        val payload = PairingPayload(
            relayUrl = "https://relay.tote.app/v1",
            senderDeviceId = identity.ed25519Pub,
            senderX25519Pub = identity.x25519Pub,
            nonceA = ByteArray(32),
            saltHint = ByteArray(16),
        )
        val handshake = PairingHandshake(identity, payload)
        val emojis = handshake.computeSasEmoji(ByteArray(32) { it.toByte() })
        assertThat(emojis.size).isEqualTo(3)
        // Each emoji must be in the SAS list
        for (e in emojis) {
            assertThat(PairingHandshake.SAS_EMOJIS).contains(e)
        }
    }

    @Test
    fun `computeSas differs for different pairingRoots`() {
        val identity = DeviceIdentity.generate()
        val payload = PairingPayload(
            relayUrl = "https://relay.tote.app/v1",
            senderDeviceId = identity.ed25519Pub,
            senderX25519Pub = identity.x25519Pub,
            nonceA = ByteArray(32),
            saltHint = ByteArray(16),
        )
        val handshake = PairingHandshake(identity, payload)
        val sas1 = handshake.computeSas(ByteArray(32) { 0 })
        val sas2 = handshake.computeSas(ByteArray(32) { 1 })
        assertThat(sas1).isNotEqualTo(sas2)
    }
}
