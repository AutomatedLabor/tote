# Changelog

All notable changes to Tote will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Pending (next batch)
- UI wiring for cookie transfer button (snapshot + apply via the existing `SessionSnapshot` facade)
- Pairing flow screens (QR generation/scanning + SAS verification UI)
- Recovery onboarding flow (first-run BIP-39 generation + confirmation)
- Kill switch settings screen (nuke trigger + duress code setup)
- Relay client wired to foreground sync service
- Truly-live tabs polish (screenshot capture, scrollY restore, form state, saveState)
- Theme + adaptive launcher icon
- Accessibility (TalkBack, content descriptions, switch control)
- Empty/error/loading states across all UI screens
- First-run onboarding tour

## [v0.2.0-alpha1] - 2026-11-10

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
