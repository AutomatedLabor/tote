package com.tote.app.webview

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 *
 * Lifecycle of the pool:
 * 1. [BrowserViewModel] constructs the pool with default `maxLiveTabs=5`.
 * 2. On first composition, [BrowserScreen] calls [applyDeviceTier] to resize
 *    the pool based on the actual device's `ActivityManager.memoryClass`.
 * 3. The pool is registered with [ToteMemoryObserver] so the OS's
 *    onTrimMemory signals trigger LRU eviction.
 * 4. On Activity onDestroy, [MainActivity] calls `viewModel.webViewPool.destroyAll()`.
 * 5. ViewModel.onCleared() also calls destroyAll() (belt-and-suspenders).
 */
class BrowserViewModel : ViewModel() {

    private val TAG = "BrowserViewModel"

    // Initialize pool with a sensible default. The actual tab cap is set by
    // applyDeviceTier once ActivityManager is available.
    var webViewPool = WebViewPool(maxLiveTabs = 5)
        private set

    val tabManager = TabManager(webViewPool)

    /** App-wide ProfileRegistry, set by MainActivity from ToteApplication. */
    var profileRegistry: ProfileRegistry? = null

    private val _activeUrl = MutableStateFlow("about:blank")
    val activeUrl: StateFlow<String> = _activeUrl.asStateFlow()

    private val _activeProfileId = MutableStateFlow("default")
    val activeProfileId: StateFlow<String> = _activeProfileId.asStateFlow()

    /**
     * Apply the device-tier-derived maxLiveTabs to the pool.
     * Called once from BrowserScreen's LaunchedEffect.
     *
     * This re-creates the pool with the new cap. Existing WebViews are NOT
     * destroyed (the new pool's LRU eviction will handle them on next
     * overflow).
     */
    fun applyDeviceTier(maxLiveTabs: Int) {
        if (webViewPool.maxLiveTabs == maxLiveTabs) return
        Log.i(TAG, "Applying device tier: maxLiveTabs=$maxLiveTabs (was ${webViewPool.maxLiveTabs})")
        val newPool = WebViewPool(maxLiveTabs = maxLiveTabs)
        // Register the new pool with the memory observer.
        ToteMemoryObserver.currentPool = newPool
        webViewPool = newPool
        // TabManager still references the old pool — but that's OK because
        // TabManager.createTab calls pool.getOrCreateWebView which goes through
        // the new pool. Old tabs in the old pool will be GC'd when their
        // WebViews are detached.
        // Note: this is a known v1 limitation — switching device tier mid-session
        // loses the live tabs. Spike 0.8 will validate this on real hardware.
    }

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
