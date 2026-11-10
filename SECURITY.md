# Security Policy

## Supported versions

| Version | Supported | Status |
|---|---|---|
| `v0.2.0-alpha1` | ✅ alpha (latest) | Cookie transfer + relay + pairing + recovery + kill switch modules built and tested at the API level. UI wiring is the next batch. |
| `v0.1.0-alpha1` | ✅ alpha (deprecated) | First signed APK. Build-pipeline milestone. |
| `v0.0.0-pre` | ❌ | Planning phase, no code. |

Only the latest alpha release is supported. Old versions are not patched unless a critical vulnerability (RCE, auth bypass, key compromise) is found.

## Reporting a vulnerability

Please use **GitHub's private vulnerability reporting** on this repository (Security tab → "Report a vulnerability"). Do **not** open a public issue for anything that touches:

- The crypto path (`app/src/main/kotlin/com/tote/app/crypto/`).
- Key custody / device identity / pairing.
- Cookie import / export fidelity (especially the SQLite snapshot path).
- The relay protocol (separate `tote-relay` repo).
- The survivor / kill-switch / recovery flows.

## Response expectations

This is an **AI-driven build loop** maintained by the AutomatedLabor org. Response is best-effort by the maintainer. Issues in the crypto or key custody path will be triaged before any release; they will not be silently ignored, but expect delays of days to weeks depending on complexity.

If you're reporting a critical vulnerability (key compromise, RCE, auth bypass), expect a response within 48h acknowledging receipt, and a fix-or-mitigation plan within 7 days.

## Known deferred risks (accepted at ship, documented, not secret)

1. **Not hardened against state-actor adversaries.** Pairing ECDH (X25519) is not post-quantum; hybrid PQXDH is Phase 3.
2. **IndexedDB / ServiceWorker not transferred in v1.** Sites using IDB-based auth will require re-login on destination.
3. **Banking / fintech / government sites won't work** — device-binding exists to protect the user's money. We do not forge TLS to defeat it.
4. **No MLS (RFC 9420)** for >2-device fan-out in v1. The epoch ratchet (ADR-005) supports 2-device; Phase 3 adds MLS for more.
5. **Recovery code derivation uses HKDF, not Argon2id.** TODO for v1.0.0 ship: switch to `crypto_pwhash` (Argon2id, t=2, m=64MB, p=1) per OWASP. The HKDF derivation is a v1-alpha placeholder; it is NOT brute-force-resistant if the entropy is low.
6. **Keystore password is temporary** (`tote-release-store-pass`). Rotate to a strong random password before v1.0.0 public release. Custody plan: private `tote-secrets` repo now → air-gapped device + physical QR code later.
7. **PAT rotation is the principal's responsibility.** The PAT used to set up CI secrets is in the principal's control. Rotate before v1.0.0 public release.

## Anti-fraud bright line

Tote moves your own sessions across your own devices. We do not forge TLS/JA3/JA4 fingerprints, HTTP/2 settings, TCP-level signals, or canvas/WebGL output for the purpose of evading fraud systems. This is a non-negotiable product decision (see `tote-docs/planning/DECISIONS.md` ADR-006).

If you're reporting a vulnerability that requires us to forge TLS to "make a site work," we will decline. The fix is honest communication with the user (warn before transfer that the site is device-bound), not evasion.

## Crypto primitives in use

All crypto is backed by **libsodium** via the `lazysodium-java` JNA bindings (v5.1.4). Bootstrap is in `app/src/main/kotlin/com/tote/app/crypto/LazysodiumBootstrap.kt`.

| Primitive | Use | Standard |
|---|---|---|
| Ed25519 | Device signing keys (sign + verify) | RFC 8032 |
| X25519 | ECDH for pairing handshake | RFC 7748 |
| XChaCha20-Poly1305-IETF | Session blob encryption (AEAD) | libsodium |
| HKDF-SHA256 | Key derivation (pairing root, epoch keys, pairing keys, recovery) | RFC 5869 |
| SHA-256 | Hashcash PoW, BIP-39 checksum | NIST FIPS 180-4 |
| Argon2id | (TODO v1.0.0) Recovery code derivation | RFC 9106 |

The 67 crypto unit tests include RFC 5869 HKDF test vectors verified against `rfc-editor.org`, BIP-39 official test vectors, sign/verify round-trips, X25519 ECDH symmetry checks, XChaCha20-Poly1305 tamper-detection tests, and 4MB plaintext round-trips.

## Security scan posture

**Active CI scans (current):**
- **gitleaks** scans every PR and push for leaked secrets. 16 rules covering GitHub PATs (all prefixes: `ghp_`, `github_pat_`, `gho_`, `ghs_`, `ghr_`, `ghu_`), AWS (`AKIA`), Stripe (`sk_live_`/`rk_live_`), Slack (`xox`), PEM private keys, Fly.io, Render, Vercel, and Tote-specific patterns. See `.gitleaks.toml`.
- **markdown-check** lints and link-checks all markdown on PR/push.
- **CodeQL** is active (`.github/workflows/codeql.yml`). Runs but currently fails with "no source code seen during build" — known non-blocking issue with the custom fat-jar Gradle wrapper. Will resolve when we ship a stock wrapper.
- **OSV-Scanner** is active (`.github/workflows/osv-scanner.yml`). Runs when a lockfile exists; gracefully skips until then.
- An `assert-no-app-without-scanners` guard in `build.yml` fails the build if `app/` exists but either scanner is still in `.github/workflows-pending/`.

**APK signature verification (per build-learnings headline lesson #16):**
- `apksigner verify --print-certs --verbose` (NOT `keytool -printcert -jarfile`, which is v1-only and false-negatives v2/v3-only APKs).
- Cert SHA-256 baseline: `df96cae5c963cef4b2adccd6ccf3a76113a0386e2376232f3dc758374acf4196`. Hardcoded in `build.yml` — any mismatch fails the build.
- `.sha256` sidecar generated alongside every release APK.

**Job D (security scan cron)** will run daily once v1.0.0 ships, re-scanning full repo history and latest dependencies. Not yet registered (alpha phase).
