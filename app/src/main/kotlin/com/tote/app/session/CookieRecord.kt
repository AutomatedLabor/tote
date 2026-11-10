package com.tote.app.session

import kotlinx.serialization.Serializable

enum class SameSite { STRICT, LAX, NONE, UNSPECIFIED }
enum class CookiePriority { LOW, MEDIUM, HIGH, UNSPECIFIED }
enum class SourceScheme { SECURE, INSECURE, UNSPECIFIED }
enum class CookieFidelity { FULL, VALUE_ONLY_ATTRS_LOST, IN_MEMORY_ONLY }
enum class CookieProvenance { SQLITE_SNAPSHOT, GET_COOKIE_DIFF, USER_VERB }

@Serializable
data class CookieRecord(
    val name: String,
    val value: String,
    val host: String,
    val path: String = "/",
    val expiresUtc: Long? = null,
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
    val provenance: CookieProvenance = CookieProvenance.SQLITE_SNAPSHOT,
) {
    companion object {
        fun fromChromiumRow(row: Map<String, Any?>): CookieRecord {
            val value = row["value"] as? String ?: ""
            val encryptedValue = row["encrypted_value"] as? ByteArray
            val fidelity = when {
                value.isNotEmpty() -> CookieFidelity.FULL
                encryptedValue != null && encryptedValue.isNotEmpty() -> CookieFidelity.VALUE_ONLY_ATTRS_LOST
                else -> CookieFidelity.IN_MEMORY_ONLY
            }
            return CookieRecord(
                name = row["name"] as? String ?: "",
                value = value.ifEmpty { "" }, // OSCrypt fallback handled by caller via getCookie()
                host = row["host_key"] as? String ?: "",
                path = row["path"] as? String ?: "/",
                expiresUtc = (row["expires_utc"] as? Number)?.toLong(),
                isSecure = (row["is_secure"] as? Number)?.toInt() == 1,
                isHttpOnly = (row["is_httponly"] as? Number)?.toInt() == 1,
                sameSite = SameSite.entries.getOrNull((row["samesite"] as? Number)?.toInt() ?: 3) ?: SameSite.UNSPECIFIED,
                isPartitioned = (row["is_partitioned"] as? Number)?.toInt() == 1,
                partitionKey = row["partition_key"] as? String,
                fidelity = fidelity,
                provenance = CookieProvenance.SQLITE_SNAPSHOT,
            )
        }
    }
}
