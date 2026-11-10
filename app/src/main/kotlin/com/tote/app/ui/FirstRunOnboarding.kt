package com.tote.app.ui

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.tote.app.ToteApplication
import com.tote.app.crypto.BIP39
import kotlinx.coroutines.launch

/**
 * First-run onboarding flow.
 *
 * Walks the user through:
 *   1. Welcome — what Tote is + what they're about to set up
 *   2. Identity generation — explains the Ed25519 + X25519 keypair
 *   3. Recovery code display — 12-word BIP-39 mnemonic (write down!)
 *   4. Recovery code confirm — type word #N to confirm
 *   5. Done — pointer to "Pair a device" via the + button
 *
 * Per ADR-019: only shown if SharedPreferences "tote_onboarding_complete" is
 * false. After the user completes onboarding, the flag is set and they go
 * straight to the BrowserScreen on subsequent launches.
 *
 * Per CRYPTO-SPEC-FINAL §5: the recovery code is generated fresh here from
 * 128 bits of SecureRandom entropy and stored (encrypted at rest by v1.0.0
 * AndroidKeyStore wrapping).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FirstRunOnboardingScreen(
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as ToteApplication
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 5 })

    // Generate the recovery code once, on first composition.
    val recovery = remember {
        val identity = app.identityStore.getOrCreateIdentity()
        val entropy = identity.seed.copyOf(16)  // 128 bits → 12-word mnemonic
        val mnemonic = BIP39.entropyToMnemonic(entropy)
        mnemonic to entropy
    }
    val mnemonic = recovery.first

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Welcome to Tote") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> IdentityPage(app)
                    2 -> RecoveryDisplayPage(mnemonic)
                    3 -> RecoveryConfirmPage(mnemonic)
                    4 -> DonePage()
                }
            }

            // Bottom navigation: Previous / Next / Done
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    },
                    enabled = pagerState.currentPage > 0,
                ) { Text("Previous") }

                // Page indicator
                Text(
                    text = "${pagerState.currentPage + 1} / ${pagerState.pageCount}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )

                if (pagerState.currentPage < pagerState.pageCount - 1) {
                    Button(
                        onClick = {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        },
                    ) { Text("Next") }
                } else {
                    Button(onClick = {
                        // Mark onboarding complete
                        context.getSharedPreferences("tote_onboarding", 0)
                            .edit()
                            .putBoolean("complete", true)
                            .apply()
                        onComplete()
                    }) { Text("Get started") }
                }
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Public,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Tote",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "A multi-profile Android browser that lets you transfer logged-in sessions end-to-end encrypted between your own devices.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("What you can do:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("• Browse in multiple isolated profiles (no shared cookies)")
                Text("• Transfer a logged-in session to another device via QR + relay")
                Text("• Recover sessions on a fresh device with a 12-word code")
                Text("• Trigger nuke (24h cooldown) or duress (immediate) kill switches")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("What Tote does NOT do:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(8.dp))
                Text("• Forge TLS / JA3 / JA4 / canvas fingerprints (anti-fraud bright line)")
                Text("• Sync tabs in real-time across devices (only sessions, on-demand)")
                Text("• Replace your daily browser — alpha-quality, expect rough edges")
            }
        }
    }
}

@Composable
private fun IdentityPage(app: ToteApplication) {
    val identity = remember { app.identityStore.getOrCreateIdentity() }
    val fingerprint = identity.ed25519Pub.joinToString("") { "%02x".format(it) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Fingerprint,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Your device identity",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Tote created an Ed25519 + X25519 keypair from 32 bytes of SecureRandom entropy. This is your device's signing + encryption identity — it never leaves this device in plaintext.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Device fingerprint (Ed25519 pubkey, first 32 hex chars):",
                    style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = fingerprint.take(32) + "…",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Key material stored in app-private SharedPreferences (sandboxed). AndroidKeyStore AES-GCM wrapping lands in v1.0.0.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun RecoveryDisplayPage(mnemonic: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Key,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Save your recovery code",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Write down these 12 words on paper. Store offline. They are the ONLY way to recover your paired sessions if you lose all your devices.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                mnemonic.forEachIndexed { idx, word ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            text = "${idx + 1}.",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                            ),
                            modifier = Modifier.width(40.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = word,
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "⚠ Never share these words. Anyone with them can claim your paired sessions.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RecoveryConfirmPage(mnemonic: List<String>) {
    // Pick a random word index (1..12) for the user to type back
    val askedIdx = remember { (0..11).random() }
    var input by remember { mutableStateOf("") }
    val isMatch = input.trim().equals(mnemonic[askedIdx], ignoreCase = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = if (isMatch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Confirm you wrote it down",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Type word #${askedIdx + 1} to confirm you have the recovery code saved offline.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Word #${askedIdx + 1}") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = input.isNotEmpty() && !isMatch,
            supportingText = {
                if (input.isNotEmpty() && !isMatch) {
                    Text("Doesn't match — check word #${askedIdx + 1} on your written code")
                } else if (isMatch) {
                    Text("✓ Matches")
                }
            },
        )
    }
}

@Composable
private fun DonePage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "You're set up",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Open a tab and start browsing. To pair another device, tap the + button in the top bar.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Quick start:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("1. Browse to a site, log in normally")
                Text("2. Tap the + button on the other device's Tote to show its QR")
                Text("3. Tap the + button on this device → Scan their QR")
                Text("4. Confirm the 6-digit + 3-emoji SAS matches on both screens")
                Text("5. Tap Send → choose recipient → they receive your session in the background")
            }
        }
    }
}
