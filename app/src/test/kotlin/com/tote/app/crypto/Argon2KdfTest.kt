package com.tote.app.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM unit test for [Argon2Kdf].
 *
 * Verifies that libsodium's crypto_pwhash (Argon2id) is correctly wired via
 * lazysodium-java and produces stable, deterministic output for the same
 * password + salt + cost parameters.
 *
 * Per build-learnings iron rule #15: we DO NOT claim Argon2id works until
 * a round-trip test (derive → derive again → compare) passes.
 *
 * Note: lazysodium-java is JNA-extracted from the jar at runtime, so this
 * test exercises the REAL libsodium (not a software mock). If libsodium
 * fails to load on the JVM, every test in this class will fail with a
 * UnsatisfiedLinkError — which would itself be a real, actionable
 * regression that we need to know about.
 */
class Argon2KdfTest {

    @Test
    fun `derive_with_same_password_salt_and_params_returns_same_output`() {
        val password = "tote-recovery-entropy-32-bytes!".toByteArray()
        val salt = ByteArray(16).apply {
            // Fixed salt (not zero) for test reproducibility
            for (i in indices) salt[i] = (i + 1).toByte()
        }
        val out1 = Argon2Kdf.derive(
            password = password,
            salt = salt,
            opsLimit = 1L,  // minimum for test speed
            memLimit = 8 * 1024 * 1024,  // 8 MB
            outputLength = 32,
        )
        val out2 = Argon2Kdf.derive(
            password = password,
            salt = salt,
            opsLimit = 1L,
            memLimit = 8 * 1024 * 1024,
            outputLength = 32,
        )
        assertThat(out1).isEqualTo(out2)
        assertThat(out1.size).isEqualTo(32)
    }

    @Test
    fun `derive_with_different_password_returns_different_output`() {
        val salt = ByteArray(16).apply {
            for (i in indices) this[i] = (i + 1).toByte()
        }
        val out1 = Argon2Kdf.derive(
            password = "password1".toByteArray(),
            salt = salt,
            opsLimit = 1L,
            memLimit = 8 * 1024 * 1024,
        )
        val out2 = Argon2Kdf.derive(
            password = "password2".toByteArray(),
            salt = salt,
            opsLimit = 1L,
            memLimit = 8 * 1024 * 1024,
        )
        assertThat(out1).isNotEqualTo(out2)
    }

    @Test
    fun `derive_with_different_salt_returns_different_output`() {
        val password = "tote-recovery".toByteArray()
        val salt1 = ByteArray(16) { it.toByte() }
        val salt2 = ByteArray(16) { (it + 1).toByte() }
        val out1 = Argon2Kdf.derive(password, salt1, opsLimit = 1L, memLimit = 8 * 1024 * 1024)
        val out2 = Argon2Kdf.derive(password, salt2, opsLimit = 1L, memLimit = 8 * 1024 * 1024)
        assertThat(out1).isNotEqualTo(out2)
    }

    @Test
    fun `derive_output_is_non_zero`() {
        val password = "test-password-non-empty".toByteArray()
        val salt = ByteArray(16) { (it + 1).toByte() }
        val out = Argon2Kdf.derive(password, salt, opsLimit = 1L, memLimit = 8 * 1024 * 1024)
        // Probability of all-zero output is astronomically low
        assertThat(out.any { it != 0.toByte() }).isTrue()
    }

    @Test
    fun `generateSalt_returns_16_bytes`() {
        val salt = Argon2Kdf.generateSalt()
        assertThat(salt.size).isEqualTo(16)
    }

    @Test
    fun `generateSalt_returns_different_values_on_each_call`() {
        val salt1 = Argon2Kdf.generateSalt()
        val salt2 = Argon2Kdf.generateSalt()
        assertThat(salt1).isNotEqualTo(salt2)
    }

    @Test
    fun `derive_with_wrong_salt_length_throws_IllegalArgumentException`() {
        val password = "test".toByteArray()
        val badSalt = ByteArray(15)  // wrong length
        try {
            Argon2Kdf.derive(password, badSalt, opsLimit = 1L, memLimit = 8 * 1024 * 1024)
            throw AssertionError("Expected IllegalArgumentException")
        } catch (t: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `deriveFromMnemonic_round_trips_with_correct_password`() {
        val mnemonic = listOf(
            "abandon", "ability", "able", "about", "above", "absent",
            "absorb", "abstract", "absurd", "abuse", "access", "accident",
        )
        val salt = ByteArray(16) { (it + 1).toByte() }
        val out1 = Argon2Kdf.deriveFromMnemonic(mnemonic, salt, opsLimit = 1L, memLimit = 8 * 1024 * 1024)
        val out2 = Argon2Kdf.deriveFromMnemonic(mnemonic, salt, opsLimit = 1L, memLimit = 8 * 1024 * 1024)
        assertThat(out1).isEqualTo(out2)
        assertThat(out1.size).isEqualTo(32)
    }

    @Test
    fun `smokeTest_returns_true`() {
        // This exercises the full libsodium crypto_pwhash path at minimum cost.
        // If this fails, libsodium isn't loading properly on the test JVM.
        assertThat(Argon2Kdf.smokeTest()).isTrue()
    }

    @Test
    fun `Cost_constants_are_correct_per_OWASP`() {
        // Interactive: 64 MB / 2 ops — for interactive login flows
        assertThat(Argon2Kdf.Cost.OPSLIMIT_INTERACTIVE).isEqualTo(2L)
        assertThat(Argon2Kdf.Cost.MEMLIMIT_INTERACTIVE).isEqualTo(67_108_864L)
        // Moderate: 256 MB / 3 ops — for sensitive operations
        assertThat(Argon2Kdf.Cost.OPSLIMIT_MODERATE).isEqualTo(3L)
        assertThat(Argon2Kdf.Cost.MEMLIMIT_MODERATE).isEqualTo(268_435_456L)
        // Sensitive: 1 GB / 4 ops — for master key derivation
        assertThat(Argon2Kdf.Cost.OPSLIMIT_SENSITIVE).isEqualTo(4L)
        assertThat(Argon2Kdf.Cost.MEMLIMIT_SENSITIVE).isEqualTo(1_073_741_824L)
    }
}
