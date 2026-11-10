package com.tote.app.recovery

import com.google.common.truth.Truth.assertThat
import com.tote.app.crypto.Argon2Kdf
import com.tote.app.crypto.BIP39
import org.junit.Test

/**
 * JVM unit test for [RecoveryCode].
 *
 * Validates BIP-39 generation, validation, and Argon2id-based pairingRoot derivation.
 *
 * Per v0.5.0-alpha1: the [RecoveryCode.derivePairingRoot] now uses Argon2id
 * (memory-hard) instead of HKDF. The salt is required (no default).
 */
class RecoveryCodeTest {

    @Test
    fun `generate produces 12 words`() {
        val (mnemonic, _, _) = RecoveryCode.generate()
        assertThat(mnemonic.size).isEqualTo(12)
    }

    @Test
    fun `generate produces a valid mnemonic (passes BIP-39 checksum)`() {
        val (mnemonic, entropy, _) = RecoveryCode.generate()
        val validated = RecoveryCode.validateMnemonic(mnemonic)
        assertThat(validated).isNotNull()
        assertThat(validated).isEqualTo(entropy)
    }

    @Test
    fun `generate produces a 16-byte salt`() {
        val (_, _, salt) = RecoveryCode.generate()
        assertThat(salt.size).isEqualTo(16)
    }

    @Test
    fun `derivePairingRoot is deterministic for same entropy + salt`() {
        val entropy = ByteArray(16) { it.toByte() }
        val salt = ByteArray(16) { (it + 1).toByte() }
        val root1 = RecoveryCode.derivePairingRoot(entropy, salt)
        val root2 = RecoveryCode.derivePairingRoot(entropy.copyOf(), salt.copyOf())
        assertThat(root1).isEqualTo(root2)
        assertThat(root1.size).isEqualTo(32)
    }

    @Test
    fun `derivePairingRoot differs for different entropy`() {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val root1 = RecoveryCode.derivePairingRoot(ByteArray(16) { 0 }, salt)
        val root2 = RecoveryCode.derivePairingRoot(ByteArray(16) { 1 }, salt)
        assertThat(root1).isNotEqualTo(root2)
    }

    @Test
    fun `derivePairingRoot differs for different salt`() {
        val entropy = ByteArray(16) { 0 }
        val salt1 = ByteArray(16) { it.toByte() }
        val salt2 = ByteArray(16) { (it + 1).toByte() }
        val root1 = RecoveryCode.derivePairingRoot(entropy, salt1)
        val root2 = RecoveryCode.derivePairingRoot(entropy, salt2)
        assertThat(root1).isNotEqualTo(root2)
    }

    @Test
    fun `deriveRecoveryHint is 4 bytes`() {
        val root = ByteArray(32) { 0 }
        val hint = RecoveryCode.deriveRecoveryHint(root)
        assertThat(hint.size).isEqualTo(4)
    }

    @Test
    fun `deriveRecoveryHint is deterministic`() {
        val root = ByteArray(32) { it.toByte() }
        val hint1 = RecoveryCode.deriveRecoveryHint(root)
        val hint2 = RecoveryCode.deriveRecoveryHint(root.copyOf())
        assertThat(hint1).isEqualTo(hint2)
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
    fun `rotate produces a new mnemonic + new pairingRoot + new salt`() {
        val entropy = ByteArray(16) { it.toByte() }
        val result = RecoveryCode.rotate(entropy)
        assertThat(result.mnemonic.size).isEqualTo(12)
        assertThat(result.entropy.size).isEqualTo(16)
        assertThat(result.salt.size).isEqualTo(16)
        assertThat(result.pairingRoot.size).isEqualTo(32)
        // Rotated pairingRoot must differ from the original
        val oldRoot = RecoveryCode.derivePairingRoot(entropy, result.salt)
        assertThat(result.pairingRoot).isNotEqualTo(oldRoot)
    }

    @Test
    fun `rotate produces a valid mnemonic`() {
        val entropy = ByteArray(16) { it.toByte() }
        val result = RecoveryCode.rotate(entropy)
        val validated = RecoveryCode.validateMnemonic(result.mnemonic)
        assertThat(validated).isEqualTo(result.entropy)
    }

    @Test
    fun `rotate produces a different mnemonic each time`() {
        val entropy = ByteArray(16) { it.toByte() }
        val r1 = RecoveryCode.rotate(entropy)
        val r2 = RecoveryCode.rotate(entropy)
        // Two rotations of the same input produce different outputs (because
        // each generates a fresh random nonce).
        assertThat(r1.mnemonic).isNotEqualTo(r2.mnemonic)
    }
}
