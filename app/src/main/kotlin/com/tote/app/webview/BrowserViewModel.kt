package com.tote.app.webview

import android.app.Application
import android.util.Log
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.webkit.WebViewCompat
import com.tote.app.profile.ProfileRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hosts the WebViewPool and survives configuration changes (per ADR-017).
 * Does NOT survive process death — only a foreground Service does that,
 * and we're NOT using one for v1.
 *
 * IMPORTANT: BrowserActivity.onDestroy() MUST call webViewPool.destroyAll()
 * as belt-and-suspenders (per Chief Mobile Architect condition #2).
 * ViewModel.onCleared() is called asynchronously after onDestroy and
 * is unreliable on some OEM ROMs (Xiaomi MIUI, Huawei EMUI).
 */
class BrowserViewModel : ViewModel() {

    private val TAG = "BrowserViewModel"
    val webViewPool = WebViewPool()
    val tabManager = TabManager(webViewPool)
    val profileRegistry: ProfileRegistry? = null  // Set by ToteApplication

    private val _activeUrl = MutableStateFlow("about:blank")
    val activeUrl: StateFlow<String> = _activeUrl.asStateFlow()

    private val _activeProfileId = MutableStateFlow("default")
    val activeProfileId: StateFlow<String> = _activeProfileId.asStateFlow()

    /**
     * Load a URL in the active tab.
     */
    fun loadUrl(url: String) {
        val normalized = normalizeUrl(url)
        _activeUrl.value = normalized
        tabManager.loadUrl(normalized)
        Log.i(TAG, "Loading: $normalized (profile: ${_activeProfileId.value})")
    }

    /**
     * Switch to a different profile.
     * Per ADR-012: profile-switch = destroy+recreate+reload (NOT instant).
     */
    fun switchProfile(profileId: String) {
        _activeProfileId.value = profileId
        tabManager.switchProfile(profileId)
        Log.i(TAG, "Switched to profile: $profileId")
    }

    /**
     * Go back in the active tab's history.
     */
    fun goBack() {
        tabManager.goBack()
    }

    /**
     * Go forward in the active tab's history.
     */
    fun goForward() {
        tabManager.goForward()
    }

    /**
     * Switch to the next tab (for swipe gesture).
     */
    fun nextTab() {
        tabManager.nextTab()
    }

    /**
     * Switch to the previous tab (for swipe gesture).
     */
    fun previousTab() {
        tabManager.previousTab()
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.isEmpty() -> "about:blank"
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("about:") -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> "https://www.google.com/search?q=${trimmed.replace(" ", "+")}"
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Belt-and-suspenders: BrowserActivity.onDestroy also calls destroyAll()
        webViewPool.destroyAll()
        Log.i(TAG, "ViewModel cleared — WebViewPool destroyed")
    }
}
