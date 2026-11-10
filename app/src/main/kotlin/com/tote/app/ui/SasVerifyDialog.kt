package com.tote.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * SAS (Short Authentication String) verification dialog.
 *
 * Shown after a QR scan completes the ECDH + HKDF pairing handshake. Both
 * devices display the same 6-digit number + 3-emoji sequence derived from
 * HKDF(pairingRoot, info="sas-v1") and HKDF(pairingRoot, info="sas-emoji-v1").
 *
 * Per CRYPTO-SPEC-FINAL §3: the user must visually compare and confirm
 * match. If they don't match, DO NOT confirm — there's a MITM in the middle.
 *
 * The confirm button is only enabled when the user explicitly taps "Match".
 * The "They don't match" button discards the pairing and returns to the
 * BrowserScreen.
 *
 * @param sasDigits 6-digit SAS string (e.g. "428571")
 * @param sasEmojis 3 emojis from the canonical 64-emoji list
 * @param onMatch Called when the user confirms the SAS matches
 * @param onMismatch Called when the user reports a mismatch
 * @param onDismiss Called when the user dismisses without confirming
 */
@Composable
fun SasVerifyDialog(
    sasDigits: String,
    sasEmojis: List<String>,
    onMatch: () -> Unit,
    onMismatch: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm pairing") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Compare these on BOTH devices. They must match exactly.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(20.dp))

                // 6-digit SAS — large monospace
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = sasDigits,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 3-emoji SAS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    sasEmojis.forEach { emoji ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.size(72.dp),
                        ) {
                            Text(
                                text = emoji,
                                style = MaterialTheme.typography.headlineLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .wrapContentSize(Alignment.Center),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "If the numbers or emojis don't match on both screens, DO NOT confirm — there may be a man-in-the-middle attack on the QR exchange.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            Button(onClick = onMatch) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("They match")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onMismatch) { Text("They don't match") }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
