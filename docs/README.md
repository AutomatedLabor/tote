# Tote - Docs (pointer)

The full documentation for Tote lives in the **private** `AutomatedLabor/tote-docs` repository. This `docs/` folder is a public pointer - if you have access to the private docs repo, the canonical docs are there, not here.

## Why split?

Per the build-learnings convention (mirroring `threshold` + `threshold-docs`):
- **Public code repo** (this one) - the codebase. Anyone can read, audit, build, contribute.
- **Private docs repo** (`tote-docs`) - planning, council iterations, threat model, survivor UX, ops notes. May reference security findings, PAT-handling mistakes, incident details that should not be public.

The public repo is intentionally lean on planning docs. Anyone landing here should:
1. Read `README.md` to understand what Tote is.
2. Read `AGENTS.md` if they're an AI agent about to work on the code.
3. Read `SECURITY.md` if they're reporting a vulnerability.
4. Read `CHANGELOG.md` for release history.
5. Browse `.github/workflows/` for CI configuration.

If you need the planning docs, council findings, threat model, or survivor UX spec, request access to `AutomatedLabor/tote-docs` from the principal.

## What's NOT in the public repo (by design)

- Council iteration transcripts (may contain security reasoning).
- Threat model details (could aid attackers if specific).
- Survivor UX spec (contains recovery code procedures).
- Cookie fidelity spike plan (contains implementation details of the SQLite snapshot path).
- Anti-fraud registry plans (contains the list of sites that will be warned).
- Ops notes (release schedule, cron registry, kill switch procedures).

The public repo is the code; the private repo is the why.

## Build status

(No code yet. CI workflows are placeholders until Phase 1 implementation begins.)
