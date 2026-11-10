package com.tote.app.crypto

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 implementation (RFC 5869).
 * Used for all key derivation in Tote: pairing root, epoch keys, backup keys, recovery.
 */
object HKDF {
    private const val HASH_LEN = 32

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val actualSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(actualSalt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val blocks = mutableListOf<ByteArray>()
        var t = ByteArray(0)
        var i = 1
        while (blocks.size * HASH_LEN < length) {
            mac.reset()
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            t = mac.doFinal(t + info + byteArrayOf(i.toByte()))
            blocks.add(t)
            i++
        }
        return blocks.flatMap { it.toList() }.take(length).toByteArray()
    }

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        return expand(extract(salt, ikm), info, length)
    }
}

/**
 * SHA-256 helper.
 */
object SHA256 {
    fun hash(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    fun hashHex(data: ByteArray): String = hash(data).joinToString("") { "%02x".format(it) }
}

/**
 * HMAC-SHA256 helper.
 */
object HMAC {
    fun mac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}


