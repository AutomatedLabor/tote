package com.tote.app.crypto

import com.goterl.lazysodium.interfaces.PasswordHash
import java.security.SecureRandom
import java.util.logging.Logger

/**
 * Argon2id password-based key derivation, backed by libsodium's `crypto_pwhash`.
 *
 * Per CRYPTO-SPEC-FINAL §5: the recovery code's 12-word BIP-39 mnemonic encodes
 * 128 bits of entropy, but that entropy is too short to use directly as a
 * pairingRoot — an attacker with the entropy could brute-force it. Argon2id
 * (memory-hard, ~64MB per attempt) makes brute-force economically impractical.
 *
 * Parameters (per OWASP 2023 recommendations for high-value secrets):
 *   - algorithm: Argon2id (libsodium's `crypto_pwhash_ALG_ARGON2ID13`)
 *   - opsLimit: 3 (between interactive=2 and moderate=5)
 *   - memLimit: 64 MiB (67_108_864 bytes; OWASP recommends 64 MB+)
 *   - output length: 32 bytes (matches pairingRoot length)
 *   - salt length: 16 bytes (crypto_pwhash_SALTBYTES)
 *
 * The salt is stored alongside the derived key in [com.tote.app.crypto.IdentityStore].
 * An attacker with the salt but not the mnemonic cannot derive the key.
 *
 * Thread-safety: libsodium's crypto_pwhash is thread-safe (no global mutable
 * state in the implementation), so we don't need to synchronize.
 *
 * JVM-testability: works on the JVM (libsodium is JNA-extracted from the jar)
 * AND on Android (libsodium-android .so bundled per-ABI).
 */
object Argon2Kdf {

    private val LOG = Logger.getLogger("Argon2Kdf")
    private val RANDOM = SecureRandom()

    /**
     * Generate a fresh 16-byte salt for use with [derive].
     *
     * The salt is NOT secret — it can be stored in plaintext alongside the
     * derived key. Its purpose is to defeat pre-computed rainbow tables.
     */
    fun generateSalt(): ByteArray {
        val salt = ByteArray(PasswordHash.SALTBYTES)
        RANDOM.nextBytes(salt)
        return salt
    }

    /**
     * Derive a 32-byte key from a password + salt using Argon2id.
     *
     * @param password The input password (e.g., the recovery entropy bytes)
     * @param salt 16-byte salt (use [generateSalt] for a fresh one)
     * @param opsLimit Number of operations (compute cost). Default 3.
     * @param memLimit Memory cost in bytes. Default 64 MiB.
     * @return 32-byte derived key
     * @throws RuntimeException if libsodium's crypto_pwhash returns false
     *   (which it does on out-of-memory or invalid params)
     */
    fun derive(
        password: ByteArray,
        salt: ByteArray,
        opsLimit: Long = OPSLIMIT_HIGH_VALUE,
        memLimit: Long = MEMLIMIT_HIGH_VALUE,
        outputLength: Int = 32,
    ): ByteArray {
        require(salt.size == PasswordHash.SALTBYTES) {
            "Salt must be ${PasswordHash.SALTBYTES} bytes (got ${salt.size})"
        }
        require(outputLength in 16..128) {
            "Output length must be 16-128 bytes (got $outputLength)"
        }
        val out = ByteArray(outputLength)
        val sodium = LazysodiumBootstrap.get()
        val ok = sodium.cryptoPwHash(
            out,
            outputLength,
            password,
            password.size,
            salt,
            opsLimit,
            memLimit,
            PasswordHash.ALG_ARGON2ID13,
        )
        if (!ok) {
            throw RuntimeException(
                "crypto_pwhash failed — likely OOM (memLimit=${memLimit / 1024 / 1024}MB)"
            )
        }
        return out
    }

    /**
     * Convenience: derive a 32-byte key from a mnemonic + salt.
     *
     * The mnemonic is converted to bytes via UTF-8 (BIP-39 words are ASCII).
     * This is what [com.tote.app.recovery.RecoveryCode.derivePairingRoot]
     * calls under the hood.
     */
    fun deriveFromMnemonic(
        mnemonic: List<String>,
        salt: ByteArray,
        opsLimit: Long = OPSLIMIT_HIGH_VALUE,
        memLimit: Long = MEMLIMIT_HIGH_VALUE,
    ): ByteArray {
        val password = mnemonic.joinToString(" ").toByteArray(Charsets.UTF_8)
        return derive(password, salt, opsLimit, memLimit, 32)
    }

    /**
     * Smoke test: run a single Argon2id derivation with low cost params.
     *
     * Useful for verifying that lazysodium's crypto_pwhash is actually wired
     * (the lazy singleton doesn't validate the interface until first call).
     */
    fun smokeTest(): Boolean {
        return try {
            val out = derive(
                password = "tote-smoke-test".toByteArray(),
                salt = ByteArray(PasswordHash.SALTBYTES),  // all-zero salt for the smoke test
                opsLimit = 1L,  // minimum
                memLimit = 8 * 1024 * 1024,  // 8 MB
                outputLength = 16,
            )
            // The result should be non-zero (extremely unlikely to be all-zero)
            out.any { it != 0.toByte() }
        } catch (t: Throwable) {
            LOG.warning("Argon2Kdf smoke test FAILED: ${t.message}")
            false
        }
    }

    /**
     * Cost parameters per use-case, following OWASP 2023 + libsodium's
     * interactive/moderate/sensitive presets.
     */
    object Cost {
        const val OPSLIMIT_INTERACTIVE: Long = 2L
        const val MEMLIMIT_INTERACTIVE: Long = 67_108_864L  // 64 MB
        const val OPSLIMIT_MODERATE: Long = 3L
        const val MEMLIMIT_MODERATE: Long = 268_435_456L  // 256 MB
        const val OPSLIMIT_SENSITIVE: Long = 4L
        const val MEMLIMIT_SENSITIVE: Long = 1_073_741_824L  // 1 GB
    }

    // Default: high-value secret (recovery code is the master secret)
    private val OPSLIMIT_HIGH_VALUE = Cost.OPSLIMIT_MODERATE
    private val MEMLIMIT_HIGH_VALUE = Cost.MEMLIMIT_INTERACTIVE  // 64 MB — constrained devices
}
