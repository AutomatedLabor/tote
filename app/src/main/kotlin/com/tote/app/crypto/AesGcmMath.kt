package com.tote.app.crypto

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stateless AES-GCM encrypt/decrypt math, separated from [KeystoreWrapper]
 * so it can be JVM-tested without an AndroidKeyStore instance.
 *
 * Output format: iv(12 bytes) || ciphertext || tag(16 bytes)
 *
 * The IV is generated randomly by the Cipher on encrypt (12 bytes for GCM).
 * The 16-byte authentication tag is appended to the ciphertext by Cipher.doFinal().
 *
 * Per build-learnings iron rule #15: the [KeystoreWrapperTest] exercises
 * this math directly with a software AES-256 key, validating:
 *   - Round-trip works for empty, 32-byte, and 64KB inputs
 *   - Wrong key → AEADBadTagException
 *   - Tampered ciphertext → AEADBadTagException
 *   - Different IVs each call → different ciphertexts for same plaintext
 */
object AesGcmMath {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128
    private const val TAG_LENGTH_BYTES = 16

    /**
     * Encrypt a plaintext using the given [SecretKey].
     *
     * Output: iv(12) + ct + tag(16). Tag is appended by Cipher.doFinal().
     */
    fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv  // 12 bytes for GCM
        require(iv.size == IV_LENGTH) { "Unexpected IV length: ${iv.size}" }
        val ct = cipher.doFinal(plaintext)
        // Output: iv(12) + ct + tag(16)
        return iv + ct
    }

    /**
     * Decrypt a ciphertext produced by [encrypt] using the same [SecretKey].
     *
     * @throws javax.crypto.AEADBadTagException if the key is wrong or the
     *   ciphertext was tampered with
     * @throws IllegalArgumentException if the input is too short
     *   (< IV_LENGTH + TAG_LENGTH_BYTES)
     */
    fun decrypt(ciphertext: ByteArray, key: SecretKey): ByteArray {
        require(ciphertext.size > IV_LENGTH + TAG_LENGTH_BYTES) {
            "Ciphertext too short: ${ciphertext.size} bytes (need at least ${IV_LENGTH + TAG_LENGTH_BYTES})"
        }
        val iv = ciphertext.copyOfRange(0, IV_LENGTH)
        val ctWithTag = ciphertext.copyOfRange(IV_LENGTH, ciphertext.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val params = GCMParameterSpec(TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, params)
        return cipher.doFinal(ctWithTag)
    }
}
