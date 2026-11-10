# Tote

**A multi-profile mobile browser that lets you transfer logged-in web sessions between your own devices, end-to-end encrypted. WhatsApp Linked Devices, for web sessions, on mobile.**

You log into a grocery web app on Phone A. 5 minutes later (or 5 weeks later) you want that same logged-in session on Phone B. Tote transfers the session - cookies and all - via an E2E-encrypted relay. No Playwright. No Chromium fork. Just a WebView-based mobile app that owns its cookie jar.

**There is no vendor account. There is no cloud copy of your sessions. The relay cannot read your data.**

If the user loses a phone, they revoke its access from a paired device. If both phones are lost, they recover from a 24-word code. The product survives worst-day-of-their-life scenarios.

## Status: PLANNING (v0.0.0-pre)

Tote is in the research & design phase. No code has shipped. The first Council iteration (architecture + threat-model audit, pre-code) is complete; the next milestone is the Phase 0 spike (de-risk the technical unknowns before writing production code).

- **Working name:** Tote (placeholder; subject to user testing).
- **One-line pitch:** WhatsApp Linked Devices, for web sessions, on mobile.
- **Anti-fraud bright line (non-negotiable):** we move your own sessions across your own devices; we do not forge TLS fingerprints or defeat fraud systems.

## Documentation

The full planning, council iterations, threat model, survivor UX spec, and ops notes live in the private `AutomatedLabor/tote-docs` repo. The publicly-visible artifacts in this repo are:

- `README.md` - this file.
- `AGENTS.md` - operating instructions for any AI agent pointed at this repo.
- `SECURITY.md` - security policy.
- `LICENSE` - MIT.
- `CHANGELOG.md` - release history (empty until v1.0.0 ships).
- `.github/workflows/` - active CI: build (placeholder until app/ exists), gitleaks (secret scan), markdown-check (lint+link). CodeQL + OSV-Scanner are parked in `.github/workflows-pending/` (activate when app/ + gradle.lockfile exist; see `workflows-pending/README.md`).
- `docs/README.md` - pointer to the private docs repo.

## What works (planned for v1.0.0)

(No code yet — planning phase. The repo currently contains docs, CI scaffolding, and the release keystore (gitignored). First Kotlin lands in Phase 1.)

- **Multi-profile WebView shell** (Android-first, iOS 17+ secondary). Each profile = isolated cookie jar + localStorage + IndexedDB. Color-coded. Switch instantly.
- **Per-profile cookie SQLite export + import** - full attribute fidelity (Secure, HttpOnly, SameSite, Partitioned, etc.).
- **Pairing via QR + 6-digit SAS + emoji pair** - proximity-anchored first pairing, then remote transfer possible.
- **Move verb** - copy a session from Phone A to Phone B, optionally wipe Phone A.
- **Self-hostable Go relay** (separate repo `AutomatedLabor/tote-relay`, planned) - dumb ciphertext mailbox. Open source.
- **LAN-direct fallback** - if the relay is down or you don't trust it, transfer over local Wi-Fi.
- **Curated domain compatibility registry** - warn before transferring to known device-binding domains (banking, fintech, government).
- **Soft fingerprint continuity** - copy UA, locale, timezone, screen class to destination by default.
- **24-word recovery code** - BIP-39; re-derives the pairing root if both phones are lost.
- **Survivor UX** - lost phone revocation, deceased-user shutdown mode (see `tote-docs/SURVIVOR-UX.md`).
- **Kill switch** - irreversible nuke; revokes all devices, burns recovery code.

## Known limitations (planned for v1.0.0, all accepted by council iter-1)

- **IndexedDB / ServiceWorker transfer deferred** to Phase 2+. v1 transfers cookies (full fidelity) and localStorage (best-effort for visited origins only). IndexedDB-reliant auth flows won't transfer.
- **Partitioned (CHIPS) cookies on iOS** - `NSHTTPCookie` has no `Partitioned` field. iOS v1 will silently drop the partition attribute. Android preserves it via SQLite snapshot.
- **Banking / fintech / government sites will not work** - device-binding exists to protect the user's money. We do not forge TLS/JA3 to defeat it. We warn before transfer and explain after.
- **iOS below 17** - degrades to single-profile mode.
- **No background sync in v1** - transfers are manual (Move verb). Mirror (continuous sync) and Lend (TTL-baked transfer) are Phase 2.
- **No quantum-safe pairing** - the X25519 ECDH is not post-quantum. Hybrid PQXDH is Phase 3.

## Council

Per the build contract, all releases require multi-persona Council review. The first iteration (iter-1, pre-code architecture + threat-model audit) is complete; records in `AutomatedLabor/tote-docs/council-iter-1/`.

The Council pattern requires:
- **Pre-ship blocking review** - synchronous, in the chat that ships. Block until all P0s pass.
- **Recurring supervision** (asynchronous, via cron Job C, scheduled ~24h once Phase 1 ships).

Minimum personas: Principal Security Engineer, Principal Mobile / Platform Architect, Principal SRE & QA, Red Team / Adversary, User Advocate / Survivor, Product.

## Releases

(No releases yet. The first release will be `v1.0.0`, targeted after Phase 1 implementation completes.)

All releases will be attached to GitHub Releases with:
- The APK file.
- A `.sha256` sidecar.
- SLSA build provenance (per threshold v1.0.1+ pattern).
- Code-signed v1+v2+v3 (per build-learnings headline lesson #16 - `apksigner verify`, NOT `keytool -printcert`).
- Council iter-N sign-off before release.

## Install (once v1.0.0 ships)

1. Download `tote-v1.0.0.apk` AND `tote-v1.0.0.apk.sha256` from the release page.
2. Verify integrity and signing (instructions TBD).
3. Install on an Android 8.0+ device (minSdk 26), enabling install-from-unknown-sources for your browser/file manager if prompted.
4. On first launch, complete onboarding. **Write down the 24-word recovery code when shown** - the app refuses to complete first-pairing until backup is confirmed.

## Operating manual for AI agents

If you are an AI agent pointed at this repo, read [`AGENTS.md`](AGENTS.md) first, then follow the build-learnings operating manual referenced there.

## Related repos

| Repo | Visibility | Purpose |
|---|---|---|
| `AutomatedLabor/tote` | public | This repo. Android Kotlin codebase. GitHub Actions for APK build & test. |
| `AutomatedLabor/tote-docs` | private | Planning, council iterations, threat model, survivor UX, ops notes. |
| `AutomatedLabor/tote-relay` | TBD (planned public) | The Go relay binary. Self-hostable. |
| `AutomatedLabor/build-learnings` | private | Cross-project institutional memory. Operating manual. |

## License

MIT. See [LICENSE](LICENSE).

## Maintained by

The AutomatedLabor org, as an AI-driven build loop. The first session that established this repo's structure is logged in `AutomatedLabor/tote-docs/LOG.md` entry `[2026-11-10T15:20:00+08:00] session-1`.

---

*Last updated: 2026-11-10 - repo created; planning phase; no code yet.*
