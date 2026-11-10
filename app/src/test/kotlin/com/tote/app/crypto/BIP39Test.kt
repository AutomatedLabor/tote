package com.tote.app.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM unit test for [BIP39].
 *
 * Validates the BIP-39 spec round-trip: entropy → mnemonic → entropy.
 *
 * Test vectors are from the official BIP-39 spec:
 * https://github.com/trezor/python-mnemonic/blob/master/vectors.json
 */
class BIP39Test {

    // BIP-39 official test vector (entropy, mnemonic)
    // From https://github.com/trezor/python-mnemonic/blob/master/vectors.json
    private val testVectors = listOf(
        // 128-bit entropy → 12 words
        Pair(
            "00000000000000000000000000000000",
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        ),
        Pair(
            "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f",
            "legal winner thank year wave sausage worth useful legal winner thank yellow"
        ),
        Pair(
            "80808080808080808080808080808080",
            "letter advice cage absurd amount doctor acoustic avoid letter advice cage above"
        ),
        Pair(
            "ffffffffffffffffffffffffffffffff",
            "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"
        ),
        // 256-bit entropy → 24 words
        Pair(
            "0000000000000000000000000000000000000000000000000000000000000000",
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
        ),
    )

    @Test
    fun `wordlist has exactly 2048 words`() {
        assertThat(BIP39.words.size).isEqualTo(2048)
    }

    @Test
    fun `wordlist first word is 'abandon'`() {
        assertThat(BIP39.words[0]).isEqualTo("abandon")
    }

    @Test
    fun `wordlist last word is 'zoo'`() {
        assertThat(BIP39.words[2047]).isEqualTo("zoo")
    }

    @Test
    fun `entropy → mnemonic → entropy round-trip preserves entropy`() {
        val entropy = ByteArray(16) { it.toByte() }
        val mnemonic = BIP39.entropyToMnemonic(entropy)
        val recovered = BIP39.mnemonicToEntropy(mnemonic)
        assertThat(recovered).isEqualTo(entropy)
    }

    @Test
    fun `entropy → mnemonic → entropy round-trip for 256-bit entropy`() {
        val entropy = ByteArray(32) { (it * 7).toByte() }
        val mnemonic = BIP39.entropyToMnemonic(entropy)
        val recovered = BIP39.mnemonicToEntropy(mnemonic)
        assertThat(recovered).isEqualTo(entropy)
    }

    @Test
    fun `generate(128) produces 12 words`() {
        val mnemonic = BIP39.generate(128)
        assertThat(mnemonic.size).isEqualTo(12)
    }

    @Test
    fun `generate(256) produces 24 words`() {
        val mnemonic = BIP39.generate(256)
        assertThat(mnemonic.size).isEqualTo(24)
    }

    @Test
    fun `generate produces a valid mnemonic (passes round-trip)`() {
        val mnemonic = BIP39.generate(128)
        val entropy = BIP39.mnemonicToEntropy(mnemonic)
        val mnemonicAgain = BIP39.entropyToMnemonic(entropy)
        assertThat(mnemonicAgain).isEqualTo(mnemonic)
    }

    @Test
    fun `official test vector — all-zeros 128-bit entropy → 'abandon abandon ... about'`() {
        val entropy = ByteArray(16)  // all zeros
        val mnemonic = BIP39.entropyToMnemonic(entropy)
        assertThat(mnemonic.joinToString(" ")).isEqualTo(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        )
    }

    @Test
    fun `official test vector — all-ones 128-bit entropy → 'zoo zoo ... wrong'`() {
        val entropy = ByteArray(16) { 0xFF.toByte() }
        val mnemonic = BIP39.entropyToMnemonic(entropy)
        assertThat(mnemonic.joinToString(" ")).isEqualTo(
            "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"
        )
    }

    @Test
    fun `mnemonic with bad checksum is rejected`() {
        // The all-zeros entropy has 'about' as the last word.
        // Replace it with 'abandon' (index 0) — checksum will not match.
        val badMnemonic = listOf(
            "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon"  // should be 'about'
        )
        try {
            BIP39.mnemonicToEntropy(badMnemonic)
            error("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("Checksum mismatch")
        }
    }

    @Test
    fun `mnemonic with unknown word is rejected`() {
        val badMnemonic = listOf(
            "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "xyzzzz"  // not in wordlist
        )
        try {
            BIP39.mnemonicToEntropy(badMnemonic)
            error("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("Unknown word")
        }
    }

    @Test
    fun `mnemonic must be 12, 15, 18, 21, or 24 words`() {
        for (bad in listOf(11, 13, 17, 20, 25)) {
            try {
                BIP39.mnemonicToEntropy(List(bad) { "abandon" })
                error("Expected IllegalArgumentException for size $bad")
            } catch (e: IllegalArgumentException) {
                assertThat(e.message).contains("must be 12/15/18/21/24")
            }
        }
    }
}
