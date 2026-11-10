package com.tote.app.recovery

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
 * NOTE: Argon2id is not yet wired (lazysodium's `cryptoPwHash` is available
 * but requires a wrapper). For the v1 alpha we use a simpler HKDF-based
 * derivation. Argon2id is a TODO for v1.0.0 ship.
 */
object RecoveryCode {

    /**
     * Generate a fresh recovery code.
     *
     * @return Pair(mnemonic, entropy) — store entropy encrypted at rest.
     */
    fun generate(): Pair<List<String>, ByteArray> {
        val entropy = ByteArray(16)
        SecureRandom().nextBytes(entropy)
        val mnemonic = BIP39.entropyToMnemonic(entropy)
        return mnemonic to entropy
    }

    /**
     * Derive the pairingRoot from recovery entropy.
     *
     * Per CRYPTO-SPEC-FINAL §5: HKDF(entropy, salt="recovery-v1", info="pairingRoot", 32)
     *
     * TODO v1.0.0: replace HKDF with Argon2id (memory-hard) — lazysodium's
     * crypto_pwhash. Argon2id parameters per OWASP: t=2, m=64MB, p=1.
     */
    fun derivePairingRoot(entropy: ByteArray): ByteArray {
        return HKDF.derive(
            ikm = entropy,
            salt = "recovery-v1".toByteArray(Charsets.US_ASCII),
            info = "pairing-root".toByteArray(Charsets.US_ASCII),
            length = 32,
        )
    }

    /**
     * Derive a 4-byte recovery hint. Used for "is this the right code?" checks
     * without revealing the full pairingRoot.
     *
     * Display as 4 hex chars or 2 emojis.
     */
    fun deriveRecoveryHint(entropy: ByteArray): ByteArray {
        return HKDF.derive(
            ikm = entropy,
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
     * This generates a new entropy + mnemonic, derives a new pairingRoot,
     * and triggers an epoch advance on all paired devices.
     *
     * Returns the new mnemonic + new pairingRoot.
     */
    fun rotate(currentEntropy: ByteArray): Pair<List<String>, ByteArray> {
        // Mix current entropy with a fresh random nonce to produce new entropy.
        val freshNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val mixedEntropy = SHA256.hash(currentEntropy + freshNonce)
        val mnemonic = BIP39.entropyToMnemonic(mixedEntropy)
        val newPairingRoot = derivePairingRoot(mixedEntropy)
        return mnemonic to newPairingRoot
    }
}
