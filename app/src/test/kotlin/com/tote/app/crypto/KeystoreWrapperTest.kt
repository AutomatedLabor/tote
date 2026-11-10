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
 * Note: we construct the [KeystoreWrapper] with `context = null` because
 * encryptWithKey / decryptWithKey never touch the AndroidKeyStore (the key
 * is supplied directly to the test). The context is only used by
 * [KeystoreWrapper.getOrCreateMasterKey] / encrypt / decrypt (the Android-only
 * methods) — which we don't exercise here.
 */
class KeystoreWrapperTest {

    // Cast to Context? — we don't have a real Context on the JVM, and we
    // can't pass null directly. We use a suppressed-cast trick: the tests
    // below only call encryptWithKey/decryptWithKey which never dereference
    // the context. The Context type parameter is purely for the Android
    // constructor signature.
    @Suppress("UNCHECKED_CAST")
    private val mockContext: android.content.Context
        get() = null as android.content.Context

    private fun newSoftwareKey(): javax.crypto.SecretKey {
        val kg = KeyGenerator.getInstance("AES")
        kg.init(256)
        return kg.generateKey()
    }

    private fun newKs(): KeystoreWrapper = KeystoreWrapper(mockContext)

    @Test
    fun `encrypt_then_decrypt_round_trips_a_32_byte_seed`() {
        val ks = newKs()
        val key = newSoftwareKey()
        val plaintext = ByteArray(32).apply {
            java.security.SecureRandom().nextBytes(this)
        }
        val ciphertext = ks.encryptWithKey(plaintext, key)
        // Output should be iv(12) + ct + tag(16) = 32 + 12 + 16 = 60 bytes
        assertThat(ciphertext.size).isEqualTo(32 + 12 + 16)
        val recovered = ks.decryptWithKey(ciphertext, key)
        assertThat(recovered).isEqualTo(plaintext)
    }

    @Test
    fun `ciphertext_differs_from_plaintext`() {
        val ks = newKs()
        val key = newSoftwareKey()
        val plaintext = "tote-pairing-root-32-bytes-long-XX".toByteArray()
        val ciphertext = ks.encryptWithKey(plaintext, key)
        // The first 12 bytes are IV (random), the rest is ct+tag.
        // The IV is always different from the plaintext prefix (extremely unlikely collision).
        assertThat(ciphertext).isNotEqualTo(plaintext)
    }

    @Test
    fun `two_encryptions_of_same_plaintext_produce_different_ciphertexts`() {
        val ks = newKs()
        val key = newSoftwareKey()
        val plaintext = ByteArray(32).apply {
            java.security.SecureRandom().nextBytes(this)
        }
        val ct1 = ks.encryptWithKey(plaintext, key)
        val ct2 = ks.encryptWithKey(plaintext, key)
        // IVs differ, so ciphertexts differ.
        assertThat(ct1).isNotEqualTo(ct2)
    }

    @Test
    fun `decrypt_with_wrong_key_throws_AEADBadTagException`() {
        val ks = newKs()
        val key1 = newSoftwareKey()
        val key2 = newSoftwareKey()
        val plaintext = "this is a secret message 32 bytes!!".toByteArray()
        val ciphertext = ks.encryptWithKey(plaintext, key1)
        try {
            ks.decryptWithKey(ciphertext, key2)
            throw AssertionError("Expected AEADBadTagException")
        } catch (t: javax.crypto.AEADBadTagException) {
            // expected
        }
    }

    @Test
    fun `decrypt_with_tampered_ciphertext_throws_AEADBadTagException`() {
        val ks = newKs()
        val key = newSoftwareKey()
        val plaintext = "this is a secret message 32 bytes!!".toByteArray()
        val ciphertext = ks.encryptWithKey(plaintext, key)
        // Flip a bit in the middle of the ciphertext (after the IV)
        val tampered = ciphertext.copyOf()
        tampered[20] = (tampered[20].toInt() xor 0xFF).toByte()
        try {
            ks.decryptWithKey(tampered, key)
            throw AssertionError("Expected AEADBadTagException")
        } catch (t: javax.crypto.AEADBadTagException) {
            // expected
        }
    }

    @Test
    fun `decrypt_with_too_short_input_throws_IllegalArgumentException`() {
        val ks = newKs()
        val key = newSoftwareKey()
        val tooShort = ByteArray(10) // less than iv(12) + tag(16) = 28
        try {
            ks.decryptWithKey(tooShort, key)
            throw AssertionError("Expected IllegalArgumentException")
        } catch (t: IllegalArgumentException) {
            // expected
        }
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
        val ks = newKs()
        val key = newSoftwareKey()
        val empty = ByteArray(0)
        val ciphertext = ks.encryptWithKey(empty, key)
        // iv(12) + tag(16) = 28 bytes (no actual ciphertext)
        assertThat(ciphertext.size).isEqualTo(12 + 16)
        val recovered = ks.decryptWithKey(ciphertext, key)
        assertThat(recovered).isEqualTo(empty)
    }

    @Test
    fun `encrypt_handles_large_plaintext`() {
        val ks = newKs()
        val key = newSoftwareKey()
        val large = ByteArray(65536).apply {
            java.security.SecureRandom().nextBytes(this)
        }
        val ciphertext = ks.encryptWithKey(large, key)
        val recovered = ks.decryptWithKey(ciphertext, key)
        assertThat(recovered).isEqualTo(large)
    }
}
