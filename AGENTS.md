# Instructions for coding agents

Read `docs/build-plan.md` in full before changing anything. It holds the project context,
the current status, the rules, and the work packages (WP0 to WP10).

- Do one work package at a time and stop for review at the end of each.
- `docs/api.md` is the contract between the Android app and the server.
- The Android app is Kotlin and Jetpack Compose only, in `android/`.
- Report only what you actually ran. Say plainly what you could not run.
- All current data is synthetic. Keep it labelled as such in code, UI and docs.
- Never commit secrets, access codes, tokens or personal data.
- Do not edit `server/src/nganyaflow/ml.py`, `docs/ai-report.md` or `docs/evidence/`
  unless a work package says so.

Backend checks: `pip install -e "server[dev]"`, then `pytest server/tests -q` and
`ruff check server/src server/tests server/scripts`.
