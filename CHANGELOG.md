# Changelog

All notable changes to Tote will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Pending (next batch)
- Animated illustrations for first-run onboarding (Lottie)
- Play Store submission (special-use FGS declaration already in manifest)
- Robolectric integration tests for SharedPreferences-backed stores
- End-to-end instrumented tests (Espresso / UI Automator) for the QR → pairing → SAS → persist flow

## [v0.6.0-alpha1] - 2026-11-11

### Added
- **Relay-mediated peer-pubkey exchange** — the Go relay now exposes `PUT/GET/DELETE /peer/<deviceId>` for publishing X25519+Ed25519 pubkeys + display name. Records have a 30-day TTL (swept every 5 min). The Android `RelayClient` gains `publishPeer()` / `fetchPeer()` / `revokePeer()` + a new `PeerRecord` data class. The `ReceiverPairingFlowDialog` now publishes our pubkey to the relay right after SAS confirm. The `FullPairingFlowDialog` (sender side) now fetches the receiver's X25519 pubkey from the relay by deviceId hex (one step less friction than pasting the X25519 pubkey directly). `ToteApplication.onCreate` now publishes our pubkey on every app start (idempotent, refreshes the TTL).
- **R8 / ProGuard enablement** — `isMinifyEnabled = true` + `isShrinkResources = true` on the release build. Comprehensive proguard-rules.pro covering lazysodium JNA reflection, kotlinx-serialization-generated serializers, OkHttp platform adapters, CameraX, ZXing, org.json, Compose runtime, Material3, AndroidX WebKit, Kotlin coroutines, and Tote's own data classes. Expected APK size reduction: 16 MB → 8-10 MB.

### Changed
- **ReceiverPairingFlowDialog** — state machine now: COMPUTE → SHOW_SAS → PUBLISH_OUR_PUB → DONE. On SAS match, publishes our pubkey to relay, persists peer + pairingRoot. Was: COMPUTE → SHOW_SAS → DONE (didn't publish).
- **FullPairingFlowDialog (sender side)** — state machine now: SHOW_QR → FETCH_PEER_PUB → SHOW_SAS → DONE. Replaces the v0.5.0 AwaitPeerPubDialog (manual X25519 pubkey paste) with AwaitPeerDeviceIdDialog (paste 64-hex deviceId, auto-fetch X25519 pubkey from relay). Adds FETCH_ERROR stage with retry.
- **ToteApplication.onCreate** — adds step 5: async publish our pubkey to the relay (best-effort, non-blocking).
- **Relay sweep** — Go relay now sweeps expired peer records every 5 min alongside the existing envelope sweep.
- **build.gradle.kts** — versionCode 5 → 6, versionName 0.5.0-alpha → 0.6.0-alpha. Release build now uses R8 + resource shrinking.

### Fixed
- **Relay-mediated peer-pubkey exchange closes the v0.5.0 deferred item** — was: "receiver pastes X25519 pubkey manually after scanning". Now: receiver publishes automatically after SAS confirm; sender fetches by deviceId hex.

## [v0.5.0-alpha1] - 2026-11-11

### Added
- **Argon2id for recovery code derivation** — [Argon2Kdf] wraps libsodium's `crypto_pwhash` (Argon2id algorithm). Parameters per OWASP 2023: t=3 ops, m=64 MiB, p=1. 10 unit tests covering round-trip, parameter sensitivity, salt uniqueness, mnemonic derivation, and a smoke test.
- **AndroidKeyStore AES-GCM wrapping** — [KeystoreWrapper] generates a master AES-256 GCM key in the AndroidKeyStore (hardware-backed on TEE/StrongBox devices). [IdentityStore] and [PeerDeviceRegistry] now encrypt at-rest secrets via this wrapper. Falls back to plaintext if AndroidKeyStore unavailable (rare, broken TEE) with a warning log. 10 unit tests covering round-trip, wrong-key rejection, tamper detection, and large/empty inputs.
- **Receiver-side pairing flow dialog** — [ReceiverPairingFlowDialog] decodes the scanned QR → computes ECDH + HKDF(pairingRoot) → shows the SAS verify dialog → on match, persists the peer + pairingRoot. State machine: COMPUTE → SHOW_SAS → DONE.
- **QR scan wired into navigation** — `ToteNavHost` now routes the scanned QR string through a `PendingScanHolder` to the `ReceiverPairingFlowDialog` composable, instead of just logging it.
- **PixelCopy tab screenshots** — `WebViewPool.WebViewEntry.checkpoint()` now captures a 256×256 screenshot via `PixelCopy.request()` on Android 8+ (with `View.draw()` fallback for older devices / non-attached WebViews). The screenshot is stored in `entry.screenshot` for use as a tab thumbnail.
- **Recovery salt persistence** — `IdentityStore.saveRecoverySalt()` / `getRecoverySalt()` persist the Argon2id salt alongside the pairingRoot. The salt is non-secret (its purpose is rainbow-table defeat).
- **IdentityStore nuke deletes AndroidKeyStore master key** — `zeroize()` now also calls `keystore.deleteMasterKey()` so previously-encrypted ciphertexts become unrecoverable.
- **PeerDeviceRegistry plaintext-fallback heuristic** — if a stored blob starts with `'['` (JSON array byte), it's treated as plaintext from an older version. Otherwise, treated as encrypted.
- **`peersToJson` / `peersFromJson` static methods** — extracted from [PeerDeviceRegistry] so the JSON round-trip is JVM-testable without a Context.
- **Relay sender_id field** — Go relay's `/inbox/{id}` poll response now returns `{"id":"...","size":N,"received_at":N,"sender_id":"<hex>"}` matching the Android client's regex. The Android client uses `sender_id` to look up the sender's Ed25519 pubkey in the [PeerDeviceRegistry] for signature verification on incoming session blobs.

### Changed
- **RecoveryCode.derivePairingRoot** — now takes (entropy, salt) and uses Argon2id (was HKDF without salt). The salt is persisted via [IdentityStore.saveRecoverySalt].
- **RecoveryCode.generate** — now returns `Triple(mnemonic, entropy, salt)` (was `Pair(mnemonic, entropy)`).
- **RecoveryCode.rotate** — returns `RotateResult` data class with mnemonic + entropy + salt + pairingRoot (was `Pair`).
- **RecoveryCode.deriveRecoveryHint** — now derives from pairingRoot (was from entropy). Chaining Argon2id twice would be wasteful; HKDF on the already-derived root is sufficient for a non-secret hint.
- **RecoveryCodeTest** — updated to pass salt to derivePairingRoot + verify the new Triple return shape. Added tests for salt sensitivity + rotate mnemonic validity.

### Fixed
- **QR scanner just logged the scanned QR** — now actually drives the receiver-side pairing flow (decode payload → ECDH → SAS verify → persist peer + pairingRoot).
- **Relay didn't expose sender_id** — Go relay now returns the sender's hex pubkey in the inbox poll response, so receivers can verify signatures on incoming session blobs.

## [v0.4.0-alpha1] - 2026-11-11

### Added
- **Theme + adaptive launcher icon** — Material3 `ToteTheme` with branded Tote orange (#FF5722) + deep teal (#00695C) palette, plus Material You dynamic color on Android 12+. Adaptive launcher icon (vector foreground + gradient background). DayNight-aware.
- **Settings screen** — Full Settings UI: paired devices (list + remove), recovery code (view + rotate), kill switch (nuke trigger + duress code), about. Accessed from BrowserScreen top bar.
- **First-run onboarding flow** — 5-page horizontal pager: Welcome → Identity generation → Recovery code display → Recovery confirm (type word N) → Done. Gate: only shown if `tote_onboarding/complete` is false.
- **Recovery code display** — BIP-39 mnemonic rendered as numbered monospace list, with warning text. Accessible from Settings.
- **Kill switch UI** — NUKE trigger with live 24-hour countdown timer (cancel within window), DURESS code entry (≥8 chars, persisted to app-private SharedPreferences).
- **SAS verification dialog** — 6-digit number + 3-emoji display, with "They match" / "They don't match" buttons. Per CRYPTO-SPEC-FINAL §3: explicit MITM detection prompt.
- **Full pairing flow dialog** — State machine: SHOW_QR → AWAIT_PEER_PUB → SHOW_SAS → DONE. Persists peer to `PeerDeviceRegistry` and pairingRoot to `IdentityStore`.
- **Peer device registry** — `PeerDeviceRegistry` persists paired devices (deviceId + x25519Pub + displayName + pairedAt + lastSeenAt) as JSON in SharedPreferences. Used by sync service for sender pubkey lookup on receive.
- **Foreground sync service** — `SessionSyncService` polls the relay every 60s for incoming envelopes. Foreground service with low-priority notification. Applies incoming session blobs + posts a separate notification when an incoming session is applied.
- **Truly-live tabs polish** — `WebViewPool` now saves `WebView.saveState()` Bundle + URL + scrollY on LRU eviction / app background. On next access, `WebView.restoreState()` restores history + form data + scroll position (no reload). Per ADR-014.
- **Navigation scaffold** — `ToteNavHost` with 4 routes (onboarding → browser → settings → qr_scanner). Onboarding gate via SharedPreferences.
- **Camera permission + CAMERA feature** — Manifest declares `android.permission.CAMERA` and `android.hardware.camera` (required=false, since QR scanning is optional).
- **Foreground service special-use declaration** — Manifest declares `FOREGROUND_SERVICE_SPECIAL_USE` with the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property explaining the relay polling use case.
- **Status banner** — BrowserScreen shows last send/receive status (success/error) below the URL bar.
- **Settings button** — Added to BrowserScreen top bar (gear icon).

### Changed
- **MainActivity** — Now uses `ToteTheme` + `ToteNavHost` for navigation. Starts `SessionSyncService` on launch. Checks `tote_onboarding/complete` SharedPreferences flag to route to onboarding on first launch.
- **BrowserScreen** — Added Settings IconButton in top bar + status banner below URL bar.
- **WebViewPool** — `WebViewEntry.checkpoint()` now also saves `WebView.saveState()` Bundle. `getOrCreateWebView()` calls `restoreState()` if a saved Bundle exists. `destroyAll()` and `onAppBackground()` preserve state in sidecar maps before destroying.
- **RelayClient** — `InboxEntry` now has an optional `senderId` field (parsed from relay response if present). Regex updated to optionally match `sender_id` in JSON.
- **build.gradle.kts** — versionCode 3 → 4, versionName 0.3.0-alpha → 0.4.0-alpha. Added CameraX (camera-core, camera-camera2, camera-lifecycle, camera-view) + material-icons-extended dependencies.
- **libs.versions.toml** — Added cameraX = "1.3.4" version + 4 cameraX libraries + material-icons-extended.

### Fixed
- None — all v0.3.0-alpha1 tests preserved (117 passing).

## [v0.3.0-alpha1] - 2026-11-10

### Added
- **Cookie snapshot pipeline** — `CookieSnapshotExtractor` opens the WebView SQLite `Cookies` file per profile, reads all 16 attributes per cookie. OSCrypt fallback via `CookieManager.getCookie()` per host for cookies where the SQLite `value` column is empty (encrypted_value path).
- **Cookie importer** — `CookieImporter` reconstructs Set-Cookie headers via `CookieRecord.toSetCookieString()` and calls `CookieManager.setCookie()` per host. Flushes to disk.
- **Session snapshot facade** — `SessionSnapshot.snapshotForProfile()` extracts → JSON serializes → signs → encrypts into a `SessionBlob`. `applyBlob()` does the inverse for incoming transfers.
- **Relay client** — `RelayClient` (OkHttp + WebSocket): POST /envelope, GET /inbox/{id}, GET /envelope/{id}, DELETE /envelope/{id}, GET /healthz, WS /inbox/{id}/events. 20-bit SHA-256 hashcash PoW. Local token-bucket rate limiter (30/min). 4MB payload cap.
- **BIP-39 mnemonic** — Full spec implementation with the canonical 2048-word English wordlist embedded as a Kotlin object literal. Entropy → mnemonic → entropy round-trip with SHA-256 checksum validation. Tested against the official BIP-39 test vectors (all-zeros, all-ones, 256-bit).
- **Pairing handshake** — `PairingPayload` QR codec (`tote://pair/<base64url>`). `PairingHandshake` computes ECDH shared secret + HKDF(pairingRoot). 6-digit SAS + 3-emoji SAS (64 canonical emojis for MITM detection).
- **Recovery code** — `RecoveryCode.generate()` produces 12-word mnemonic + 16-byte entropy. `derivePairingRoot()` via HKDF (Argon2id TODO v1.0.0). `rotate()` mixes current entropy with fresh random to produce new mnemonic + new root.
- **Kill switch** — `KillSwitch` dual-mode state machine:
  - NUKE: 24h cooldown, cancellable. Transitions IDLE → NUKE_PENDING → NUKE_EXECUTED.
  - DURESS: ≤5min, NOT cancellable, ≥8 chars. Transitions IDLE → DURESS_ACTIVATED.
- 45 new unit tests across 5 test files (BIP39Test, KillSwitchTest, RecoveryCodeTest, PairingHandshakeTest, RelayClientTest).

### Changed
- Version bumped to `0.2.0-alpha` (versionCode = 2).
- Added `okhttp` 4.12.0 as a main `implementation` dependency (was test-only).
- `data_extraction_rules.xml` — replaced invalid `domain="cache"` with `domain="sharedpref"` + `domain="external"` (cache files are excluded implicitly by Android).

### Fixed
- BIP-39 entropyBits/checksumBits swap in `mnemonicToEntropy` (had them backwards).
- `KillSwitch` used `android.util.Log` (not JVM-testable) → `java.util.logging.Logger`.
- `RecoveryCode.rotate` passed 32-byte SHA-256 output to BIP-39 (which expects 16 bytes for 12 words).
- `PairingPayload` used `android.util.Base64` → `java.util.Base64` (JVM-testable).
- `RelayConfig` data class had `override equals` inline in constructor → moved to body with `contentEquals` for ByteArray comparison.
- `AtomicLong.set()` calls needed explicit `.toLong()` conversion from Int (Kotlin doesn't auto-widen).
- Old `CryptoPrimitives.BIP39` object (only 256 words, no checksum) conflicting with the new canonical `BIP39.kt` → removed.
- `build.yml`: needed `id-token:write` + `attestations:write` + `contents:write` permissions for SLSA + Releases.
- SLSA attestation wrapped in `continue-on-error: true` so build doesn't fail if attestation persistence has issues.

## [v0.1.0-alpha1] - 2026-11-10

### Added
- **First signed APK.** Build-pipeline milestone, not a usable app yet.
- **Crypto module (lazysodium-backed):** Ed25519 sign/verify, X25519 ECDH, XChaCha20-Poly1305-IETF encrypt/decrypt with Ed25519 detached signatures, HKDF-SHA256 (RFC 5869 verified), epoch ratchet Variant B with sender-authenticated membership lists, CBOR serialization.
- **Android foundation:** `ToteApplication` bootstraps lazysodium + ProfileRegistry + memory observer. `BrowserScreen` uses `WebViewPool` for truly-live tabs (ADR-014). `DeviceTierHelper` auto-sizes the pool based on `ActivityManager.memoryClass`. `ToteMemoryObserver` bridges `ProcessLifecycle.onStop` → `WebViewPool.onAppBackground`.
- **CI pipeline:** GitHub Actions workflow that compiles the Android app, runs unit tests, signs the APK with the release keystore, verifies with `apksigner` (NOT `keytool`), generates `.sha256` sidecar, attempts SLSA attestation, uploads APK artifact, publishes to GitHub Releases on tag.
- **Scanners active:** gitleaks (16-rule secret scan), osv-scanner, codeql (best-effort — can't trace through custom fat-jar wrapper).
- **Assert-no-app-without-scanners guard:** fails the build if `app/` exists but either scanner is still in `.github/workflows-pending/`.
- 67 JVM unit tests across 6 test files (DeviceIdentityTest, HKDFTest, SessionBlobTest, PairingLinkTest, EpochStateTest, CookieRecordTest, DeviceTierHelperTest).

### Fixed
- `BrowserScreen.kt` — typo'd import `gestureorientation.DraggableState` (should be `gestures`), missing `detectTapGestures` import, `Icons.Default.Work` (only in material-icons-extended) → `Icons.Default.Person` (core icons).
- `build.yml` — duplicate `with:` block on setup-gradle step caused YAML parse failure (every prior build was failing silently at YAML parse with "0 jobs").
- `build.gradle.kts` — Gradle Kotlin DSL doesn't auto-import `java.io.File` or `java.util.Base64`. Added explicit imports.
- `LazysodiumBootstrap` — called `sodiumVersion()` method that doesn't exist on `LazySodiumJava`. Replaced with cryptoScalarMultBase smoke test.
- RFC 5869 test vectors — two transcription errors (missing `5` in PRK, transposed digits `5665` → `5865` in OKM). Verified via Python `hmac` + `hashlib`.
- `data_extraction_rules.xml` — `domain="cache"` is not a valid Android backup domain. Replaced with `sharedpref` + `external`.
- `apksigner verify` step — used bare `apksigner` (not on PATH on ubuntu-latest runners) AND the sed regex didn't match the actual apksigner output format (`certificate SHA-256 digest:` not `SHA-256:`).

## [v0.0.0-pre] - 2026-11-10

Initial commit. Planning phase. No code.
