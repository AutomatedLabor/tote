package com.tote.app.crypto

import com.goterl.lazysodium.interfaces.DiffieHellman
import com.goterl.lazysodium.interfaces.Sign
import java.security.SecureRandom

/**
 * Per-device identity: Ed25519 (signing) + X25519 (key exchange), both derived
 * from a 32-byte seed.
 *
 * Per CRYPTO-SPEC-FINAL.md §3:
 * - The seed is wrapped at rest (AndroidKeyStore / iOS Keychain).
 * - This class only holds the derived keys in memory.
 * - The Ed25519 secret key (64 bytes, seed||pub) is NOT exposed — callers
 *   use [sign] which proxies through lazysodium.
 *
 * Implementation: uses the lazysodium native byte-array API directly (no
 * String/Key wrappers) for efficiency and to avoid base64 round-trips on
 * every operation.
 */
class DeviceIdentity(val seed: ByteArray) {
    init { require(seed.size == 32) { "Seed must be 32 bytes (got ${seed.size})" } }

    /** Ed25519 public key (32 bytes). Lazy — only derived on first access. */
    val ed25519Pub: ByteArray by lazy {
        ByteArray(Sign.ED25519_PUBLICKEYBYTES).also { pk ->
            val sk = ByteArray(Sign.ED25519_SECRETKEYBYTES)
            val ok = LazysodiumBootstrap.get()
                .cryptoSignSeedKeypair(pk, sk, seed)
            check(ok) { "cryptoSignSeedKeypair returned false" }
            // Cache the secret key for later sign() calls.
            _ed25519Sec = sk
        }
    }

    /** Ed25519 secret key (64 bytes — libsodium's "secret key" = seed || pub). */
    private var _ed25519Sec: ByteArray? = null
    val ed25519Sec: ByteArray
        get() {
            // Trigger pubkey derivation if it hasn't happened yet (that also fills _ed25519Sec).
            ed25519Pub
            return _ed25519Sec ?: error("ed25519Sec not initialized")
        }

    /** X25519 public key (32 bytes) — derived via scalar mult on the curve base. */
    val x25519Pub: ByteArray by lazy {
        ByteArray(DiffieHellman.SCALARMULT_BYTES).also { out ->
            val ok = LazysodiumBootstrap.get()
                .cryptoScalarMultBase(out, seed)
            check(ok) { "cryptoScalarMultBase returned false" }
        }
    }

    /**
     * Sign a message with the Ed25519 secret key.
     * Returns the detached 64-byte signature.
     */
    fun sign(message: ByteArray): ByteArray {
        val sig = ByteArray(Sign.ED25519_BYTES)
        val ok = LazysodiumBootstrap.get()
            .cryptoSignDetached(sig, message, message.size.toLong(), ed25519Sec)
        check(ok) { "cryptoSignDetached returned false" }
        return sig
    }

    /**
     * X25519 ECDH with a peer's public key.
     * Returns the raw 32-byte shared secret. CALLER is responsible for
     * feeding this through HKDF before use as a symmetric key.
     */
    fun ecdh(peerX25519Pub: ByteArray): ByteArray {
        require(peerX25519Pub.size == DiffieHellman.SCALARMULT_BYTES) {
            "Peer X25519 pub must be 32 bytes (got ${peerX25519Pub.size})"
        }
        val shared = ByteArray(DiffieHellman.SCALARMULT_BYTES)
        val ok = LazysodiumBootstrap.get()
            .cryptoScalarMult(shared, seed, peerX25519Pub)
        check(ok) { "cryptoScalarMult returned false" }
        return shared
    }

    companion object {
        /** Generate a brand-new [DeviceIdentity] with a cryptographically-random seed. */
        fun generate(): DeviceIdentity {
            val seed = ByteArray(32)
            SecureRandom().nextBytes(seed)
            return DeviceIdentity(seed)
        }

        /**
         * Verify an Ed25519 detached signature against a public key.
         * Constant-time per libsodium's `crypto_sign_verify_detached`.
         */
        fun verify(message: ByteArray, signature: ByteArray, pub: ByteArray): Boolean {
            require(signature.size == Sign.ED25519_BYTES) {
                "Signature must be 64 bytes (got ${signature.size})"
            }
            require(pub.size == Sign.ED25519_PUBLICKEYBYTES) {
                "Ed25519 pub must be 32 bytes (got ${pub.size})"
            }
            return LazysodiumBootstrap.get()
                .cryptoSignVerifyDetached(signature, message, message.size, pub)
        }
    }
}
