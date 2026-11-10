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
import com.tote.app.session.PeerDeviceRegistry

/**
 * Receiver-side pairing flow.
 *
 * Triggered after the user scans a sender's QR code. Decodes the
 * [PairingPayload], computes the ECDH shared secret using the local
 * device's X25519 private key + the sender's X25519 pubkey (from the
 * QR), derives the pairingRoot, computes the SAS, and shows the SAS
 * verify dialog.
 *
 * On "They match":
 * - Persists the sender as a peer in [PeerDeviceRegistry]
 * - Saves the pairingRoot in [com.tote.app.crypto.IdentityStore]
 * - Dismisses
 *
 * State machine:
 * 1. COMPUTE — runs ECDH + HKDF synchronously (fast, ~1ms)
 * 2. SHOW_SAS — displays the SAS verify dialog
 * 3. DONE — peer + pairingRoot persisted, dismiss
 *
 * Per CRYPTO-SPEC-FINAL §3: this is the inverse of the sender's flow.
 * Both devices must derive the same pairingRoot + SAS, so the user can
 * visually verify no MITM is present.
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
            // Receiver's view: the "sender" is the device that showed the QR.
            // The receiver (this device) computes ECDH(senderX25519Pub) using
            // its own X25519 private key (held inside DeviceIdentity).
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
                    },
                    onMismatch = {
                        // Discard — possible MITM
                        Log.w("ReceiverPairing", "SAS mismatch reported by user — discarding pairing")
                        stage = ReceiverStage.DONE
                    },
                    onDismiss = {
                        stage = ReceiverStage.DONE
                    },
                )
            }
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
    ERROR,
    DONE,
}
