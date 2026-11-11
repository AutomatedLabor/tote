# Contributing to Tote

> **Last updated:** 2026-11-11 (v1.0.0-alpha1)
> **License:** MIT — see [LICENSE](LICENSE)
> **Code of conduct:** be excellent to each other. We're building a privacy tool; let's model that.

## Quick start

### Prerequisites

- **JDK 17** (OpenJDK 17.x, any vendor — Temurin recommended)
- **Android SDK** with `compileSdk 34` (Android Studio Hedgehog or newer installs this automatically)
- **Git** (with your GitHub PAT or SSH key configured)
- An Android device or emulator running **API 26+ (Android 8.0+)** for runtime testing

### Clone + build

```bash
git clone https://github.com/AutomatedLabor/tote.git
cd tote
./gradlew :app:assembleDebug      # debug APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest  # run all JVM unit tests (~140 passing)
```

The first build will take ~3 minutes (downloads Gradle 8.7 + all deps). Subsequent builds are ~30 seconds.

### Build a release APK

You'll need a release keystore. For local dev, the build falls back to the debug signing config if no keystore is provided, so `./gradlew :app:assembleRelease` works on a fresh clone:

```bash
./gradlew :app:assembleRelease     # release APK at app/build/outputs/apk/release/app-release.apk
```

To sign with your own keystore, create `keystore.properties` at the project root (gitignored — see `keystore.properties.example`):

```properties
storeFile=/path/to/your.keystore
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

### Run on a device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.tote.app/.MainActivity
```

Or open the project in Android Studio and click Run.

## Architecture overview

See [`tote-docs/planning/ARCHITECTURE.md`](https://github.com/AutomatedLabor/tote-docs/blob/main/planning/ARCHITECTURE.md) for the full module map + data-flow diagrams.

Quick summary:

- `crypto/` — Ed25519, X25519, XChaCha20-Poly1305-IETF, Argon2id, HKDF-SHA256, AndroidKeyStore wrapper. The crypto boundary lives here.
- `session/` — SessionBlob wire format, SessionTransferService (send/receive facade), PeerDeviceRegistry, SessionSyncService (foreground relay poller)
- `pairing/` — QR payload codec + PairingHandshake (ECDH + HKDF + SAS)
- `relay/` — OkHttp client for the Go relay
- `recovery/` — BIP-39 mnemonic + KillSwitch state machine
- `profile/` — AndroidX WebKit Profile registry wrapper
- `webview/` — WebViewPool (LRU + saveState/restoreState), TabManager, DeviceTierHelper, ToteMemoryObserver
- `ui/` — Compose screens (BrowserScreen, SettingsScreen, FirstRunOnboarding, QrScannerScreen, etc.)

## API stability tiers

See [`tote-docs/planning/API-STABILITY.md`](https://github.com/AutomatedLabor/tote-docs/blob/main/planning/API-STABILITY.md). Short version:

- **Stable**: `crypto/`, `relay/`, `session/SessionBlob`, `session/SessionTransferService`, `session/PeerDeviceRegistry`, `pairing/`, `recovery/` — safe for forks/extensions to depend on.
- **Experimental**: `ui/` composables, `webview/` public types — may change between minor versions.
- **Internal**: `webview/` WebViewPool internals, KeystoreWrapper implementation details, RelayClient regex-based JSON parsing — do not depend on.

## Testing

We test at three levels:

1. **JVM unit tests** (`./gradlew :app:testDebugUnitTest`) — pure JVM, no Android dependencies. ~140 tests covering crypto math, JSON round-trips, Argon2id, AES-GCM, BIP-39. Run on every push.

2. **Robolectric integration tests** — same JVM test runner but with a real Android Context (via Robolectric). Covers SharedPreferences-backed stores (IdentityStore, PeerDeviceRegistry). Run on every push.

3. **Instrumented tests** (`./gradlew :app:connectedAndroidTest`) — Espresso/UI Automator on a real device or emulator. Covers the QR scan → pairing → SAS verify → persist flow. TODO at v1.0.0 ship.

CI runs (1) and (2) on every push. (3) requires a manual trigger.

## Pull request checklist

Before opening a PR:

- [ ] `./gradlew :app:testDebugUnitTest` passes locally
- [ ] `./gradlew :app:assembleDebug` succeeds
- [ ] `./gradlew :app:lintDebug` doesn't introduce new warnings (unless justified)
- [ ] New code has KDoc on every public API
- [ ] New crypto code has a JVM round-trip test
- [ ] No secrets in the diff (PATs, API keys, keystore passwords)
- [ ] If you added a new permission, document it in [PRIVACY.md](PRIVACY.md)
- [ ] If you changed the wire format, document it in [`tote-docs/planning/API-STABILITY.md`](https://github.com/AutomatedLabor/tote-docs/blob/main/planning/API-STABILITY.md) + bumped the relevant version

## Iron rules (don't break these)

From [`AutomatedLabor/build-learnings`](https://github.com/AutomatedLabor/build-learnings) (private repo, but the rules are general):

- **#2**: No secrets in build files. All secrets via env vars + GitHub Actions secrets.
- **#15**: Only claim what you can show. No "tested" without a test, no "working" without a CI green, no "secure" without a threat model entry.
- **#16**: Use `apksigner verify` to verify APK signatures, NOT `keytool -printcert` (which produces false negatives on v2+v3-signed APKs).
- **#17**: Pin the certificate SHA-256 in the CI verification step. The release cert SHA-256 is `df96cae5c963cef4b2adccd6ccf3a76113a0386e2376232f3dc758374acf4196`.
- **Anti-fraud bright line**: Tote does NOT forge TLS/JA3/JA4/canvas fingerprints. We move your own sessions across your own devices. PRs that introduce fingerprint-forging capabilities will be rejected.

## Reporting a security vulnerability

Email security@automatedlabor.io (TODO: set up) OR open a private security advisory on GitHub: https://github.com/AutomatedLabor/tote/security/advisories/new

Do NOT open a public GitHub Issue for security vulnerabilities.

## License

By contributing, you agree that your contributions will be licensed under the MIT License. See [LICENSE](LICENSE).
