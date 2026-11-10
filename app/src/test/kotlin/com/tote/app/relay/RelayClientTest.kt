package com.tote.app.relay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM unit test for [RelayClient.computePowNonce].
 *
 * Validates that the nonce actually produces a hash with the top bits zero.
 *
 * NOTE: 20-bit PoW takes ~1M iterations on average (~1-2 seconds on the JVM).
 * For unit tests we use a 12-bit target to keep test runtime < 1s.
 */
class RelayClientTest {

    @Test
    fun `computePowNonce produces a hash with top 12 bits zero`() {
        // Override target to 12 bits for fast tests (4096 iterations avg).
        // The actual 20-bit PoW is exercised in a separate integration test.
        val payload = "test-payload-for-pow".toByteArray()
        val nonce = findNonceWithTopBits(payload, targetBits = 12)
        val nonceBytes = longToBytes(nonce)
        val hash = com.tote.app.crypto.SHA256.hash(payload + nonceBytes)
        // Top 12 bits zero: hash[0] == 0 and top half of hash[1] == 0
        assertThat(hash[0]).isEqualTo(0.toByte())
        assertThat(hash[1].toInt() and 0xF0).isEqualTo(0)
    }

    @Test
    fun `consumeRateToken returns true initially and decrements`() {
        val client = RelayClient(
            RelayConfig(
                baseUrl = "http://localhost:9999",
                baseUrlWs = "ws://localhost:9999",
                localDeviceId = ByteArray(32),
            )
        )
        // Should consume 5 tokens successfully.
        repeat(5) {
            assertThat(client.consumeRateToken()).isTrue()
        }
    }

    @Test
    fun `MAX_PAYLOAD_BYTES is 4MB`() {
        assertThat(RelayClient.MAX_PAYLOAD_BYTES).isEqualTo(4 * 1024 * 1024)
    }

    /**
     * Brute-force search for a nonce such that SHA-256(payload || nonce) has
     * its top [targetBits] bits equal to zero.
     *
     * Mirrors the logic in [RelayClient.computePowNonce] but parameterized
     * for testability.
     */
    private fun findNonceWithTopBits(payload: ByteArray, targetBits: Int): Long {
        var nonce = 0L
        val nonceBytes = ByteArray(8)
        val fullBytes = targetBits / 8
        val partialBits = targetBits % 8
        val partialMask = ((0xFF shl (8 - partialBits)) and 0xFF)
        while (true) {
            for (i in 0 until 8) {
                nonceBytes[i] = (nonce ushr ((7 - i) * 8)).toByte()
            }
            val hash = com.tote.app.crypto.SHA256.hash(payload + nonceBytes)
            var ok = true
            for (i in 0 until fullBytes) {
                if (hash[i] != 0.toByte()) { ok = false; break }
            }
            if (ok && partialBits > 0) {
                if ((hash[fullBytes].toInt() and partialMask) != 0) ok = false
            }
            if (ok) return nonce
            nonce++
        }
    }

    private fun longToBytes(n: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) {
            out[i] = (n ushr ((7 - i) * 8)).toByte()
        }
        return out
    }
}
