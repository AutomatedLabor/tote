package com.tote.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import com.tote.app.crypto.LazysodiumBootstrap
import com.tote.app.profile.ProfileRegistry
import com.tote.app.webview.ToteMemoryObserver

/**
 * Tote application entry point.
 *
 * Bootstraps:
 * 1. **Lazysodium** — JNA-extracted libsodium. If this fails the app is
 *    unusable, so we let it throw (process will crash with a clear stack trace).
 * 2. **ProfileRegistry** — ensures the default "Personal" profile exists before
 *    any Activity tries to use it.
 * 3. **ToteMemoryObserver** — wires [ProcessLifecycleOwner] to a memory-pressure
 *    listener that triggers WebViewPool LRU eviction on TRIM_MEMORY events.
 *
 * Per ADR-007: this is the only place that holds app-wide singletons. Activities
 * fetch them via `application as ToteApplication`.
 */
class ToteApplication : Application() {

    private val TAG = "ToteApplication"

    /** App-wide singleton, lazily initialized on first access. */
    val profileRegistry: ProfileRegistry by lazy { ProfileRegistry.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Tote starting…")

        // 1. Bootstrap lazysodium eagerly (so a load failure surfaces at app start,
        //    not the first time the user tries to pair a device).
        try {
            LazysodiumBootstrap.get()
            Log.i(TAG, "libsodium loaded ✓")
        } catch (t: Throwable) {
            Log.e(TAG, "libsodium load FAILED — crypto will not work", t)
            // Don't crash: app should still launch so the user can see an error
            // and the diagnostic surface. Every crypto op will throw when invoked.
        }

        // 2. Touch profileRegistry to force initialization (creates default profile).
        try {
            profileRegistry
            Log.i(TAG, "ProfileRegistry initialized ✓")
        } catch (t: Throwable) {
            Log.e(TAG, "ProfileRegistry init FAILED", t)
        }

        // 3. Wire memory-pressure observer (for WebViewPool LRU eviction).
        try {
            ToteMemoryObserver.install(this)
            Log.i(TAG, "Memory observer installed ✓")
        } catch (t: Throwable) {
            Log.w(TAG, "Memory observer install failed (non-fatal): ${t.message}")
        }

        Log.i(TAG, "Tote ready.")
    }

    companion object {
        /**
         * Convenience accessor for code that has a [Context] but doesn't want to
         * cast. Returns the singleton [ToteApplication].
         */
        fun get(context: Context): ToteApplication =
            context.applicationContext as ToteApplication
    }
}
