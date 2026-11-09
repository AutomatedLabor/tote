# Parked workflows (pending Phase 1)

These workflows are inactive. They are NOT in `.github/workflows/` so GitHub Actions does not pick them up. They live here, in version control, so they're ready to enable when Phase 1 begins and the `app/` module exists.

## Why parked

- `codeql.yml` requires Kotlin/Java source code to analyze. With no `app/` module yet, CodeQL fails with "did not detect any code written in languages supported." Job-level `if:` guards work but GitHub marks the overall workflow as "failure" because zero jobs ran, which pollutes the green-tick signal.
- `osv-scanner.yml` requires a lockfile (`gradle.lockfile` or `package-lock.json`) to scan. With no dependencies yet, the scan would either fail or no-op.

## How to enable (Phase 1 kickoff)

When Phase 1 starts and `app/` exists with actual Kotlin source + `gradle.lockfile`:

```
git mv .github/workflows-pending/codeql.yml .github/workflows/codeql.yml
git mv .github/workflows-pending/osv-scanner.yml .github/workflows/osv-scanner.yml
git commit -m "phase-1: enable codeql + osv-scanner workflows (app/ module exists)"
git push
```

The workflows will then trigger on push/PR/schedule as configured.
