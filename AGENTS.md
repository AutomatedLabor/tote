# AGENTS.md - Operating Instructions for AI Agents

> **If you are an AI agent pointed at this repo, read this file first, then follow the build-learnings operating manual referenced below.**

## Step 0 - Read the iron rules

You were handed a task prompt, a secret (PAT or similar), and the instruction: "go read the repo's README and follow it." The iron rules are non-negotiable. They take 30 seconds and every one was paid for in real failures.

Source: [`AutomatedLabor/build-learnings`](https://github.com/AutomatedLabor/build-learnings) README.md §"The iron rules". If you don't have access, the digest is in `AutomatedLabor/tote-docs/conventions/build-learnings-quick-ref.md`.

**The most important rules for this repo:**
- **Never paste a live token into chat, file content, or commit.** Use `<PAT>` placeholder; resolve at runtime via env var. If you've leaked it, tell the user to revoke immediately.
- **A blank sandbox is not your sandbox.** Probe, then bootstrap. Provisioning is part of the prompt.
- **Green CI != working app.** Tests validate the function; they don't validate the wiring.
- **Registering a cron != executing it.** After every `cron create`, immediately run the first iteration of that cron's purpose synchronously in this chat.
- **Don't go silent.** The user can't see your tool calls or thinking. After each non-trivial step, write one or two sentences saying what just happened.
- **Claim only what you can show.** If you can't point to output - a pushed commit, a file in `/home/z/my-project/download/`, a `cron list` entry - you didn't do it.
- **Hash match != installable; use the canonical verification tool.** `apksigner verify --print-certs --verbose`, NOT `keytool -printcert -jarfile`.
- **Folder sovereignty.** Each project owns its own folder. Never modify another project's folder in `build-learnings`.

## Step 1 - Bootstrap your sandbox

Your sandbox may be blank OR may have stale state from a previous session. Do not assume.

1. **Probe what's there.** `which git python3 node gh apksigner gradle`. Do not assume any tool is installed.
2. **Resolve the secret at runtime.** Use env vars. Never echo, never commit, never log, never paste into a script file you save to disk. If the prompt contains a plaintext PAT, treat it as already-leaked and tell the user to revoke and rotate.
3. **PAT validity probe.** `curl -s -o /dev/null -w "%{http_code}" -H "Authorization: token $GH_TOKEN" https://api.github.com/user` - must return `200`.
4. **Clone the repos you'll need** into `/home/z/my-project/`. Set `git config user.name` / `user.email` per-repo so commits don't fail. Always `git fetch origin && git pull --rebase origin main` before acting.
5. **Read `tote-docs/ops/WORK-PROGRESS.md`** — the status block at the top tells you the current batch, task in progress, CI SHA, and next action. This is the recovery point if the sandbox dies mid-session.
6. **Document any missing tool with a fallback.** Example: "apksigner unavailable - APK signature verification reduced to `unzip -t` + `sha256sum` against the published sidecar." Tell the user what reduced-scope behavior you accepted; do not silently skip verification.

## Step 2 - Read the docs repo before writing code

This repo (`AutomatedLabor/tote`) holds the code. The docs repo (`AutomatedLabor/tote-docs`, private) holds the planning, council iterations, threat model, survivor UX, and ops notes.

**Before writing any production code, read (in this order):**

1. `tote-docs/README.md` - project overview.
2. `tote-docs/LOG.md` - session log (so you know what's been done — sessions 1-12 logged as of v0.2.0-alpha1).
3. `tote-docs/ops/WORK-PROGRESS.md` - master work list with status block at top (current batch + task-in-progress + CI SHA).
4. `tote-docs/planning/SYNTHESIS.md` - the unified plan.
5. `tote-docs/planning/ROADMAP.md` - the phased plan.
6. `tote-docs/planning/DECISIONS.md` - the architecture decision records (ADRs).
7. `tote-docs/THREAT-MODEL.md` - the threat model and defenses.
8. `tote-docs/SURVIVOR-UX.md` - the worst-day UX spec (mandatory v1 deliverable).
9. `tote-docs/planning/COOKIE-FIDELITY-SPIKE.md` - the major implementation risk.
10. `tote-docs/planning/CRYPTO-SPEC-FINAL.md` - the crypto spec (all 7 conditions resolved).
11. `tote-docs/research/council-*.md` - the three council outputs (verbatim from session-1).
12. `tote-docs/council-iter-1/REPORT.md` - the council findings and verdict.

If the docs repo is not accessible, ask the principal. Do not invent the architecture from scratch.

## Step 3 - Current state (as of v0.2.0-alpha1)

**v0.2.0-alpha1 is shipped** — APK on GitHub Releases (draft), 112 unit tests passing, all crypto + cookie transfer + relay + pairing + recovery + kill switch modules built and tested at the API level.

What works:
- Crypto module (lazysodium-backed): Ed25519, X25519, XChaCha20-Poly1305, HKDF-SHA256, epoch ratchet Variant B
- Cookie snapshot pipeline (SQLite + OSCrypt fallback)
- Cookie importer (CookieManager.setCookie per host)
- Relay client (OkHttp + WebSocket + PoW + rate limiter)
- BIP-39 mnemonic (full spec, 2048 words, checksum)
- Pairing handshake (QR + ECDH + SAS)
- Recovery code (12-word mnemonic + rotate)
- Kill switch (nuke + duress state machine)
- Android foundation (ToteApplication, BrowserScreen, WebViewPool, DeviceTierHelper, ToteMemoryObserver)

What does NOT work yet (the next batches):
- UI wiring for cookie transfer button
- Pairing flow screens (QR + SAS)
- Recovery onboarding flow
- Kill switch settings screen
- Relay client wired to foreground sync service
- Truly-live tabs polish (screenshot, scrollY, form state, saveState)
- Theme + adaptive launcher icon
- Accessibility
- Empty/error/loading states
- First-run onboarding tour

## Step 4 - The build contract

When writing production code:

1. **Load the matching Skill first.** `Skill(command="docx")` / `pdf` / `pptx` / `xlsx` / `charts` / `fullstack-dev` - **before** writing content or delegating to a subagent. Skills encode rules you cannot guess.
2. **Council review before shipping anything high-stakes** - synchronous, in this chat, never scheduled.
3. **Sanitize secrets before any commit/push.** Scan your work for the live PAT string. Replace with `<PAT>` placeholder.
4. **Verify the deliverable by reopening it, not by trusting the script.** Render docx/pdf/pptx to PDF -> JPEG pages and look at them. For APKs, use `apksigner verify --print-certs --verbose`, NOT `keytool -printcert`.
5. **Register the recurring crons AND execute their first iteration synchronously.** Per iron rule #13/#14.
6. **Preflight before push.** Before committing any change to `build-learnings`, verify the diff is scoped to `projects/tote/` only.

## Step 5 - The tech stack (locked per ADR-007)

- **Language:** Kotlin (Android). iOS deferred.
- **Shared module:** Plain Kotlin for now (KMP-extractable). No platform WebView types across the bridge.
- **WebView:** `android.webkit.WebView` + `androidx.webkit.*` directly. No wrapper library.
- **Crypto:** libsodium via `lazysodium-java` 5.1.4 (JNA bindings). Ed25519 + X25519 ECDH + XChaCha20-Poly1305-IETF + HKDF-SHA256.
- **HTTP:** OkHttp 4.12.0 (relay client + WebSocket).
- **Serialization:** kotlinx.serialization (JSON + CBOR).
- **Long-term pairing key:** Ed25519 in AndroidKeyStore (`setIsStrongBoxBacked` where supported). Note: Ed25519 can't live in iOS Secure Enclave (P-256 only) — iOS deferred.
- **Relay:** Go. Single static binary. Embedded SQLite. (Separate `tote-relay` repo, planned public.)
- **CI:** GitHub Actions (build, codeql, gitleaks, osv-scanner, markdown-check). All blocking on PR.
- **Min SDK:** 26 (Android 8.0+).
- **Target SDK:** 34 (Android 14).
- **Compose Compiler:** 1.5.14 (paired with Kotlin 1.9.24 — `composeOptions` not the `org.jetbrains.kotlin.plugin.compose` plugin, which requires Kotlin 2.0+).

## Step 6 - The cron heartbeat (post-v1.0.0)

Once v1.0.0 ships, register the standard 4 crons (A=verification, B=build trigger, C=council, D=security scan). Plus Job E (user-side watchdog) which the principal controls.

**The first iteration of EACH cron must run synchronously in the chat session that registers it.** Per iron rule #13/#14: register != execute. After every `cron create`, immediately run the first iteration in-chat, then verify via `cron list`, then say "the recurring loop is in place."

Until v1.0.0 ships, no crons are registered. The `WORK-PROGRESS.md` file in `tote-docs/ops/` is the recovery point — any cron-fired agent reading it knows the current batch + task-in-progress.

## Step 7 - Reporting back

When done, summarize what you built in <= 100 words, name the deliverable files in `/home/z/my-project/download/` (the only user-visible directory), and surface anything the user needs to do (PAT rotation, field test, kill switch, manual step). Do not end with "let me know if you need anything else" - propose concrete next steps.

**Distinguish what you did in this session** (visible: commits pushed, files written, subagent results reported in chat) **from what's scheduled** (the recurring crons). Never blur the two. If the user can't see the work, it didn't happen.

## See also

- `tote-docs/conventions/build-learnings-quick-ref.md` - the digest of build-learnings rules.
- `tote-docs/LOG.md` - the running session log (sessions 1-12 logged).
- `tote-docs/ops/WORK-PROGRESS.md` - the master work list with status block.
- `tote-docs/planning/ROADMAP.md` - the phased plan.
- `tote-docs/council-iter-1/REPORT.md` - the council findings.
- `tote-docs/council-final/ELITE-COUNCIL-FINAL-GATE.md` - the final-gate sign-off.
