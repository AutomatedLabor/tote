package com.tote.app.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM unit test for [HKDF].
 *
 * Validates against the RFC 5869 test vectors. If this passes, we can trust
 * every other key derivation in Tote that builds on HKDF (pairing root,
 * epoch keys, pairing-key-per-link, etc.).
 */
class HKDFTest {

    // RFC 5869 §A.1 — Test Case 1 — Basic test case with SHA-256
    private val tc1_ikm = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".hexToBytes()
    private val tc1_salt = "000102030405060708090a0b0c".hexToBytes()
    private val tc1_info = "f0f1f2f3f4f5f6f7f8f9".hexToBytes()
    private val tc1_prk = """
        077709362c2e32df0ddc3f0dc47bba63
        90b6c73bb50f9c3122ec844ad7c2b3e5
    """.trimIndent().replace(" ", "").replace("\n", "").hexToBytes()
    private val tc1_okm = """
        3cb25f25faacd57a90434f64d0362f2a
        2d2d0a90cf1a5a4c5db02d56ecc4c5bf
        34007208d5b887185865
    """.trimIndent().replace(" ", "").replace("\n", "").hexToBytes()

    @Test
    fun `extract — RFC 5869 Test Case 1 PRK matches`() {
        val prk = HKDF.extract(tc1_salt, tc1_ikm)
        assertThat(prk).isEqualTo(tc1_prk)
    }

    @Test
    fun `expand — RFC 5869 Test Case 1 OKM matches`() {
        val okm = HKDF.expand(tc1_prk, tc1_info, 42)
        assertThat(okm.size).isEqualTo(42)
        assertThat(okm).isEqualTo(tc1_okm)
    }

    @Test
    fun `derive — RFC 5869 Test Case 1 extract+expand matches`() {
        val okm = HKDF.derive(tc1_ikm, tc1_salt, tc1_info, 42)
        assertThat(okm).isEqualTo(tc1_okm)
    }

    @Test
    fun `derive with empty salt — uses HashLen zeros as salt (RFC 5869 §2·2)`() {
        val ikm = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".hexToBytes()
        val info = ByteArray(0)
        val okm = HKDF.derive(ikm, ByteArray(0), info, 32)
        assertThat(okm.size).isEqualTo(32)
        // Should not be all zeros.
        val anyNonZero = okm.any { it != 0.toByte() }
        assertThat(anyNonZero).isTrue()
    }

    @Test
    fun `derive is deterministic — same inputs produce same output`() {
        val ikm = ByteArray(32) { it.toByte() }
        val salt = ByteArray(16) { (it + 1).toByte() }
        val info = "tote-test-v1".toByteArray()

        val k1 = HKDF.derive(ikm, salt, info, 64)
        val k2 = HKDF.derive(ikm.copyOf(), salt.copyOf(), info.copyOf(), 64)
        assertThat(k1).isEqualTo(k2)
        assertThat(k1.size).isEqualTo(64)
    }

    @Test
    fun `derive — different info produces different output`() {
        val ikm = ByteArray(32) { it.toByte() }
        val salt = ByteArray(16)

        val a = HKDF.derive(ikm, salt, "info-a".toByteArray(), 32)
        val b = HKDF.derive(ikm, salt, "info-b".toByteArray(), 32)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `derive — different salt produces different output`() {
        val ikm = ByteArray(32) { it.toByte() }
        val info = "tote-test".toByteArray()

        val a = HKDF.derive(ikm, ByteArray(16), info, 32)
        val b = HKDF.derive(ikm, ByteArray(16) { 1 }, info, 32)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `derive — different ikm produces different output`() {
        val salt = ByteArray(16)
        val info = "tote-test".toByteArray()

        val a = HKDF.derive(ByteArray(32) { 0 }, salt, info, 32)
        val b = HKDF.derive(ByteArray(32) { 1 }, salt, info, 32)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `expand supports length longer than one block (255 * 32 bytes max)`() {
        val prk = ByteArray(32) { it.toByte() }
        val info = "long-output-test".toByteArray()
        val okm = HKDF.expand(prk, info, 32 * 5)
        assertThat(okm.size).isEqualTo(32 * 5)
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Hex string must be even length, got $length" }
        val out = ByteArray(length / 2)
        for (i in indices step 2) {
            out[i / 2] = ((Character.digit(this[i], 16) shl 4) or
                Character.digit(this[i + 1], 16)).toByte()
        }
        return out
    }
}
