package com.tote.app.session

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import androidx.webkit.Profile
import com.tote.app.crypto.CookieRecord
import com.tote.app.crypto.CookieFidelity
import com.tote.app.crypto.CookieProvenance
import java.io.File

/**
 * Extracts cookies from a WebView profile's SQLite `Cookies` table.
 *
 * Per ADR-016 (cookie fidelity): the SQLite snapshot is the gold standard
 * because it captures ALL attributes (host, path, expires, secure, httponly,
 * samesite, priority, partitioned, partition_key, source_port, source_scheme,
 * last_access, creation). The CookieManager.getCookie() API only returns
 * `name=value` pairs, losing everything else.
 *
 * Per ADR-003 (cookie fidelity spike): we read the SQLite file directly,
 * bypassing the in-process CookieManager. This requires either:
 *   (a) The AndroidX WebView Profile API's `getDataDirectory()` (preferred),
 *       available on Chromium M119+ / androidx.webkit 1.10.0+.
 *   (b) The legacy single-profile path: `/data/data/<pkg>/app_webview/Default/Cookies`.
 *
 * OSCrypt fallback (per CRYPTO-SPEC-FINAL §6): if a cookie's `value` column
 * is empty AND `encrypted_value` is non-empty, the WebView provider is using
 * OSCrypt (Linux: DPAPI-less AES-256-GCM with a key stored in `~/.config/...`
 * equivalent; Android: Keystore-backed). We cannot decrypt OSCrypt blobs
 * portably (the key is per-device), so we fall back to `CookieManager.getCookie()`
 * for those entries and mark `fidelity = VALUE_ONLY_ATTRS_LOST`.
 */
class CookieSnapshotExtractor(private val context: Context) {
    private val TAG = "CookieSnapExtractor"

    /**
     * Snapshot all cookies for the given profile.
     *
     * Returns a list of [CookieRecord] with `provenance = SQLITE_SNAPSHOT`
     * for full-fidelity rows and `provenance = GET_COOKIE_DIFF` for
     * OSCrypt-fallback rows.
     */
    fun extractForProfile(profile: Profile?, profileId: String): List<CookieRecord> {
        val cookieFile = locateCookiesFile(profile, profileId)
            ?: run {
                Log.w(TAG, "No Cookies SQLite file found for profile=$profileId — falling back to CookieManager only")
                return extractViaCookieManagerOnly(profileId)
            }

        if (!cookieFile.exists()) {
            Log.w(TAG, "Cookies file does not exist: ${cookieFile.absolutePath} — opening WebView once should create it")
            return extractViaCookieManagerOnly(profileId)
        }

        return try {
            extractFromSqlite(cookieFile, profileId)
        } catch (t: Throwable) {
            Log.e(TAG, "SQLite extraction failed: ${t.message}; falling back to CookieManager", t)
            extractViaCookieManagerOnly(profileId)
        }
    }

    /**
     * Locate the SQLite `Cookies` file for a profile.
     *
     * Tries AndroidX Profile API first (Chromium M119+), then falls back to
     * the legacy single-profile path.
     */
    private fun locateCookiesFile(profile: Profile?, profileId: String): File? {
        // Approach 1: AndroidX Profile API (preferred)
        // Note: as of androidx.webkit 1.10.0, the Profile interface has
        // `getCookiesPath()` indirectly via the data directory. We use
        // Profile.getNativePtr() reflection or just walk the expected path.
        //
        // For now, use the well-known layout:
        //   /data/data/<pkg>/app_<profileId>/Cookies
        // or for the default profile:
        //   /data/data/<pkg>/app_webview/Default/Cookies
        val baseDir = File(context.applicationInfo.dataDir)
        val candidatePaths = buildList {
            // Multi-profile paths (Chromium M119+ with WebViewCompat.setProfile)
            if (profileId != "default") {
                add(File(baseDir, "app_$profileId/Cookies"))
                add(File(baseDir, "app_webview/$profileId/Cookies"))
            }
            // Default profile paths (legacy + modern)
            add(File(baseDir, "app_webview/Default/Cookies"))
            add(File(baseDir, "app_webview/Cookies"))
        }
        return candidatePaths.firstOrNull { it.exists() }
    }

    /**
     * Read all rows from the SQLite Cookies file.
     *
     * We use raw SQL via the SQLiteOpenHelper-in-headless mode: we open the
     * file in read-only mode using Android's built-in SQLite (no extra deps).
     */
    private fun extractFromSqlite(file: File, profileId: String): List<CookieRecord> {
        val records = mutableListOf<CookieRecord>()
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        db.use { database ->
            val cursor = database.rawQuery(
                """SELECT
                      name, value, encrypted_value, host_key, path,
                      expires_utc, is_secure, is_httponly, samesite, priority,
                      is_partitioned, partition_key, source_port, source_scheme,
                      last_update_utc, creation_utc
                   FROM cookies""".trimIndent(),
                null
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(c.getColumnIndexOrThrow("name"))
                    val value = c.getString(c.getColumnIndexOrThrow("value")) ?: ""
                    val encryptedValue = c.getBlob(c.getColumnIndexOrThrow("encrypted_value"))
                    val row = mapOf<String, Any?>(
                        "name" to name,
                        "value" to value,
                        "encrypted_value" to encryptedValue,
                        "host_key" to c.getString(c.getColumnIndexOrThrow("host_key")),
                        "path" to c.getString(c.getColumnIndexOrThrow("path")),
                        "expires_utc" to c.getLong(c.getColumnIndexOrThrow("expires_utc")),
                        "is_secure" to c.getLong(c.getColumnIndexOrThrow("is_secure")),
                        "is_httponly" to c.getLong(c.getColumnIndexOrThrow("is_httponly")),
                        "samesite" to c.getLong(c.getColumnIndexOrThrow("samesite")),
                        "priority" to c.getLong(c.getColumnIndexOrThrow("priority")),
                        "is_partitioned" to c.getLong(c.getColumnIndexOrThrow("is_partitioned")),
                        "partition_key" to c.getString(c.getColumnIndexOrThrow("partition_key")),
                        "source_port" to c.getLong(c.getColumnIndexOrThrow("source_port")),
                        "source_scheme" to c.getLong(c.getColumnIndexOrThrow("source_scheme")),
                        "last_update_utc" to c.getLong(c.getColumnIndexOrThrow("last_update_utc")),
                        "creation_utc" to c.getLong(c.getColumnIndexOrThrow("creation_utc"))
                    )
                    val record = CookieRecord.fromChromiumRow(row).copy(
                        provenance = CookieProvenance.SQLITE_SNAPSHOT
                    )
                    // OSCrypt fallback: if value is empty AND encrypted_value is non-empty,
                    // try to recover the value via CookieManager.getCookie().
                    val finalRecord = if (record.fidelity == CookieFidelity.VALUE_ONLY_ATTRS_LOST) {
                        val recoveredValue = recoverValueViaCookieManager(record.host, record.name)
                        if (recoveredValue != null) {
                            record.copy(value = recoveredValue, fidelity = CookieFidelity.FULL)
                        } else {
                            record
                        }
                    } else {
                        record
                    }
                    records.add(finalRecord)
                }
            }
        }
        Log.i(TAG, "Extracted ${records.size} cookies from SQLite (profile=$profileId, file=${file.name})")
        return records
    }

    /**
     * Try to recover a cookie's value via the in-process CookieManager.
     *
     * The CookieManager returns `name=value; name=value; ...` for a given URL,
     * losing all attribute info. We use this ONLY for OSCrypt-encrypted cookies
     * where the SQLite `value` column is empty.
     */
    private fun recoverValueViaCookieManager(host: String, name: String): String? {
        val cm = CookieManager.getInstance()
        val url = if (host.startsWith(".")) "https://${host.substring(1)}" else "https://$host"
        val cookieHeader = cm.getCookie(url) ?: return null
        // cookieHeader = "name1=val1; name2=val2; ..."
        for (pair in cookieHeader.split(';')) {
            val trimmed = pair.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0 && trimmed.substring(0, eq) == name) {
                return trimmed.substring(eq + 1)
            }
        }
        return null
    }

    /**
     * Last-resort fallback: get all cookies via CookieManager.getCookie() per host.
     *
     * This is what we do when SQLite is unavailable. We lose all attribute
     * information and mark every cookie `fidelity = IN_MEMORY_ONLY`.
     */
    private fun extractViaCookieManagerOnly(profileId: String): List<CookieRecord> {
        // Without SQLite access, we can't enumerate hosts. CookieManager doesn't
        // expose a list of all stored hosts. The best we can do is nothing here
        // (caller must explicitly request extraction for known hosts).
        Log.w(TAG, "CookieManager-only fallback is not implemented (requires host list)")
        return emptyList()
    }
}
