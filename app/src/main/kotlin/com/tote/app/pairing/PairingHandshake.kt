package com.tote.app.pairing

import com.tote.app.crypto.DeviceIdentity
import com.tote.app.crypto.HKDF
import com.tote.app.crypto.SHA256
import java.security.SecureRandom

/**
 * QR-payload exchanged between devices during pairing.
 *
 * Encoded as a Base64 string in the QR code, prefixed with `tote://pair/`.
 *
 * Fields:
 * - `relayUrl` — where the receiver should connect for envelope exchange
 * - `senderDeviceId` — sender's Ed25519 pubkey (32 bytes)
 * - `senderX25519Pub` — sender's X25519 pubkey (32 bytes, derived from same seed)
 * - `nonceA` — sender's random nonce (32 bytes)
 * - `saltHint` — bind to relay identity for SAS (per CRYPTO-SPEC-FINAL §2)
 *
 * The receiver decodes this, generates its own `nonceB`, performs X25519 ECDH
 * with the sender's X25519 pub, then derives `pairingRoot = HKDF(shared, salt=nonceA||nonceB||relayUrl, info="pairing-root-v1")`.
 */
data class PairingPayload(
    val relayUrl: String,
    val senderDeviceId: ByteArray,
    val senderX25519Pub: ByteArray,
    val nonceA: ByteArray,
    val saltHint: ByteArray,  // 16 bytes — relay identity binding
) {
    /**
     * Encode as a `tote://pair/<base64>` URL for the QR code.
     */
    fun toQrString(): String {
        val bytes = encode()
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        return "tote://pair/$b64"
    }

    /**
     * Pack into bytes for QR encoding.
     *
     * Layout:
     *   relayUrlLen(2) + relayUrl + senderDeviceId(32) + senderX25519Pub(32) +
     *   nonceA(32) + saltHint(16)
     */
    fun encode(): ByteArray {
        val urlBytes = relayUrl.toByteArray(Charsets.US_ASCII)
        require(urlBytes.size <= 0xFFFF) { "relayUrl too long" }
        val out = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(out)
        dos.writeShort(urlBytes.size)
        dos.write(urlBytes)
        dos.write(senderDeviceId.copyOf(32))
        dos.write(senderX25519Pub.copyOf(32))
        dos.write(nonceA.copyOf(32))
        dos.write(saltHint.copyOf(16))
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean = other is PairingPayload && relayUrl == other.relayUrl
    override fun hashCode(): Int = relayUrl.hashCode()

    companion object {
        /**
         * Decode a `tote://pair/<base64>` URL back into a [PairingPayload].
         */
        fun fromQrString(qr: String): PairingPayload {
            val prefix = "tote://pair/"
            require(qr.startsWith(prefix)) { "Not a Tote pairing QR" }
            val b64 = qr.removePrefix(prefix)
            val bytes = android.util.Base64.decode(b64, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
            return decode(bytes)
        }

        /**
         * Unpack from bytes (inverse of [encode]).
         */
        fun decode(bytes: ByteArray): PairingPayload {
            val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
            val urlLen = dis.readUnsignedShort()
            val urlBytes = ByteArray(urlLen).also { dis.readFully(it) }
            val relayUrl = String(urlBytes, Charsets.US_ASCII)
            val senderDeviceId = ByteArray(32).also { dis.readFully(it) }
            val senderX25519Pub = ByteArray(32).also { dis.readFully(it) }
            val nonceA = ByteArray(32).also { dis.readFully(it) }
            val saltHint = ByteArray(16).also { dis.readFully(it) }
            return PairingPayload(relayUrl, senderDeviceId, senderX25519Pub, nonceA, saltHint)
        }
    }
}

/**
 * The pairing handshake, executed on both devices after QR exchange.
 *
 * Steps (per CRYPTO-SPEC-FINAL §3):
 * 1. Sender generates DeviceIdentity, nonceA. Displays QR.
 * 2. Receiver scans QR → decodes PairingPayload → generates nonceB.
 * 3. Both devices perform X25519 ECDH:
 *    - Sender: shared = senderIdentity.ecdh(receiverX25519Pub)
 *    - Receiver: shared = receiverIdentity.ecdh(senderX25519Pub)
 *    - Both must agree on `shared`.
 * 4. Derive pairingRoot = HKDF(shared, salt=nonceA||nonceB||relayUrl||relayPub, info="pairing-root-v1", 32)
 * 5. Compute SAS = first 4 bytes of HKDF(pairingRoot, info="sas-v1") → 6-digit + 3 emojis.
 * 6. Both devices display the SAS. User confirms match.
 * 7. If match: bootstrap EpochState(pairingRoot, identity). Done.
 */
class PairingHandshake(
    private val localIdentity: DeviceIdentity,
    private val payload: PairingPayload,
) {
    /** Receiver's nonce (generated when receiving the QR). */
    private val nonceB: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /**
     * Compute the shared ECDH secret given the peer's X25519 pubkey.
     *
     * Called by BOTH devices:
     * - Sender: peerX25519Pub = receiver's X25519 pub (sent over relay)
     * - Receiver: peerX25519Pub = payload.senderX25519Pub (from QR)
     */
    fun computeSharedSecret(peerX25519Pub: ByteArray): ByteArray {
        return localIdentity.ecdh(peerX25519Pub)
    }

    /**
     * Derive the pairingRoot from the ECDH shared secret + both nonces + relay URL.
     *
     * This is the master secret for the pairing — never sent over the wire.
     */
    fun derivePairingRoot(sharedSecret: ByteArray, otherNonce: ByteArray): ByteArray {
        // Salt: nonceA || otherNonce || relayUrl || saltHint (sorted by role? per spec)
        // For simplicity we use sorted nonces so both devices derive the same salt.
        val (smallerNonce, largerNonce) = if (compareByteArrays(payload.nonceA, otherNonce) <= 0) {
            payload.nonceA to otherNonce
        } else {
            otherNonce to payload.nonceA
        }
        val salt = smallerNonce + largerNonce +
            payload.relayUrl.toByteArray(Charsets.US_ASCII) +
            payload.saltHint
        return HKDF.derive(
            ikm = sharedSecret,
            salt = salt,
            info = "pairing-root-v1".toByteArray(Charsets.US_ASCII),
            length = 32,
        )
    }

    /**
     * Compute the 6-digit Short Authentication String.
     *
     * Per CRYPTO-SPEC-FINAL §3: SAS = HKDF(pairingRoot, info="sas-v1")[0..4]
     * interpreted as a big-endian uint32, mod 1_000_000, formatted as 6 digits.
     */
    fun computeSas(pairingRoot: ByteArray): String {
        val sasBytes = HKDF.derive(
            ikm = pairingRoot,
            salt = ByteArray(0),
            info = "sas-v1".toByteArray(Charsets.US_ASCII),
            length = 4,
        )
        val sasInt = ((sasBytes[0].toLong() and 0xFF) shl 24) or
                     ((sasBytes[1].toLong() and 0xFF) shl 16) or
                     ((sasBytes[2].toLong() and 0xFF) shl 8) or
                     (sasBytes[3].toLong() and 0xFF)
        val sas6 = (sasInt % 1_000_000L).toInt()
        return "%06d".format(sas6)
    }

    /**
     * Compute the 3-emoji SAS for MITM detection.
     *
     * Picks 3 emojis from a fixed list of 64, indexed by 6-bit chunks of
     * HKDF(pairingRoot, info="sas-emoji-v1")[0..3] (24 bits = 4 * 6 bits, take 3).
     */
    fun computeSasEmoji(pairingRoot: ByteArray): List<String> {
        val emojiBytes = HKDF.derive(
            ikm = pairingRoot,
            salt = ByteArray(0),
            info = "sas-emoji-v1".toByteArray(Charsets.US_ASCII),
            length = 3,
        )
        val emojis = SAS_EMOJIS
        return emojiBytes.map { b -> emojis[(b.toInt() and 0xFF) % emojis.size] }
    }

    private fun compareByteArrays(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val cmp = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (cmp != 0) return cmp
        }
        return a.size - b.size
    }

    companion object {
        // 64 distinct emojis for the emoji-SAS. Per CRYPTO-SPEC-FINAL §3 the
        // list is fixed and canonical — any change here breaks cross-device
        // verification.
        val SAS_EMOJIS = listOf(
            "🦊", "🐙", "🦄", "🐉",
            "🦋", "🐢", "🦉", "🐝",
            "🐳", "🦈", "🦓", "🦒",
            "🐘", "🦏", "🐪", "🦥",
            "🌵", "🌴", "🌻", "🍁",
            "🌍", "🌙", "⭐", "☀️",
            "⚡", "🔥", "❄️", "🌈",
            "🍎", "🍊", "🍌", "🍇",
            "🍓", "🍑", "🍒", "🥝",
            "🍕", "🍔", "🌮", "🍜",
            "🎲", "🎯", "🎸", "🎺",
            "🎈", "🎁", "🏆", "💎",
            "🔑", "🔒", "📌", "📎",
            "💡", "📖", "✏️", "🔨",
            "🚀", "🛸", "⚓", "🚲",
            "☁️", "🌊", "🌋", "🌳",
        )
    }
}
