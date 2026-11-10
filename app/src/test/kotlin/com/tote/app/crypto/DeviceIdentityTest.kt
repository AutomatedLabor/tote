package com.tote.app.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM unit test for [DeviceIdentity].
 *
 * Validates that lazysodium loads via JNA, and that Ed25519 sign/verify and
 * X25519 ECDH work correctly with round-trip + tamper-detection tests.
 *
 * Per build-learnings iron rule #15: this test passing is what gives us
 * the right to claim "crypto works" — until it passes, all crypto code is
 * aspirational.
 */
class DeviceIdentityTest {

    @Test
    fun `libSodium loads via JNA without throwing`() {
        // Just calling get() and using the instance is enough — if JNA can't
        // extract the native lib, the LazySodiumJava constructor throws.
        val ls = LazysodiumBootstrap.get()
        // Smoke test: crypto scalarmult base should not throw on a zero seed.
        val seed = ByteArray(32)
        val pub = ByteArray(32)
        val ok = ls.cryptoScalarMultBase(pub, seed)
        // libsodium returns false for the all-zero seed (small subgroup),
        // but doesn't throw. Either result proves libsodium is loaded.
        // We don't assert ok here because zero-seed is degenerate.
        // (See X25519 docs: small-order points are rejected.)
        assertThat(pub.size).isEqualTo(32)
    }

    @Test
    fun `Ed25519 sign and verify round-trip`() {
        val alice = DeviceIdentity.generate()
        val message = "the quick brown fox jumps over the lazy dog".toByteArray()

        val sig = alice.sign(message)
        assertThat(sig.size).isEqualTo(64)

        val valid = DeviceIdentity.verify(message, sig, alice.ed25519Pub)
        assertThat(valid).isTrue()
    }

    @Test
    fun `Ed25519 verify rejects tampered message`() {
        val alice = DeviceIdentity.generate()
        val message = "hello".toByteArray()
        val sig = alice.sign(message)

        val tampered = "goodbye".toByteArray()
        val valid = DeviceIdentity.verify(tampered, sig, alice.ed25519Pub)
        assertThat(valid).isFalse()
    }

    @Test
    fun `Ed25519 verify rejects tampered signature`() {
        val alice = DeviceIdentity.generate()
        val message = "hello".toByteArray()
        val sig = alice.sign(message).copyOf()
        sig[0] = (sig[0] + 1).toByte()  // flip a bit

        val valid = DeviceIdentity.verify(message, sig, alice.ed25519Pub)
        assertThat(valid).isFalse()
    }

    @Test
    fun `Ed25519 verify rejects wrong public key`() {
        val alice = DeviceIdentity.generate()
        val bob = DeviceIdentity.generate()
        val message = "hello".toByteArray()
        val sig = alice.sign(message)

        val valid = DeviceIdentity.verify(message, sig, bob.ed25519Pub)
        assertThat(valid).isFalse()
    }

    @Test
    fun `Ed25519 verify rejects short signature`() {
        val alice = DeviceIdentity.generate()
        val message = "hello".toByteArray()
        val shortSig = ByteArray(32)  // too short

        try {
            DeviceIdentity.verify(message, shortSig, alice.ed25519Pub)
            error("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("64 bytes")
        }
    }

    @Test
    fun `Ed25519 pub key is deterministic from seed`() {
        val seed = ByteArray(32) { it.toByte() }
        val alice1 = DeviceIdentity(seed)
        val alice2 = DeviceIdentity(seed.copyOf())

        assertThat(alice1.ed25519Pub).isEqualTo(alice2.ed25519Pub)
        assertThat(alice1.x25519Pub).isEqualTo(alice2.x25519Pub)
    }

    @Test
    fun `Ed25519 pubkey is 32 bytes`() {
        val alice = DeviceIdentity.generate()
        assertThat(alice.ed25519Pub.size).isEqualTo(32)
    }

    @Test
    fun `Ed25519 secret key is 64 bytes (seed || pub)`() {
        val alice = DeviceIdentity.generate()
        // Force secret key derivation by triggering pubkey
        assertThat(alice.ed25519Pub).isNotNull()
        assertThat(alice.ed25519Sec.size).isEqualTo(64)
    }

    @Test
    fun `X25519 pubkey is 32 bytes`() {
        val alice = DeviceIdentity.generate()
        assertThat(alice.x25519Pub.size).isEqualTo(32)
    }

    @Test
    fun `X25519 ECDH — Alice and Bob derive the same shared secret`() {
        val alice = DeviceIdentity.generate()
        val bob = DeviceIdentity.generate()

        val aliceShared = alice.ecdh(bob.x25519Pub)
        val bobShared = bob.ecdh(alice.x25519Pub)

        assertThat(aliceShared).isEqualTo(bobShared)
        assertThat(aliceShared.size).isEqualTo(32)
    }

    @Test
    fun `X25519 ECDH — different peers produce different shared secrets`() {
        val alice = DeviceIdentity.generate()
        val bob = DeviceIdentity.generate()
        val carol = DeviceIdentity.generate()

        val aliceBobShared = alice.ecdh(bob.x25519Pub)
        val aliceCarolShared = alice.ecdh(carol.x25519Pub)

        assertThat(aliceBobShared).isNotEqualTo(aliceCarolShared)
    }

    @Test
    fun `X25519 ECDH rejects wrong-size peer pubkey`() {
        val alice = DeviceIdentity.generate()
        val badPeer = ByteArray(31)  // too short

        try {
            alice.ecdh(badPeer)
            error("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("32 bytes")
        }
    }
}
