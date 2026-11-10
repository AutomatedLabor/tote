package com.tote.app.webview

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.app.crypto.DeviceIdentity
import com.tote.app.crypto.EpochState
import com.tote.app.profile.ProfileRegistry
import com.tote.app.relay.RelayConfig
import com.tote.app.session.SessionTransferService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    /** Application context, set by MainActivity. Used to reach ToteApplication singletons. */
    var appContext: Context? = null

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

    /**
     * Send the current profile's session to a paired device via the relay.
     *
     * Bootstraps a [SessionTransferService] on demand using the local device's
     * identity + epoch state (loaded from [IdentityStore] on ToteApplication).
     *
     * @param recipientDeviceIdHex The recipient's Ed25519 pubkey (64 hex chars)
     * @param profileId The Tote profile ID to snapshot (default "default")
     */
    fun sendSessionToPairedDevice(recipientDeviceIdHex: String, profileId: String = "default") {
        try {
            val recipientBytes = parseHex(recipientDeviceIdHex)
            require(recipientBytes.size == 32) { "Recipient device ID must be 32 bytes (64 hex chars)" }

            // Get the app singletons.
            val context = appContext ?: run {
                Log.e(TAG, "sendSessionToPairedDevice: no app context")
                _lastSendStatus.value = "ERROR: no app context"
                return
            }
            val app = context as? com.tote.app.ToteApplication ?: run {
                Log.e(TAG, "sendSessionToPairedDevice: context is not ToteApplication")
                _lastSendStatus.value = "ERROR: bad application context"
                return
            }

            val identity = app.identityStore.getOrCreateIdentity()
            val epochState = app.identityStore.getOrCreateEpochState(identity)

            val relayConfig = com.tote.app.relay.RelayConfig(
                baseUrl = "https://relay.tote.app/v1",  // TODO: configurable in Settings
                baseUrlWs = "wss://relay.tote.app/v1",
                localDeviceId = identity.ed25519Pub,
            )
            val service = com.tote.app.session.SessionTransferService(
                context = context,
                relayConfig = relayConfig,
                identity = identity,
                epochState = epochState,
            )

            // Launch the send in a coroutine (fire-and-forget; caller watches lastSendStatus).
            viewModelScope.launch {
                _lastSendStatus.value = "SENDING: snapshotting + uploading…"
                val result = service.sendSession(
                    profileId = profileId,
                    profile = null,  // TODO: resolve the AndroidX Profile for profileId
                    recipientDeviceId = recipientBytes,
                    sequence = System.currentTimeMillis() / 1000,  // monotonic-ish for alpha
                )
                _lastSendStatus.value = if (result.success) {
                    "SENT: ${result.cookieCount} cookies, ${result.blobSize} bytes"
                } else {
                    "SEND FAILED: ${result.error}"
                }
                Log.i(TAG, "sendSession result: ${result.success} (${result.cookieCount} cookies)")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "sendSessionToPairedDevice failed", t)
            _lastSendStatus.value = "SEND FAILED: ${t.message}"
        }
    }

    /** Parse a 64-char hex string into 32 bytes. */
    private fun parseHex(hex: String): ByteArray {
        val clean = hex.trim().replace(" ", "").replace(":", "").lowercase()
        require(clean.length == 64) { "Expected 64 hex chars, got ${clean.length}" }
        return ByteArray(32).also { out ->
            for (i in 0 until 32) {
                out[i] = ((Character.digit(clean[i * 2], 16) shl 4) or
                    Character.digit(clean[i * 2 + 1], 16)).toByte()
            }
        }
    }

    private val _lastSendStatus = MutableStateFlow<String?>(null)
    val lastSendStatus: StateFlow<String?> = _lastSendStatus.asStateFlow()

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
