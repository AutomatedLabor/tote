package com.tote.app.webview

import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import com.tote.app.profile.ProfileRegistry

/**
 * Manages tab lifecycle: creation, switching (no-reload intra-profile),
 * profile-switch (destroy+recreate+reload per ADR-012).
 *
 * Tab-switch (intra-profile): instant, WebView retained in pool.
 * Profile-switch (cross-profile): ~600ms-3s, destroy+recreate+reload.
 */
class TabManager(private val pool: WebViewPool) {
    private val TAG = "TabManager"

    data class Tab(
        val id: String,
        val profileId: String,
        var url: String = "about:blank",
        var title: String = "New Tab",
        var isActive: Boolean = false
    )

    private val tabs = mutableListOf<Tab>()
    private var activeTabId: String? = null
    private var activeProfileId: String = "default"

    init {
        // Create the first tab
        createTab("default", "https://www.google.com")
    }

    fun createTab(profileId: String, url: String = "about:blank"): String {
        val tabId = java.util.UUID.randomUUID().toString()
        val tab = Tab(id = tabId, profileId = profileId, url = url, isActive = true)
        tabs.forEach { it.isActive = false }
        tabs.add(tab)
        activeTabId = tabId
        activeProfileId = profileId

        // Detach the previous tab's WebView (keep it alive in pool)
        if (tabs.size > 1) {
            pool.detachWebView(tabs[tabs.size - 2].id)
        }

        // Create the new tab's WebView
        pool.getOrCreateWebView(tabId, profileId)?.let { wv ->
            if (url != "about:blank") {
                wv.loadUrl(url)
            }
        }

        Log.i(TAG, "Created tab=$tabId profile=$profileId (total tabs: ${tabs.size})")
        return tabId
    }

    fun switchTab(tabId: String) {
        val tab = tabs.find { it.id == tabId } ?: return
        if (tab.profileId != activeProfileId) {
            switchProfile(tab.profileId)
        }
        // Detach current
        activeTabId?.let { pool.detachWebView(it) }
        // Attach target (or recreate if evicted)
        val wv = pool.getOrCreateWebView(tabId, tab.profileId)
        if (pool.isLive(tabId) == false && wv != null) {
            // Tab was evicted — reload its last URL
            wv.loadUrl(tab.url)
        }
        tabs.forEach { it.isActive = it.id == tabId }
        activeTabId = tabId
        Log.i(TAG, "Switched to tab=$tabId (instant, no reload)")
    }

    fun nextTab() {
        val idx = tabs.indexOfFirst { it.id == activeTabId }
        if (idx >= 0 && idx < tabs.size - 1) {
            switchTab(tabs[idx + 1].id)
        }
    }

    fun previousTab() {
        val idx = tabs.indexOfFirst { it.id == activeTabId }
        if (idx > 0) {
            switchTab(tabs[idx - 1].id)
        }
    }

    fun closeTab(tabId: String) {
        val idx = tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return
        pool.detachWebView(tabId)
        tabs.removeAt(idx)
        if (activeTabId == tabId) {
            activeTabId = tabs.getOrNull(0)?.id
            activeTabId?.let { switchTab(it) }
        }
        Log.i(TAG, "Closed tab=$tabId (remaining: ${tabs.size})")
    }

    fun loadUrl(url: String) {
        val tabId = activeTabId ?: return
        val tab = tabs.find { it.id == tabId } ?: return
        tab.url = url
        pool.getOrCreateWebView(tabId, tab.profileId)?.loadUrl(url)
    }

    fun goBack() {
        val tabId = activeTabId ?: return
        val tab = tabs.find { it.id == tabId } ?: return
        pool.getOrCreateWebView(tabId, tab.profileId)?.goBack()
    }

    fun goForward() {
        val tabId = activeTabId ?: return
        val tab = tabs.find { it.id == tabId } ?: return
        pool.getOrCreateWebView(tabId, tab.profileId)?.goForward()
    }

    /**
     * Profile-switch: destroy all current profile's tabs, recreate for new profile.
     * Per ADR-012: this ALWAYS implies a page reload (~600ms-3s).
     */
    fun switchProfile(newProfileId: String) {
        if (newProfileId == activeProfileId) return

        // Save active tab URL
        val savedUrl = tabs.find { it.id == activeTabId }?.url ?: "about:blank"

        // Destroy all current profile's WebViews
        pool.destroyProfile(activeProfileId)

        // Remove tabs of old profile
        tabs.removeAll { it.profileId == activeProfileId }

        activeProfileId = newProfileId

        // Create first tab in new profile
        createTab(newProfileId, savedUrl)

        Log.i(TAG, "Profile switched to=$newProfileId (reloaded: $savedUrl)")
    }

    fun getActiveTab(): Tab? = activeTabId?.let { id -> tabs.find { it.id == id } }
    fun getActiveProfileId(): String = activeProfileId
    fun getTabCount(): Int = tabs.size
    fun getAllTabs(): List<Tab> = tabs.toList()
}
