package com.tote.app.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import javax.crypto.KeyGenerator

/**
 * JVM unit test for [KeystoreWrapper]'s AES-GCM math.
 *
 * We can't test the AndroidKeyStore path itself on the JVM (no "AndroidKeyStore"
 * provider available), but we CAN test that encryptWithKey → decryptWithKey
 * round-trips correctly with a software AES-256 key.
 *
 * Per build-learnings iron rule #15: we DO NOT claim crypto works until
 * a round-trip test passes.
 *
 * Strategy: bypass the [KeystoreWrapper] constructor entirely (which requires
 * a non-null Context) by constructing a no-arg shimmed wrapper. We extract
 * the AES-GCM math into a static helper that doesn't need a Context.
 *
 * Note: we test the AES-GCM math directly with a software key (not from
 * AndroidKeyStore). The AndroidKeyStore path is exercised on-device during
 * manual integration testing.
 */
class KeystoreWrapperTest {

    private fun newSoftwareKey(): javax.crypto.SecretKey {
        val kg = KeyGenerator.getInstance("AES")
        kg.init(256)
        return kg.generateKey()
    }

    @Test
    fun `encrypt_then_decrypt_round_trips_a_32_byte_seed`() {
        val key = newSoftwareKey()
        val plaintext = ByteArray(32).apply {
            java.security.SecureRandom().nextBytes(this)
        }
        val ciphertext = AesGcmMath.encrypt(plaintext, key)
        // Output should be iv(12) + ct + tag(16) = 32 + 12 + 16 = 60 bytes
        assertThat(ciphertext.size).isEqualTo(32 + 12 + 16)
        val recovered = AesGcmMath.decrypt(ciphertext, key)
        assertThat(recovered).isEqualTo(plaintext)
    }

    @Test
    fun `ciphertext_differs_from_plaintext`() {
        val key = newSoftwareKey()
        val plaintext = "tote-pairing-root-32-bytes-long-XX".toByteArray()
        val ciphertext = AesGcmMath.encrypt(plaintext, key)
        assertThat(ciphertext).isNotEqualTo(plaintext)
    }

    @Test
    fun `two_encryptions_of_same_plaintext_produce_different_ciphertexts`() {
        val key = newSoftwareKey()
        val plaintext = ByteArray(32).apply {
            java.security.SecureRandom().nextBytes(this)
        }
        val ct1 = AesGcmMath.encrypt(plaintext, key)
        val ct2 = AesGcmMath.encrypt(plaintext, key)
        // IVs differ, so ciphertexts differ.
        assertThat(ct1).isNotEqualTo(ct2)
    }

    @Test
    fun `decrypt_with_wrong_key_throws_AEADBadTagException`() {
        val key1 = newSoftwareKey()
        val key2 = newSoftwareKey()
        val plaintext = "this is a secret message 32 bytes!!".toByteArray()
        val ciphertext = AesGcmMath.encrypt(plaintext, key1)
        try {
            AesGcmMath.decrypt(ciphertext, key2)
            throw AssertionError("Expected AEADBadTagException")
        } catch (t: javax.crypto.AEADBadTagException) {
            // expected
        }
    }

    @Test
    fun `decrypt_with_tampered_ciphertext_throws_AEADBadTagException`() {
        val key = newSoftwareKey()
        val plaintext = "this is a secret message 32 bytes!!".toByteArray()
        val ciphertext = AesGcmMath.encrypt(plaintext, key)
        // Flip a bit in the middle of the ciphertext (after the IV)
        val tampered = ciphertext.copyOf()
        tampered[20] = (tampered[20].toInt() xor 0xFF).toByte()
        try {
            AesGcmMath.decrypt(tampered, key)
            throw AssertionError("Expected AEADBadTagException")
        } catch (t: javax.crypto.AEADBadTagException) {
            // expected
        }
    }

    @Test
    fun `decrypt_with_too_short_input_throws_IllegalArgumentException`() {
        val key = newSoftwareKey()
        val tooShort = ByteArray(10) // less than iv(12) + tag(16) = 28
        try {
            AesGcmMath.decrypt(tooShort, key)
            throw AssertionError("Expected IllegalArgumentException")
        } catch (t: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `decrypt_with_exactly_iv_plus_tag_bytes_succeeds_for_empty_plaintext`() {
        // Edge case: empty plaintext encrypts to exactly iv(12) + tag(16) = 28 bytes.
        // The decrypt side must accept this length (>= rather than >).
        val key = newSoftwareKey()
        val empty = ByteArray(0)
        val ciphertext = AesGcmMath.encrypt(empty, key)
        assertThat(ciphertext.size).isEqualTo(28)
        val recovered = AesGcmMath.decrypt(ciphertext, key)
        assertThat(recovered).isEqualTo(empty)
    }

    @Test
    fun `GCM_IV_LENGTH_is_12_bytes`() {
        assertThat(KeystoreWrapper.GCM_IV_LENGTH).isEqualTo(12)
    }

    @Test
    fun `GCM_TAG_LENGTH_BYTES_is_16_bytes`() {
        assertThat(KeystoreWrapper.GCM_TAG_LENGTH_BYTES).isEqualTo(16)
    }

    @Test
    fun `encrypt_handles_empty_plaintext`() {
        val key = newSoftwareKey()
        val empty = ByteArray(0)
        val ciphertext = AesGcmMath.encrypt(empty, key)
        // iv(12) + tag(16) = 28 bytes (no actual ciphertext)
        assertThat(ciphertext.size).isEqualTo(12 + 16)
        val recovered = AesGcmMath.decrypt(ciphertext, key)
        assertThat(recovered).isEqualTo(empty)
    }

    @Test
    fun `encrypt_handles_large_plaintext`() {
        val key = newSoftwareKey()
        val large = ByteArray(65536).apply {
            java.security.SecureRandom().nextBytes(this)
        }
        val ciphertext = AesGcmMath.encrypt(large, key)
        val recovered = AesGcmMath.decrypt(ciphertext, key)
        assertThat(recovered).isEqualTo(large)
    }
}

