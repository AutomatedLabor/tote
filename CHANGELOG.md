# Changelog

All notable changes to Tote will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Repo created (2026-11-10). Planning phase. No code yet.
- Council iter-1 (architecture + threat-model audit, pre-code) completed.
- 9 ADRs (`tote-docs/planning/DECISIONS.md`).
- Threat model (`tote-docs/THREAT-MODEL.md`).
- Survivor UX spec (`tote-docs/SURVIVOR-UX.md`).
- Cookie fidelity spike plan (`tote-docs/planning/COOKIE-FIDELITY-SPIKE.md`).
- Ops stubs (`tote-docs/ops/`).
- CI workflows (`.github/workflows/`): build (placeholder), codeql, gitleaks, osv-scanner, markdown-check.

### Pending (Phase 0)
- 6 spike reports (`tote-docs/planning/spikes/`).
- Phase 0 exit criteria: all critical spikes pass; architecture confirmed buildable.

### Pending (Phase 1)
- Multi-profile WebView shell.
- Per-profile cookie SQLite export + import.
- Pairing via QR + 6-digit SAS + emoji pair.
- Move verb (manual transfer).
- Self-hostable Go relay (`tote-relay` repo, planned).
- LAN-direct fallback.
- 24-word recovery code.
- Survivor UX (lost phone, deceased user, both phones lost).
- Kill switch.
- First release: `v1.0.0`.

## [v0.0.0-pre] - 2026-11-10

Initial commit. Planning phase. No code.
