package com.tote.app.pairing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM unit test for [PairingPayload.encode/decode] round-trip (the QR
 * payload format).
 *
 * ZXing's QRCodeWriter is Android-agnostic but returns android.graphics.Bitmap,
 * so we can't test the visual QR generation in JVM. We test the byte-level
 * payload codec instead (which is what goes into the QR).
 */
class QrPayloadTest {

    @Test
    fun `PairingPayload encode-decode round-trip preserves all fields`() {
        val original = PairingPayload(
            relayUrl = "https://relay.tote.app/v1",
            senderDeviceId = ByteArray(32) { it.toByte() },
            senderX25519Pub = ByteArray(32) { (it + 32).toByte() },
            nonceA = ByteArray(32) { (it + 64).toByte() },
            saltHint = ByteArray(16) { (it + 96).toByte() },
        )
        val bytes = original.encode()
        val decoded = PairingPayload.decode(bytes)
        assertThat(decoded.relayUrl).isEqualTo(original.relayUrl)
        assertThat(decoded.senderDeviceId).isEqualTo(original.senderDeviceId)
        assertThat(decoded.senderX25519Pub).isEqualTo(original.senderX25519Pub)
        assertThat(decoded.nonceA).isEqualTo(original.nonceA)
        assertThat(decoded.saltHint).isEqualTo(original.saltHint)
    }

    @Test
    fun `QR string has the tote-pair prefix`() {
        val payload = PairingPayload(
            relayUrl = "https://relay.tote.app/v1",
            senderDeviceId = ByteArray(32),
            senderX25519Pub = ByteArray(32),
            nonceA = ByteArray(32),
            saltHint = ByteArray(16),
        )
        val qr = payload.toQrString()
        assertThat(qr).startsWith("tote://pair/")
    }

    @Test
    fun `QR string is URL-safe (no + or / characters)`() {
        val payload = PairingPayload(
            relayUrl = "https://relay.tote.app/v1",
            senderDeviceId = ByteArray(32) { 0xFF.toByte() },  // worst case
            senderX25519Pub = ByteArray(32) { 0xFF.toByte() },
            nonceA = ByteArray(32) { 0xFF.toByte() },
            saltHint = ByteArray(16) { 0xFF.toByte() },
        )
        val qr = payload.toQrString()
        val b64part = qr.removePrefix("tote://pair/")
        // URL-safe base64 uses - and _ instead of + and /
        assertThat(b64part).doesNotContain("+")
        assertThat(b64part).doesNotContain("/")
    }

    @Test
    fun `QR string round-trips`() {
        val payload = PairingPayload(
            relayUrl = "wss://relay.example.com/ws",
            senderDeviceId = ByteArray(32) { (it * 3).toByte() },
            senderX25519Pub = ByteArray(32) { (it * 7).toByte() },
            nonceA = ByteArray(32) { (it * 11).toByte() },
            saltHint = ByteArray(16) { (it * 13).toByte() },
        )
        val qr = payload.toQrString()
        val decoded = PairingPayload.fromQrString(qr)
        assertThat(decoded.relayUrl).isEqualTo(payload.relayUrl)
        assertThat(decoded.senderDeviceId).isEqualTo(payload.senderDeviceId)
        assertThat(decoded.senderX25519Pub).isEqualTo(payload.senderX25519Pub)
        assertThat(decoded.nonceA).isEqualTo(payload.nonceA)
        assertThat(decoded.saltHint).isEqualTo(payload.saltHint)
    }

    @Test
    fun `fromQrString rejects non-tote prefix`() {
        try {
            PairingPayload.fromQrString("https://not-tote.example.com/abc")
            error("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("Not a Tote pairing QR")
        }
    }
}
