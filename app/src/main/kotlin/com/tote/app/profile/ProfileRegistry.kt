package com.tote.app.profile

import android.content.Context
import android.util.Log
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature

/**
 * Manages Tote's multi-profile system (per ADR-011, uncapped for v1).
 *
 * Each profile = an isolated AndroidX WebView Profile with its own:
 * - Cookie jar (SQLite)
 * - Local storage (LevelDB)
 * - IndexedDB
 * - Service worker registrations
 * - HTTP cache
 *
 * Profile names are UUIDs internally; display names are mapped via a Tote-owned
 * SQLite table. This avoids filesystem collisions and unicode-in-path issues.
 *
 * Per ADR-018: requires Chromium M119+ for MULTI_PROFILE support.
 * Falls back to single-profile mode on older WebView providers.
 */
class ProfileRegistry private constructor(private val context: Context) {

    data class ToteProfile(
        val id: String,           // UUID
        val displayName: String,
        val colorHex: String,     // e.g. "#FF5722"
        val glyph: String,        // emoji or icon name
        val isDefault: Boolean = false,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val profiles = mutableMapOf<String, ToteProfile>()
    private var activeProfileId: String? = null
    private var multiProfileSupported: Boolean = false

    companion object {
        private const val TAG = "ProfileRegistry"
        private const val DEFAULT_PROFILE_NAME = "Personal"
        private const val DEFAULT_COLOR = "#2196F3"
        private const val DEFAULT_GLYPH = "🦊"

        @Volatile
        private var instance: ProfileRegistry? = null

        fun getInstance(context: Context): ProfileRegistry {
            return instance ?: synchronized(this) {
                instance ?: ProfileRegistry(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        multiProfileSupported = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        Log.i(TAG, "MULTI_PROFILE supported: $multiProfileSupported")

        // Create or get the default profile
        val defaultId = "default"
        val defaultProfile = ToteProfile(
            id = defaultId,
            displayName = DEFAULT_PROFILE_NAME,
            colorHex = DEFAULT_COLOR,
            glyph = DEFAULT_GLYPH,
            isDefault = true
        )
        profiles[defaultId] = defaultProfile
        activeProfileId = defaultId

        if (multiProfileSupported) {
            // Ensure the default AndroidX Profile exists
            try {
                val profileStore = ProfileStore.getInstance()
                profileStore.getOrCreateProfile(defaultId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create default profile: ${e.message}")
            }
        }
    }

    /**
     * Create a new profile. Returns the profile ID.
     * Per ADR-011: uncapped for v1 (no 32-profile limit).
     */
    fun createProfile(displayName: String, colorHex: String = DEFAULT_COLOR, glyph: String = DEFAULT_GLYPH): String {
        val id = java.util.UUID.randomUUID().toString()

        val profile = ToteProfile(
            id = id,
            displayName = displayName,
            colorHex = colorHex,
            glyph = glyph
        )
        profiles[id] = profile

        if (multiProfileSupported) {
            try {
                val profileStore = ProfileStore.getInstance()
                profileStore.getOrCreateProfile(id)
                Log.i(TAG, "Created profile: $displayName ($id)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AndroidX profile: ${e.message}")
            }
        }

        return id
    }

    /**
     * Get a profile by ID.
     */
    fun getProfile(id: String): ToteProfile? = profiles[id]

    /**
     * Get all profiles.
     */
    fun getAllProfiles(): List<ToteProfile> = profiles.values.toList()

    /**
     * Get the active profile.
     */
    fun getActiveProfile(): ToteProfile? = activeProfileId?.let { profiles[it] }

    /**
     * Set the active profile. Does NOT switch WebViews — that's the TabManager's job.
     */
    fun setActiveProfile(id: String) {
        if (profiles.containsKey(id)) {
            activeProfileId = id
            Log.i(TAG, "Active profile: ${profiles[id]?.displayName}")
        }
    }

    /**
     * Delete a profile. Also deletes its AndroidX Profile and all associated data.
     */
    fun deleteProfile(id: String) {
        if (id == "default") {
            Log.w(TAG, "Cannot delete default profile")
            return
        }
        profiles.remove(id)
        if (activeProfileId == id) {
            activeProfileId = "default"
        }
        if (multiProfileSupported) {
            try {
                val profileStore = ProfileStore.getInstance()
                profileStore.deleteProfile(id)
                Log.i(TAG, "Deleted profile: $id")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete AndroidX profile: ${e.message}")
            }
        }
    }

    /**
     * Get the AndroidX Profile for a given Tote profile ID.
     * Returns null if multi-profile is not supported or profile doesn't exist.
     */
    fun getAndroidXProfile(id: String): Profile? {
        if (!multiProfileSupported) return null
        return try {
            ProfileStore.getInstance().getOrCreateProfile(id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get AndroidX profile: ${e.message}")
            null
        }
    }

    /**
     * Check if multi-profile is supported on this device.
     */
    fun isMultiProfileSupported(): Boolean = multiProfileSupported
}
