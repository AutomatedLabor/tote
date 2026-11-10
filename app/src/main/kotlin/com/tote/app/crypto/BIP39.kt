package com.tote.app.crypto

import java.security.SecureRandom

/**
 * BIP-39 mnemonic generator.
 *
 * Generates a 12-word recovery phrase from 16 bytes (128 bits) of entropy.
 * Per CRYPTO-SPEC-FINAL §5: this is the user's recovery code, written down
 * offline, used to re-derive the pairingRoot if the device is lost.
 *
 * The BIP-39 spec includes a checksum (first 4 bits of SHA-256(entropy)
 * appended to the entropy before splitting into 11-bit indices). We implement
 * the full spec, not just the wordlist-lookup.
 *
 * The wordlist is the official English BIP-39 wordlist (2048 words).
 * We embed it as a Kotlin object literal to avoid the overhead of loading
 * from a resource file at app startup.
 */
object BIP39 {
    /**
     * The official BIP-39 English wordlist (2048 words).
     *
     * Source: https://github.com/bitcoin/bips/blob/master/bip-0039/english.txt
     * Each word is 3-8 characters; sorted lexicographically per the spec.
     */
    val words: List<String> = BIP39_WORDLIST.toList()

    /**
     * Generate a 12-word mnemonic from random entropy.
     *
     * @param entropySizeBits Must be 128 (12 words), 160 (15 words),
     *   192 (18 words), 224 (21 words), or 256 (24 words). Default 128.
     * @return The mnemonic as a List of words.
     */
    fun generate(entropySizeBits: Int = 128): List<String> {
        require(entropySizeBits % 32 == 0 && entropySizeBits in 128..256) {
            "Entropy size must be 128-256 bits in 32-bit steps"
        }
        val entropy = ByteArray(entropySizeBits / 8)
        SecureRandom().nextBytes(entropy)
        return entropyToMnemonic(entropy)
    }

    /**
     * Convert raw entropy bytes to a BIP-39 mnemonic.
     *
     * Algorithm (per BIP-39):
     * 1. Compute checksum = first (entropySize / 32) bits of SHA-256(entropy)
     * 2. Concatenate (entropy || checksum) — total bits = entropySize * 33 / 32
     * 3. Split into 11-bit groups, each group is a wordlist index
     */
    fun entropyToMnemonic(entropy: ByteArray): List<String> {
        val entropyBits = entropy.size * 8
        require(entropyBits % 32 == 0 && entropyBits in 128..256) {
            "Entropy must be 16-32 bytes (128-256 bits)"
        }
        val checksumBits = entropyBits / 32
        val checksumByte = SHA256.hash(entropy)[0]
        // Total bits = entropyBits + checksumBits, must be multiple of 11
        // (e.g. 128 + 4 = 132 = 12 * 11 ✓; 256 + 8 = 264 = 24 * 11 ✓)

        // Build a bit array of (entropyBits + checksumBits) bits
        val totalBits = entropyBits + checksumBits
        val wordCount = totalBits / 11
        val mnemonic = mutableListOf<String>()

        for (i in 0 until wordCount) {
            // Extract 11 bits starting at bit position i*11
            val bitOffset = i * 11
            var idx = 0
            for (b in 0 until 11) {
                val bitPos = bitOffset + b
                val bit = getBit(entropy, checksumByte, checksumBits, bitPos)
                idx = (idx shl 1) or bit
            }
            mnemonic.add(words[idx])
        }
        return mnemonic
    }

    /**
     * Convert a mnemonic back to entropy bytes.
     *
     * Validates the checksum. Throws [IllegalArgumentException] on bad checksum.
     */
    fun mnemonicToEntropy(mnemonic: List<String>): ByteArray {
        require(mnemonic.size in listOf(12, 15, 18, 21, 24)) {
            "Mnemonic must be 12/15/18/21/24 words (got ${mnemonic.size})"
        }
        // Reconstruct bits
        val totalBits = mnemonic.size * 11
        // Per BIP-39: entropy is 32/33 of total, checksum is 1/33 of total.
        val entropyBits = totalBits * 32 / 33
        val checksumBits = totalBits / 33
        require(entropyBits % 8 == 0) { "Entropy bits not aligned" }
        val entropyBytes = entropyBits / 8

        // Build bit array
        val bits = BooleanArray(totalBits)
        for (i in mnemonic.indices) {
            val word = mnemonic[i]
            val idx = words.indexOf(word)
            require(idx >= 0) { "Unknown word: '$word'" }
            for (b in 0 until 11) {
                bits[i * 11 + b] = ((idx shr (10 - b)) and 1) == 1
            }
        }

        // Extract entropy bytes
        val entropy = ByteArray(entropyBytes)
        for (i in 0 until entropyBits) {
            val byteIdx = i / 8
            val bitIdx = 7 - (i % 8)
            if (bits[i]) {
                entropy[byteIdx] = (entropy[byteIdx].toInt() or (1 shl bitIdx)).toByte()
            }
        }

        // Verify checksum
        val computedChecksumByte = SHA256.hash(entropy)[0]
        val expectedChecksumBits = entropyBits / 32
        for (b in 0 until expectedChecksumBits) {
            val computedBit = (computedChecksumByte.toInt() shr (7 - b)) and 1
            val expectedBit = if (bits[entropyBits + b]) 1 else 0
            require(computedBit == expectedBit) {
                "Checksum mismatch — typo in mnemonic"
            }
        }

        return entropy
    }

    /**
     * Get bit `pos` from the concatenation of entropy bytes and checksum byte.
     */
    private fun getBit(entropy: ByteArray, checksumByte: Byte, checksumBits: Int, pos: Int): Int {
        val entropyBits = entropy.size * 8
        return if (pos < entropyBits) {
            val byteIdx = pos / 8
            val bitIdx = 7 - (pos % 8)
            (entropy[byteIdx].toInt() shr bitIdx) and 1
        } else {
            val checksumPos = pos - entropyBits
            if (checksumPos < checksumBits) {
                val bitIdx = 7 - checksumPos
                (checksumByte.toInt() shr bitIdx) and 1
            } else {
                0
            }
        }
    }
}
