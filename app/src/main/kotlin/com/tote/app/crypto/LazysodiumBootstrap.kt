package com.tote.app.crypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.goterl.lazysodium.interfaces.AEAD
import com.goterl.lazysodium.interfaces.DiffieHellman
import com.goterl.lazysodium.interfaces.Sign
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Singleton holder for the lazysodium instance.
 *
 * `LazySodiumJava` works on both the JVM (unit tests, JNA extracts the libsodium
 * shared lib from the jar at runtime) and Android (we add the
 * `lazysodium-android` artifact so the .so files are bundled per-ABI).
 *
 * On Android, [SodiumJava] looks for the system-native libsodium first, then
 * falls back to the bundled one. On the JVM, it always uses the bundled one.
 *
 * Per build-learnings iron rule #15: we DO NOT claim crypto works until the
 * first JVM unit test passes a round-trip (sign → verify, encrypt → decrypt).
 */
object LazysodiumBootstrap {
    private val LOG = Logger.getLogger("LazysodiumBootstrap")

    @Volatile
    private var instance: LazySodiumJava? = null

    /**
     * Get the singleton [LazySodiumJava] instance.
     * Thread-safe via double-checked locking.
     */
    fun get(): LazySodiumJava {
        return instance ?: synchronized(this) {
            instance ?: LazySodiumJava(SodiumJava()).also {
                instance = it
                try {
                    val v = it.sodiumVersion()
                    LOG.info("libsodium loaded: $v")
                } catch (e: Throwable) {
                    LOG.log(Level.SEVERE, "libsodium load FAILED: ${e.message}", e)
                    throw e
                }
            }
        }
    }

    /**
     * Constants pulled from the lazysodium interfaces so we don't sprinkle
     * magic numbers around the codebase.
     *
     * NB: NOT marked `const val` because lazysodium interface constants are
     * `static final int` — accessible at compile time from Java but only
     * `val` from Kotlin (not `const val`). The values are still inlined by
     * the JVM at class load.
     */
    object Const {
        val ED25519_PUBLICKEYBYTES: Int = Sign.ED25519_PUBLICKEYBYTES  // 32
        val ED25519_SECRETKEYBYTES: Int = Sign.ED25519_SECRETKEYBYTES  // 64
        val ED25519_BYTES: Int = Sign.ED25519_BYTES                    // 64 (signature)

        val X25519_BYTES: Int = DiffieHellman.SCALARMULT_BYTES         // 32
        val X25519_SCALARBYTES: Int = DiffieHellman.SCALARMULT_SCALARBYTES  // 32

        val XCHACHA20POLY1305_IETF_KEYBYTES: Int = AEAD.XCHACHA20POLY1305_IETF_KEYBYTES  // 32
        val XCHACHA20POLY1305_IETF_NPUBBYTES: Int = AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES  // 24
        val XCHACHA20POLY1305_IETF_ABYTES: Int = AEAD.XCHACHA20POLY1305_IETF_ABYTES  // 16
    }
}
