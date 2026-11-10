package com.tote.app.crypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Represents a single HTTP cookie with ALL attributes (per ADR-016).
 *
 * This is the Tote-internal canonical format. Platform adapters convert
 * between this and the platform-specific cookie representations:
 * - Android: Chromium SQLite `cookies` table ↔ CookieRecord
 * - iOS: NSHTTPCookie ↔ CookieRecord (loses partition_key)
 * - Relay wire format: CBOR-encoded List<CookieRecord>
 *
 * Plain Kotlin — no Android-specific types — for future KMP extraction.
 */
@Serializable
data class CookieRecord(
    val name: String,
    val value: String,
    val host: String,
    val path: String = "/",
    val expiresUtc: Long? = null,  // null = session cookie
    val isSecure: Boolean = false,
    val isHttpOnly: Boolean = false,
    val sameSite: SameSite = SameSite.UNSPECIFIED,
    val priority: CookiePriority = CookiePriority.UNSPECIFIED,
    val isPartitioned: Boolean = false,
    val partitionKey: String? = null,
    val sourcePort: Int? = null,
    val sourceScheme: SourceScheme = SourceScheme.UNSPECIFIED,
    val lastAccessUtc: Long? = null,
    val creationUtc: Long? = null,
    val fidelity: CookieFidelity = CookieFidelity.FULL,
    val provenance: CookieProvenance = CookieProvenance.SQLITE_SNAPSHOT
) {
    companion object {
        /**
         * Factory: create from a Chromium SQLite `cookies` table row.
         * The map keys are column names; values are the raw column values.
         */
        fun fromChromiumRow(row: Map<String, Any?>): CookieRecord {
            val name = row["name"] as? String ?: ""
            val value = row["value"] as? String ?: ""
            val encryptedValue = row["encrypted_value"] as? ByteArray

            // If encrypted_value is non-empty and value is empty, we hit OSCrypt
            val hasValue = value.isNotEmpty()
            val hasEncrypted = encryptedValue != null && encryptedValue.isNotEmpty()

            val fidelity = when {
                hasValue -> CookieFidelity.FULL
                hasEncrypted -> CookieFidelity.VALUE_ONLY_ATTRS_LOST
                else -> CookieFidelity.IN_MEMORY_ONLY
            }

            // Chromium stores times as microseconds since 1601-01-01 (Windows epoch).
            // Convert to Unix epoch milliseconds.
            val chromiumEpochOffset = 11644473600L * 1_000_000L  // seconds to microseconds
            fun chromiumToUnix(v: Long?): Long? = v?.let { (it - chromiumEpochOffset) / 1000 }

            return CookieRecord(
                name = name,
                value = value,
                host = (row["host_key"] as? String) ?: "",
                path = (row["path"] as? String) ?: "/",
                expiresUtc = chromiumToUnix(row["expires_utc"] as? Long),
                isSecure = (row["is_secure"] as? Long)?.let { it != 0L } ?: false,
                isHttpOnly = (row["is_httponly"] as? Long)?.let { it != 0L } ?: false,
                sameSite = when ((row["samesite"] as? Long)?.toInt()) {
                    1 -> SameSite.LAX
                    2 -> SameSite.STRICT
                    3 -> SameSite.NONE
                    else -> SameSite.UNSPECIFIED
                },
                priority = when ((row["priority"] as? Long)?.toInt()) {
                    1 -> CookiePriority.LOW
                    2 -> CookiePriority.MEDIUM
                    3 -> CookiePriority.HIGH
                    else -> CookiePriority.UNSPECIFIED
                },
                isPartitioned = (row["is_partitioned"] as? Long)?.let { it != 0L } ?: false,
                partitionKey = row["partition_key"] as? String,
                sourcePort = (row["source_port"] as? Long)?.toInt(),
                lastAccessUtc = chromiumToUnix(row["last_update_utc"] as? Long),
                creationUtc = chromiumToUnix(row["creation_utc"] as? Long),
                fidelity = fidelity,
                provenance = CookieProvenance.SQLITE_SNAPSHOT
            )
        }
    }

    /**
     * Reconstruct a Set-Cookie header string from this record.
     * Used when importing cookies via CookieManager.setCookie().
     */
    fun toSetCookieString(): String {
        val parts = mutableListOf("$name=$value")
        if (host.isNotEmpty()) parts.add("Domain=$host")
        parts.add("Path=$path")
        if (isSecure) parts.add("Secure")
        if (isHttpOnly) parts.add("HttpOnly")
        when (sameSite) {
            SameSite.LAX -> parts.add("SameSite=Lax")
            SameSite.STRICT -> parts.add("SameSite=Strict")
            SameSite.NONE -> parts.add("SameSite=None")
            SameSite.UNSPECIFIED -> {}
        }
        expiresUtc?.let {
            // Convert Unix ms to seconds for Max-Age approximation
            val maxAge = (it - System.currentTimeMillis()) / 1000
            if (maxAge > 0) parts.add("Max-Age=$maxAge")
        }
        if (isPartitioned) parts.add("Partitioned")
        return parts.joinToString("; ")
    }
}

@Serializable
enum class SameSite { STRICT, LAX, NONE, UNSPECIFIED }

@Serializable
enum class CookiePriority { LOW, MEDIUM, HIGH, UNSPECIFIED }

@Serializable
enum class SourceScheme { SECURE, INSECURE, UNSPECIFIED }

@Serializable
enum class CookieFidelity {
    FULL,                 // All attributes preserved (SQLite snapshot, plaintext value)
    VALUE_ONLY_ATTRS_LOST, // Value recovered via getCookie(), attributes lost (OSCrypt)
    IN_MEMORY_ONLY        // Session cookie not in SQLite, recovered via getCookie()
}

@Serializable
enum class CookieProvenance {
    SQLITE_SNAPSHOT,    // From the cookies SQLite table (full fidelity)
    GET_COOKIE_DIFF,    // From onPageFinished cookie diff
    USER_VERB            // From explicit "Save this session" action
}
