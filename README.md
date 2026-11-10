# Tote

**A multi-profile Android browser that lets you transfer logged-in web sessions between your own devices, end-to-end encrypted. WhatsApp Linked Devices, for web sessions, on mobile.**

You log into a grocery web app on Phone A. 5 minutes later (or 5 weeks later) you want that same logged-in session on Phone B. Tote transfers the session — cookies and all — via an E2E-encrypted relay. No Playwright. No Chromium fork. Just a WebView-based mobile app that owns its cookie jar.

**There is no vendor account. There is no cloud copy of your sessions. The relay cannot read your data.**

If the user loses a phone, they revoke its access from a paired device. If both phones are lost, they recover from a 12-word code. The product survives worst-day-of-their-life scenarios.

## Status: ALPHA — v0.5.0-alpha1 SHIPPED (PUBLISHED)

Tote is in active alpha development. **Five pre-release APKs are now live on GitHub Releases:**

| Release | APK size | APK SHA-256 | Tests | Date |
|---|---|---|---|---|
| `v0.1.0-alpha1` | 7.46 MB | `43c28483d6e585f5b89997c47210f1901f37f9f87ca65aeb226ceffddc1fa6c7` | 67 | 2026-11-10 |
| `v0.2.0-alpha1` | 7.88 MB | `19c88b96551c8644c30b0e4a6a1a815c2ae9b5bdeb3e949060a0a5ee090fe0fd` | 112 | 2026-11-10 |
| `v0.3.0-alpha1` | 7.89 MB | `7b58e4eeffeb3d81b97df46a40ca4c426822c364a59f1dc169e6d424c1081ae2` | 117 | 2026-11-10 |
| `v0.4.0-alpha1` | 16.47 MB | `5ccb8859ed84446d8aaab0735e2f5b1501b6cff2575f9bf57fe026eeb2859897` | 117 | 2026-11-11 |
| `v0.5.0-alpha1` | 16.49 MB | `b87af1dc6fdb1c287984b38c9ba3d72caa7c98bce2c7762b1db5e5089d00333c` | 140 | 2026-11-11 |

All APKs are:
- Code-signed with the production release keystore (cert SHA-256: `df96cae5c963cef4b2adccd6ccf3a76113a0386e2376232f3dc758374acf4196`).
- Verified by `apksigner verify --print-certs --verbose` (NOT `keytool`).
- Accompanied by a `.sha256` sidecar.
- Uploaded as GitHub Actions artifacts (downloadable even without release access).
- SLSA build provenance attestation attempted (best-effort via `continue-on-error`).
- 100% JVM unit-test passing on each push.

**v0.5.0-alpha1** adds the security + crypto hardening layer: Argon2id for recovery code derivation, AndroidKeyStore AES-GCM wrapping for at-rest secrets, receiver-side pairing flow dialog (QR scan → ECDH → SAS verify → persist), PixelCopy tab screenshots, and the Go relay now exposes `sender_id` for signature verification on incoming session blobs.

- **Working name:** Tote (placeholder; subject to user testing).
- **One-line pitch:** WhatsApp Linked Devices, for web sessions, on mobile.
- **Anti-fraud bright line (non-negotiable):** we move your own sessions across your own devices; we do not forge TLS fingerprints or defeat fraud systems.

## What works (v0.2.0-alpha1)

- **Android foundation:** `ToteApplication` bootstraps lazysodium + ProfileRegistry + memory observer. `BrowserScreen` uses `WebViewPool` for truly-live tabs (ADR-014). `DeviceTierHelper` auto-sizes the pool based on `ActivityManager.memoryClass`.
- **Crypto module (lazysodium-backed):** Ed25519 sign/verify, X25519 ECDH, XChaCha20-Poly1305-IETF encrypt/decrypt with Ed25519 detached signatures, HKDF-SHA256 (RFC 5869 verified), epoch ratchet Variant B with sender-authenticated membership lists, CBOR serialization.
- **Cookie extraction:** `CookieSnapshotExtractor` opens the WebView SQLite `Cookies` file per profile, reads all 16 attributes per cookie. OSCrypt fallback via `CookieManager.getCookie()` for encrypted_value cookies.
- **Cookie importer:** `CookieImporter` reconstructs Set-Cookie headers and calls `CookieManager.setCookie()` per host. Flushes to disk.
- **Session blob:** `SessionSnapshot` facade: extract → JSON → sign → encrypt → wire-format blob. Inverse for incoming transfers.
- **Relay client:** `RelayClient` (OkHttp + WebSocket) — POST /envelope, GET /inbox/{id}, DELETE /envelope/{id}, GET /healthz, WS /inbox/{id}/events. 20-bit SHA-256 hashcash PoW. Local token-bucket rate limiter (30/min). 4MB cap.
- **BIP-39 mnemonic:** Full spec implementation with the canonical 2048-word English wordlist + SHA-256 checksum. Tested against official BIP-39 test vectors.
- **Pairing handshake:** `PairingPayload` QR codec (`tote://pair/<base64url>`). `PairingHandshake` computes ECDH shared secret + HKDF(pairingRoot). 6-digit SAS + 3-emoji SAS (64 canonical emojis for MITM detection).
- **Recovery code:** 12-word mnemonic generation, validation, pairingRoot derivation, rotation.
- **Kill switch:** Dual-mode state machine — NUKE (24h cooldown, cancellable) + DURESS (≤5min, NOT cancellable, ≥8 chars).

## What does NOT work yet (alpha)

These modules are implemented at the API level but not yet wired to the UI:
- **Cookie transfer button** — the snapshot/apply pipeline exists but no UI button to trigger it.
- **Pairing flow screens** — QR generation/scanning + SAS verification UI not yet built.
- **Recovery onboarding** — the BIP-39 generator + validator exists but no first-run onboarding flow.
- **Kill switch settings** — the state machine exists but no Settings screen to trigger it.
- **Relay client wired to UI** — OkHttp client built, but not connected to the foreground sync service.

Other limitations (planned for v1.0.0):
- IndexedDB / ServiceWorker transfer deferred to Phase 2.
- Banking / fintech / government sites will not work — device-binding exists to protect the user's money.
- No background sync in v1 — transfers are manual.
- No quantum-safe pairing — X25519 ECDH is not post-quantum. Hybrid PQXDH is Phase 3.

## Documentation

The full planning, council iterations, threat model, survivor UX spec, and ops notes live in the private `AutomatedLabor/tote-docs` repo. The publicly-visible artifacts in this repo are:

- `README.md` — this file.
- `AGENTS.md` — operating instructions for any AI agent pointed at this repo.
- `SECURITY.md` — security policy.
- `LICENSE` — MIT.
- `CHANGELOG.md` — release history.
- `.github/workflows/` — active CI: build (compile + unit test + sign + verify + SLSA + release), gitleaks, osv-scanner, codeql.
- `docs/README.md` — pointer to the private docs repo.

## Releases

Two draft pre-releases live on GitHub Releases:

- **v0.1.0-alpha1** — first signed APK. Crypto module + Android foundation + 67 unit tests. https://github.com/AutomatedLabor/tote/releases/tag/v0.1.0-alpha1
- **v0.2.0-alpha1** — full crypto + cookie transfer + relay + pairing + recovery + kill switch + 112 unit tests. https://github.com/AutomatedLabor/tote/releases/tag/v0.2.0-alpha1

All releases:
- APK file + `.sha256` sidecar attached to GitHub Releases.
- Code-signed v1+v2 (per build-learnings headline lesson #16 — `apksigner verify`, NOT `keytool -printcert`).
- SLSA build provenance attestation (best-effort).
- Council iter-N sign-off before v1.0.0 ship (alpha releases are exempt).

## Install (alpha)

1. Download `app-release.apk` AND `app-release.apk.sha256` from the [Releases page](https://github.com/AutomatedLabor/tote/releases) (you need collaborator access to see drafts).
2. Verify integrity: `sha256sum -c app-release.apk.sha256` → `OK`.
3. Verify signature (optional but recommended):
   ```bash
   BT=$(ls $ANDROID_HOME/build-tools/ | sort -V | tail -1)
   $ANDROID_HOME/build-tools/$BT/apksigner verify --print-certs --verbose app-release.apk
   # Expect: Verifies
   # V2 Signer: certificate DN: CN=Tote Release, O=AutomatedLabor, C=US
   # V2 Signer: certificate SHA-256 digest: df96cae5c963cef4b2adccd6ccf3a76113a0386e2376232f3dc758374acf4196
   ```
4. Install on an Android 8.0+ device (minSdk 26), enabling install-from-unknown-sources for your browser/file manager if prompted.
5. **The app is at "build-pipeline milestone" stage.** It launches, but cookie transfer + pairing + recovery + kill switch are not yet wired to the UI. Use it to verify the build pipeline works end-to-end.

## Operating manual for AI agents

If you are an AI agent pointed at this repo, read [`AGENTS.md`](AGENTS.md) first, then follow the build-learnings operating manual referenced there. The master work list at `tote-docs/ops/WORK-PROGRESS.md` has the current batch + task-in-progress + CI status — read its status block at the top to know where to resume if the sandbox dies.

## Related repos

| Repo | Visibility | Purpose |
|---|---|---|
| `AutomatedLabor/tote` | public | This repo. Android Kotlin codebase. GitHub Actions for APK build & test. |
| `AutomatedLabor/tote-docs` | private | Planning, council iterations, threat model, survivor UX, ops notes, LOG.md, WORK-PROGRESS.md. |
| `AutomatedLabor/tote-secrets` | private | Keystore custody (release keystore base64-encoded + keystore-info.txt + custody README). |
| `AutomatedLabor/tote-relay` | TBD | Go relay binary. Self-hostable. |
| `AutomatedLabor/build-learnings` | private | Cross-project institutional memory. Operating manual. |

## License

MIT. See [LICENSE](LICENSE).

## Maintained by

The AutomatedLabor org, as an AI-driven build loop. The first session that established this repo's structure is logged in `AutomatedLabor/tote-docs/LOG.md` entry `[2026-11-10T15:20:00+08:00] session-1`. Sessions 1–12 are logged; the latest is `[2026-11-10T23:40:00+08:00] session-12` (v0.2.0-alpha1 shipped).

---

*Last updated: 2026-11-10 — v0.2.0-alpha1 shipped. 112 unit tests passing. APK code-signed and verified. Cookie transfer + pairing + recovery + kill switch modules built and tested at the API level; UI wiring is the next batch.*
