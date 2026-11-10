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

/**
 * BIP-39 wordlist (first 256 words for the 12-word recovery code).
 * In production, load the full 2048-word list from a bundled resource file.
 */
object BIP39 {
    // Abbreviated list — production would have all 2048 words
    val words = listOf(
        "abandon","ability","able","about","above","absent","absorb","abstract",
        "absurd","abuse","access","accident","account","accuse","achieve","acid",
        "acoustic","acquire","across","act","action","actor","actress","actual",
        "adapt","add","addict","address","adjust","admit","adult","advance",
        "advice","aerobic","affair","afford","afraid","again","age","agent",
        "agree","ahead","aim","air","airport","aisle","alarm","album",
        "alcohol","alert","alien","all","alley","allow","almost","alone",
        "alpha","already","also","alter","always","amateur","amazing","among",
        "amount","amused","analyst","anchor","ancient","anger","angle","angry",
        "animal","ankle","announce","annual","another","answer","antenna","antique",
        "anxiety","any","apart","apology","appear","apple","approve","april",
        "arch","arctic","area","arena","argue","arm","armed","armor",
        "army","around","arrange","arrest","arrive","arrow","art","artefact",
        "artist","artwork","ask","aspect","assault","asset","assist","assume",
        "asthma","athlete","atom","attack","attend","attitude","attract","auction",
        "audit","august","aunt","author","auto","autumn","average","avocado",
        "avoid","awake","aware","away","awesome","awful","awkward","axis",
        "baby","bachelor","bacon","badge","bag","balance","balcony","ball",
        "bamboo","banana","banner","bar","barely","bargain","barrel","base",
        "basic","basket","battle","beach","bean","beauty","because","become",
        "beef","before","begin","behave","behind","believe","below","belt",
        "bench","benefit","best","betray","better","between","beyond","bicycle",
        "bid","bike","bind","biology","bird","birth","bitter","black",
        "blade","blame","blanket","blast","bleak","bless","blind","blood",
        "blossom","blouse","blue","blur","blush","board","boat","body",
        "boil","bomb","bone","bonus","book","boost","border","boring",
        "borrow","boss","bottom","bounce","box","boy","bracket","brain",
        "brand","brass","brave","bread","breeze","brick","bridge","brief",
        "bright","bring","brisk","broccoli","broken","bronze","broom","brother",
        "brown","brush","bubble","buddy","budget","buffalo","build","bulb",
        "bulk","bullet","bundle","bunker","burden","burger","burst","bus",
        "business","busy","butter","buyer","buzz","cabbage","cabin","cable"
    )

    fun entropyToWords(entropy: ByteArray, wordCount: Int = 12): List<String> {
        val result = mutableListOf<String>()
        for (i in entropy.indices) {
            if (result.size >= wordCount) break
            val idx = (entropy[i].toInt() and 0xFF) % words.size
            result.add(words[idx])
        }
        // Pad if needed
        while (result.size < wordCount) {
            result.add(words[0])  // "abandon"
        }
        return result
    }

    fun wordsToEntropy(words: List<String>): ByteArray {
        val result = ByteArray(words.size)
        for (i in words.indices) {
            result[i] = words.indexOf(words[i]).toByte()
        }
        return result
    }
}
