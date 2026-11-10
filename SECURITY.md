# Security Policy

## Supported version

(No releases yet. The first release will be `v1.0.0` once Phase 1 implementation completes.)

When releases exist, only the latest minor release is supported. Old versions are not patched unless a critical vulnerability (RCE, auth bypass, key compromise) is found.

## Reporting a vulnerability

Please use **GitHub's private vulnerability reporting** on this repository (Security tab -> "Report a vulnerability"). Do **not** open a public issue for anything that touches:

- The crypto path (planned: `app/src/main/kotlin/com/tote/app/crypto/`).
- Key custody / device identity / pairing.
- Cookie import / export fidelity (especially the SQLite snapshot path).
- The relay protocol (planned: separate `tote-relay` repo).
- The survivor / kill-switch / recovery flows.

## Response expectations

This is an **AI-driven build loop** maintained by the AutomatedLabor org. Response is best-effort by the maintainer. Issues in the crypto or key custody path will be triaged before any release; they will not be silently ignored, but expect delays of days to weeks depending on complexity.

If you're reporting a critical vulnerability (key compromise, RCE, auth bypass), expect a response within 48h acknowledging receipt, and a fix-or-mitigation plan within 7 days.

## Known deferred risks (accepted at ship, documented, not secret)

These will be recorded here once v1.0.0 ships, so nobody has to rediscover them. The categories that will be documented:

1. **Not hardened against state-actor adversaries.** Pairing ECDH (X25519) is not post-quantum; hybrid PQXDH is Phase 3.
2. **IndexedDB / ServiceWorker not transferred in v1.** Sites using IDB-based auth will require re-login on destination.
3. **iOS partitioned (CHIPS) cookies not round-trippable in v1.** `NSHTTPCookie` has no `Partitioned` field.
4. **Banking / fintech / government sites won't work** - device-binding exists to protect the user's money. We do not forge TLS to defeat it.
5. **No MLS (RFC 9420)** for >2-device fan-out in v1. The epoch ratchet (ADR-005) supports 2-device; Phase 3 adds MLS for more.

## Anti-fraud bright line

Tote moves your own sessions across your own devices. We do not forge TLS/JA3/JA4 fingerprints, HTTP/2 settings, TCP-level signals, or canvas/WebGL output for the purpose of evading fraud systems. This is a non-negotiable product decision (see `tote-docs/planning/DECISIONS.md` ADR-006).

If you're reporting a vulnerability that requires us to forge TLS to "make a site work," we will decline. The fix is honest communication with the user (warn before transfer that the site is device-bound), not evasion.

## Security scan posture

**Active CI scans (current):**
- **gitleaks** scans every PR and push for leaked secrets. 16 rules covering GitHub PATs (all prefixes: `ghp_`, `github_pat_`, `gho_`, `ghs_`, `ghr_`, `ghu_`), AWS (`AKIA`), Stripe (`sk_live_`/`rk_live_`), Slack (`xox`), PEM private keys, Fly.io, Render, Vercel, and Tote-specific patterns. See `.gitleaks.toml`.
- **markdown-check** lints and link-checks all markdown on PR/push.
- **CodeQL** is active (`.github/workflows/codeql.yml`). Runs when Kotlin source + Gradle wrapper jar exist; gracefully skips (via `check-prerequisites` + `skip-notice` jobs) until prerequisites are met. Currently in skip mode (no `gradle-wrapper.jar`-based autobuild yet — will activate when the wrapper is committed).
- **OSV-Scanner** is active (`.github/workflows/osv-scanner.yml`). Runs when a lockfile (`gradle.lockfile` or `package-lock.json`) exists; gracefully skips until then.
- An `assert-no-app-without-scanners` guard in `build.yml` fails the build if `app/` exists but either scanner is still in `.github/workflows-pending/`. (Scanners were activated in commit `104cd44` when `app/` landed.)

**Job D (security scan cron)** will run daily once Phase 1 ships, re-scanning full repo history and latest dependencies.
