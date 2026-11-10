package com.tote.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import com.tote.app.crypto.IdentityStore
import com.tote.app.crypto.LazysodiumBootstrap
import com.tote.app.profile.ProfileRegistry
import com.tote.app.webview.ToteMemoryObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tote application entry point.
 *
 * Bootstraps:
 * 1. **Lazysodium** — JNA-extracted libsodium. If this fails the app is
 *    unusable, so we let it throw (process will crash with a clear stack trace).
 * 2. **ProfileRegistry** — ensures the default "Personal" profile exists before
 *    any Activity tries to use it.
 * 3. **IdentityStore** — loads (or generates) the local device identity + epoch
 *    state. Exposed as a singleton so BrowserViewModel can construct a
 *    SessionTransferService for the "send session" verb.
 * 4. **ToteMemoryObserver** — wires [ProcessLifecycleOwner] to a memory-pressure
 *    listener that triggers WebViewPool LRU eviction on TRIM_MEMORY events.
 *
 * Per ADR-007: this is the only place that holds app-wide singletons. Activities
 * fetch them via `application as ToteApplication`.
 */
class ToteApplication : Application() {

    private val TAG = "ToteApplication"

    /** App-wide singleton, lazily initialized on first access. */
    val profileRegistry: ProfileRegistry by lazy { ProfileRegistry.getInstance(this) }

    /** Loads/generates the local device identity + epoch state, persisted. */
    val identityStore: IdentityStore by lazy { IdentityStore(this) }

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

        // 3. Load (or generate) the device identity + epoch state.
        try {
            val identity = identityStore.getOrCreateIdentity()
            identityStore.getOrCreateEpochState(identity)
            Log.i(TAG, "IdentityStore initialized ✓")
        } catch (t: Throwable) {
            Log.e(TAG, "IdentityStore init FAILED — send/receive will not work", t)
        }

        // 4. Wire memory-pressure observer (for WebViewPool LRU eviction).
        try {
            ToteMemoryObserver.install(this)
            Log.i(TAG, "Memory observer installed ✓")
        } catch (t: Throwable) {
            Log.w(TAG, "Memory observer install failed (non-fatal): ${t.message}")
        }

        // 5. Publish our pubkey to the relay (best-effort, async — don't block onCreate).
        // Per v0.6.0-alpha1: relay-mediated peer-pubkey exchange. Other devices
        // need to be able to fetch our X25519 pubkey to complete the pairing
        // handshake. We publish on every app start (the relay endpoint is
        // idempotent, so this just refreshes the TTL).
        try {
            val identity = identityStore.getOrCreateIdentity()
            val relayConfig = com.tote.app.relay.RelayConfig(
                baseUrl = "https://relay.tote.app/v1",
                baseUrlWs = "wss://relay.tote.app/v1",
                localDeviceId = identity.ed25519Pub,
            )
            val relay = com.tote.app.relay.RelayClient(relayConfig)
            val ourName = "Device " + identity.ed25519Pub.joinToString("") { "%02x".format(it) }.take(8)
            MainScope().launch {
                try {
                    val ok = withContext(Dispatchers.IO) {
                        relay.publishPeer(
                            x25519Pub = identity.x25519Pub,
                            ed25519Pub = identity.ed25519Pub,
                            displayName = ourName,
                        )
                    }
                    if (ok) {
                        Log.i(TAG, "Peer pubkey published to relay ✓")
                    } else {
                        Log.w(TAG, "Peer pubkey publish returned non-200 — relay may be down")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Peer pubkey publish failed (non-fatal): ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to enqueue peer pubkey publish: ${t.message}")
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
