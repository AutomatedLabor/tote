package com.tote.app.ui

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tote.app.ToteApplication
import com.tote.app.pairing.PairingHandshake
import com.tote.app.pairing.PairingPayload
import com.tote.app.relay.PeerRecord
import com.tote.app.relay.RelayClient
import com.tote.app.relay.RelayConfig
import com.tote.app.session.PeerDeviceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receiver-side pairing flow — RELAY-MEDIATED (v0.6.0+).
 *
 * Triggered after the user scans a sender's QR code. State machine:
 *
 * 1. COMPUTE — decodes the [PairingPayload], computes the ECDH shared secret
 *    using the local device's X25519 private key + the sender's X25519 pubkey
 *    (from the QR), derives pairingRoot, computes SAS.
 *
 * 2. SHOW_SAS — displays the SAS verify dialog. Both devices MUST show the
 *    same 6-digit + 3-emoji SAS. On "They match":
 *      a. PUBLISH_OUR_PUB — publish our X25519 + Ed25519 pubkey to the relay
 *         via `PUT /peer/<ourDeviceId>` (idempotent).
 *      b. PERSIST — add sender to PeerDeviceRegistry + save pairingRoot in
 *         IdentityStore.
 *      c. DONE.
 *
 * On "They don't match":
 *   - Discard — possible MITM. Don't publish anything, don't persist anything.
 *
 * Per CRYPTO-SPEC-FINAL §3: this is the inverse of the sender's flow.
 * Both devices must derive the same pairingRoot + SAS, so the user can
 * visually verify no MITM is present.
 *
 * The sender's flow now uses [RelayClient.fetchPeer] to look up OUR X25519
 * pubkey (instead of the user manually pasting it, as in v0.5.0).
 *
 * @param scannedQr The raw QR string ("tote://pair/<base64url>")
 * @param onPairingComplete Called when the pairing is fully committed
 * @param onDismiss Called when the user dismisses without completing
 */
@Composable
fun ReceiverPairingFlowDialog(
    scannedQr: String,
    onPairingComplete: (pairingRoot: ByteArray, peer: PeerDeviceRegistry.Peer) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as ToteApplication

    var stage by remember { mutableStateOf(ReceiverStage.COMPUTE) }
    var payload by remember { mutableStateOf<PairingPayload?>(null) }
    var pairingRoot by remember { mutableStateOf<ByteArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val identity = remember { app.identityStore.getOrCreateIdentity() }

    // Decode the QR + compute the pairingRoot once on first composition.
    LaunchedEffect(scannedQr) {
        try {
            val p = PairingPayload.fromQrString(scannedQr)
            payload = p
            val handshake = PairingHandshake(identity, p)
            val shared = handshake.computeSharedSecret(p.senderX25519Pub)
            val root = handshake.derivePairingRoot(shared, p.nonceA)
            pairingRoot = root
            stage = ReceiverStage.SHOW_SAS
        } catch (t: Throwable) {
            Log.e("ReceiverPairing", "Failed to compute pairingRoot", t)
            error = "Invalid pairing QR: ${t.message}"
            stage = ReceiverStage.ERROR
        }
    }

    when (stage) {
        ReceiverStage.COMPUTE -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Pairing…") },
                text = { Text("Computing shared secret + SAS…") },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                },
            )
        }
        ReceiverStage.SHOW_SAS -> {
            val p = payload
            val root = pairingRoot
            if (p != null && root != null) {
                val handshake = remember(p) { PairingHandshake(identity, p) }
                val sasDigits = handshake.computeSas(root)
                val sasEmojis = handshake.computeSasEmoji(root)
                SasVerifyDialog(
                    sasDigits = sasDigits,
                    sasEmojis = sasEmojis,
                    onMatch = {
                        // Publish our pubkey + persist peer + pairingRoot.
                        // Run in IO dispatcher so we don't block the UI.
                        stage = ReceiverStage.PUBLISH_OUR_PUB
                        MainScope().launch {
                            try {
                                val relayConfig = RelayConfig(
                                    baseUrl = p.relayUrl,
                                    baseUrlWs = p.relayUrl.replace("https://", "wss://"),
                                    localDeviceId = identity.ed25519Pub,
                                )
                                val relay = RelayClient(relayConfig)
                                val ourName = "Device " + identity.ed25519Pub.joinToString("") { "%02x".format(it) }.take(8)
                                withContext(Dispatchers.IO) {
                                    relay.publishPeer(
                                        x25519Pub = identity.x25519Pub,
                                        ed25519Pub = identity.ed25519Pub,
                                        displayName = ourName,
                                    )
                                }
                                val peer = PeerDeviceRegistry.Peer(
                                    deviceId = p.senderDeviceId,
                                    x25519Pub = p.senderX25519Pub,
                                    displayName = "Device " + p.senderDeviceId.joinToString("") { "%02x".format(it) }.take(8),
                                    pairedAt = System.currentTimeMillis(),
                                    lastSeenAt = System.currentTimeMillis(),
                                )
                                PeerDeviceRegistry.getInstance(context).upsert(peer)
                                app.identityStore.savePairingRoot(root)
                                onPairingComplete(root, peer)
                                stage = ReceiverStage.DONE
                            } catch (t: Throwable) {
                                Log.e("ReceiverPairing", "publishPeer failed", t)
                                error = "Failed to publish our pubkey to relay: ${t.message}"
                                stage = ReceiverStage.ERROR
                            }
                        }
                    },
                    onMismatch = {
                        Log.w("ReceiverPairing", "SAS mismatch — discarding pairing")
                        stage = ReceiverStage.DONE
                    },
                    onDismiss = {
                        stage = ReceiverStage.DONE
                    },
                )
            }
        }
        ReceiverStage.PUBLISH_OUR_PUB -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Publishing…") },
                text = { Text("Publishing our pubkey to the relay so the sender can fetch it…") },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                },
            )
        }
        ReceiverStage.ERROR -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Pairing failed") },
                text = {
                    Column {
                        Text(
                            error ?: "Unknown error.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("OK") }
                },
            )
        }
        ReceiverStage.DONE -> {
            LaunchedEffect(Unit) { onDismiss() }
        }
    }
}

private enum class ReceiverStage {
    COMPUTE,
    SHOW_SAS,
    PUBLISH_OUR_PUB,
    ERROR,
    DONE,
}
