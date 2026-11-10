package com.tote.app.webview

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Bridges Android's [ComponentCallbacks2.onTrimMemory] to the WebViewPool so
 * we evict paused WebViews (in LRU order) before the OS kills the process.
 *
 * Per ADR-014: the pool itself owns the eviction logic. This class just routes
 * the system signal to the pool. There is at most one pool per process (held
 * by BrowserViewModel), so we use a static reference set by BrowserViewModel
 * on first creation.
 *
 * Per ADR-017: we listen on [ProcessLifecycleOwner] (not the Activity) so we
 * still get memory warnings when the app is in the background.
 */
object ToteMemoryObserver {
    private const val TAG = "ToteMemoryObserver"

    @Volatile
    private var installed: Boolean = false

    /**
     * Currently-registered pool. Set by BrowserViewModel when it constructs
     * the pool. May be null at app start before any browser screen is opened.
     */
    @Volatile
    var currentPool: WebViewPool? = null

    /**
     * Install the memory observer. Safe to call multiple times — only the
     * first call actually registers the listener.
     */
    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val app = context.applicationContext
            val observer = object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    Log.i(TAG, "Process stopped — evicting LRU tabs to free memory")
                    currentPool?.onAppBackground()
                }
            }
            ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
            installed = true
            Log.i(TAG, "Installed on ProcessLifecycleOwner")
        }
    }
}
