package com.tote.app.crypto

import java.security.SecureRandom

/**
 * Per-device identity: Ed25519 (signing) + X25519 (key exchange), both derived from a 32B seed.
 * The seed is wrapped at rest (AndroidKeyStore / iOS Keychain) — this class only holds the derived keys.
 */
class DeviceIdentity(val seed: ByteArray) {
    init { require(seed.size == 32) { "Seed must be 32 bytes" } }

    val ed25519Pub: ByteArray by lazy { TODO("libsodium: crypto_sign_seed_keypair(seed).second") }
    val x25519Pub: ByteArray by lazy { TODO("libsodium: crypto_scalarmult_base(seed)") }

    fun sign(message: ByteArray): ByteArray = TODO("libsodium: crypto_sign_detached(message, ed25519Priv)")
    fun ecdh(peerX25519Pub: ByteArray): ByteArray = TODO("libsodium: crypto_scalarmult(seed, peerX25519Pub)")

    companion object {
        fun generate(): DeviceIdentity {
            val seed = ByteArray(32)
            SecureRandom().nextBytes(seed)
            return DeviceIdentity(seed)
        }
        fun verify(message: ByteArray, signature: ByteArray, pub: ByteArray): Boolean =
            TODO("libsodium: crypto_sign_verify_detached(signature, message, pub)")
    }
}
