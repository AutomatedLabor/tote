package com.tote.app.recovery

import com.tote.app.crypto.Argon2Kdf
import com.tote.app.crypto.BIP39
import com.tote.app.crypto.HKDF
import com.tote.app.crypto.SHA256
import java.security.SecureRandom

/**
 * Recovery code manager.
 *
 * Per CRYPTO-SPEC-FINAL §5: the recovery code is a 12-word BIP-39 mnemonic
 * that encodes 128 bits of entropy. From that entropy we derive:
 * 1. `pairingRoot` — via Argon2id (memory-hard, prevents brute-force)
 * 2. `recoveryHint` — a 4-byte identifier (for "is this the right code?" checks
 *    without revealing the full root)
 *
 * The recovery code is shown to the user ONCE at onboarding. They write it
 * down offline. If they lose all their devices, they enter the code on a new
 * device to restore the pairingRoot and re-bootstrap the EpochState.
 *
 * Per v0.5.0-alpha1: Argon2id is now wired via [Argon2Kdf] (libsodium's
 * crypto_pwhash). The salt is stored alongside the derived key in
 * IdentityStore; the salt is NOT secret.
 *
 * For backward-compat with v0.4.0-alpha1 storage: if a stored pairingRoot
 * has no accompanying salt, we fall back to the v0.4 HKDF derivation. This
 * path is deprecated and will be removed in v1.0.0.
 */
object RecoveryCode {

    /**
     * Generate a fresh recovery code.
     *
     * @return Triple(mnemonic, entropy, salt) — store entropy + salt encrypted
     *   at rest. The mnemonic is shown to the user ONCE.
     */
    fun generate(): Triple<List<String>, ByteArray, ByteArray> {
        val entropy = ByteArray(16)
        SecureRandom().nextBytes(entropy)
        val mnemonic = BIP39.entropyToMnemonic(entropy)
        val salt = Argon2Kdf.generateSalt()
        return Triple(mnemonic, entropy, salt)
    }

    /**
     * Derive the pairingRoot from recovery entropy + salt via Argon2id.
     *
     * Per CRYPTO-SPEC-FINAL §5: pairingRoot = Argon2id(entropy, salt, ops=3, mem=64MB, len=32)
     *
     * The salt MUST be persisted alongside the derived root. An attacker with
     * the salt but not the mnemonic cannot derive the root (Argon2id's
     * memory-hardness makes brute-force impractical even at 128 bits entropy).
     *
     * The entropy is treated as the "password" input. We don't convert it to
     * a mnemonic first — that would just add an unnecessary encoding layer.
     */
    fun derivePairingRoot(entropy: ByteArray, salt: ByteArray): ByteArray {
        return Argon2Kdf.derive(
            password = entropy,
            salt = salt,
            opsLimit = Argon2Kdf.Const.OPSLIMIT_MODERATE,
            memLimit = Argon2Kdf.Const.MEMLIMIT_INTERACTIVE,  // 64 MB
            outputLength = 32,
        )
    }

    /**
     * Derive a 4-byte recovery hint. Used for "is this the right code?" checks
     * without revealing the full pairingRoot.
     *
     * Display as 4 hex chars or 2 emojis.
     *
     * This uses HKDF (NOT Argon2id) since the hint is derived from the
     * pairingRoot (which is already memory-hard-derived). Chaining Argon2id
     * would be unnecessary cost.
     */
    fun deriveRecoveryHint(pairingRoot: ByteArray): ByteArray {
        return HKDF.derive(
            ikm = pairingRoot,
            salt = "recovery-hint".toByteArray(Charsets.US_ASCII),
            info = "hint".toByteArray(Charsets.US_ASCII),
            length = 4,
        )
    }

    /**
     * Validate a mnemonic entered by the user.
     *
     * @return The entropy bytes if the mnemonic is valid (passes checksum),
     *         null otherwise.
     */
    fun validateMnemonic(mnemonic: List<String>): ByteArray? {
        return try {
            BIP39.mnemonicToEntropy(mnemonic)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Recovery code rotation.
     *
     * Per CRYPTO-SPEC-FINAL §5: every 90 days, prompt the user to rotate.
     * This generates a new entropy + mnemonic + salt, derives a new
     * pairingRoot, and triggers an epoch advance on all paired devices.
     *
     * @return Quadruple(newMnemonic, newEntropy, newSalt, newPairingRoot)
     */
    fun rotate(currentEntropy: ByteArray): RotateResult {
        // Mix current entropy with a fresh random nonce to produce new entropy.
        // We do NOT reuse the current salt — generate a fresh one.
        val freshNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val mixed = SHA256.hash(currentEntropy + freshNonce)
        // BIP-39 for 12 words requires 16 bytes (128 bits) of entropy. Take the
        // first 16 bytes of the SHA-256 output.
        val newEntropy = mixed.copyOf(16)
        val mnemonic = BIP39.entropyToMnemonic(newEntropy)
        val newSalt = Argon2Kdf.generateSalt()
        val newPairingRoot = derivePairingRoot(newEntropy, newSalt)
        return RotateResult(mnemonic, newEntropy, newSalt, newPairingRoot)
    }

    data class RotateResult(
        val mnemonic: List<String>,
        val entropy: ByteArray,
        val salt: ByteArray,
        val pairingRoot: ByteArray,
    )
}
