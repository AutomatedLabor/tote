package com.tote.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tote.app.ToteApplication
import com.tote.app.pairing.PairingHandshake
import com.tote.app.pairing.PairingPayload
import com.tote.app.pairing.QrCodeGenerator
import androidx.compose.ui.platform.LocalContext

/**
 * Pairing dialog: shows the sender's QR code for the receiver to scan.
 *
 * Flow:
 * 1. Sender taps "Pair a device" in Settings (or a dedicated button).
 * 2. This dialog generates a [PairingPayload] from the local device identity
 *    + a fresh nonce, renders the QR, and displays it full-screen.
 * 3. The receiver scans the QR, performs ECDH + HKDF, computes the SAS.
 * 4. Both devices show the SAS (6 digits + 3 emojis). The user confirms match.
 * 5. On confirm, the sender's [IdentityStore.savePairingRoot] persists the
 *    new pairing root, and the receiver does the same on its side.
 *
 * For the alpha, the receiver-side flow (QR scanning + SAS confirm) is a
 * TODO — this dialog is the sender-side half.
 */
@Composable
fun PairingDialog(
    onDismiss: () -> Unit,
    onPairingComplete: (pairingRoot: ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as ToteApplication

    // Build the pairing payload once on first composition.
    val payload = remember {
        val identity = app.identityStore.getOrCreateIdentity()
        val nonce = ByteArray(32).also {
            java.security.SecureRandom().nextBytes(it)
        }
        PairingPayload(
            relayUrl = "https://relay.tote.app/v1",
            senderDeviceId = identity.ed25519Pub,
            senderX25519Pub = identity.x25519Pub,
            nonceA = nonce,
            saltHint = ByteArray(16).also {
                java.security.SecureRandom().nextBytes(it)
            },
        )
    }

    // Render the QR once.
    val qrBitmap: Bitmap = remember(payload) {
        QrCodeGenerator.generate(payload.toQrString(), size = 512)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair a device") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Scan this QR with the other device running Tote.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Pairing QR code",
                    modifier = Modifier
                        .size(256.dp)
                        .background(Color.White),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "After scanning, both devices will show a 6-digit code + 3 emojis. Confirm they match on BOTH screens to complete the pairing.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Your device ID:",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = payload.senderDeviceId.joinToString("") { "%02x".format(it) },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
