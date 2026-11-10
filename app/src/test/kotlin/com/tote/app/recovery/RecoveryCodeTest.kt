package com.tote.app.recovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM unit test for [RecoveryCode].
 *
 * Validates BIP-39 generation, validation, and pairingRoot derivation.
 */
class RecoveryCodeTest {

    @Test
    fun `generate produces 12 words`() {
        val (mnemonic, _) = RecoveryCode.generate()
        assertThat(mnemonic.size).isEqualTo(12)
    }

    @Test
    fun `generate produces a valid mnemonic (passes BIP-39 checksum)`() {
        val (mnemonic, entropy) = RecoveryCode.generate()
        val validated = RecoveryCode.validateMnemonic(mnemonic)
        assertThat(validated).isNotNull()
        assertThat(validated).isEqualTo(entropy)
    }

    @Test
    fun `derivePairingRoot is deterministic for same entropy`() {
        val entropy = ByteArray(16) { it.toByte() }
        val root1 = RecoveryCode.derivePairingRoot(entropy)
        val root2 = RecoveryCode.derivePairingRoot(entropy.copyOf())
        assertThat(root1).isEqualTo(root2)
        assertThat(root1.size).isEqualTo(32)
    }

    @Test
    fun `derivePairingRoot differs for different entropy`() {
        val root1 = RecoveryCode.derivePairingRoot(ByteArray(16) { 0 })
        val root2 = RecoveryCode.derivePairingRoot(ByteArray(16) { 1 })
        assertThat(root1).isNotEqualTo(root2)
    }

    @Test
    fun `deriveRecoveryHint is 4 bytes`() {
        val hint = RecoveryCode.deriveRecoveryHint(ByteArray(16) { 0 })
        assertThat(hint.size).isEqualTo(4)
    }

    @Test
    fun `validateMnemonic returns null for bad checksum`() {
        val bad = listOf(
            "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon"  // should be 'about'
        )
        assertThat(RecoveryCode.validateMnemonic(bad)).isNull()
    }

    @Test
    fun `validateMnemonic returns null for unknown word`() {
        val bad = listOf(
            "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "xyzzzz"
        )
        assertThat(RecoveryCode.validateMnemonic(bad)).isNull()
    }

    @Test
    fun `validateMnemonic returns null for wrong word count`() {
        val bad = listOf("abandon", "abandon", "abandon")
        assertThat(RecoveryCode.validateMnemonic(bad)).isNull()
    }

    @Test
    fun `rotate produces a new mnemonic + new pairingRoot`() {
        val entropy = ByteArray(16) { it.toByte() }
        val (newMnemonic, newRoot) = RecoveryCode.rotate(entropy)
        assertThat(newMnemonic.size).isEqualTo(12)
        assertThat(newRoot.size).isEqualTo(32)
        val oldRoot = RecoveryCode.derivePairingRoot(entropy)
        assertThat(newRoot).isNotEqualTo(oldRoot)
    }
}
