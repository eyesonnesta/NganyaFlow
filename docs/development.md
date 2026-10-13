# Development roadmap

## Current and proposed structure

Current: `README.md`, `docs/`, `design/`, licence and contribution guidance. Proposed future directories are `android/`, `backend/`, `ml/` and `tests/`; they are not implemented or runnable in this change.

| Weeks | Work | Exit evidence |
| --- | --- | --- |
| 1-2 | Confirm corridor, field permissions, schemas and UI | Approved instruments; verified registry; reviewed prototype |
| 3-4 | Build Android shell and durable reporting; collect observations | Tested offline save/retry; coverage log and quality report |
| 5-6 | Train baselines/QRF and freeze display policy | Reproducible split manifest, baseline metrics and calibration |
| 7-8 | Locked evaluation, usability and documentation | Test report, known limitations and demonstrable pilot build |

This is a proposed eight-week schedule. Inadequate data or missing permissions changes the schedule; model claims must not outrun the evidence.

## First implementation pull request

Create an Android Studio Kotlin/Compose project with a pinned Gradle wrapper, dependency catalogue, package name and agreed SDK levels. Add a single Activity, MaterialTheme, navigation and typed UI state. Record exact toolchain and build/test commands only after validating them. A mock repository should label demo data; never silently switch it into production.

## Verification before pilot release

- Rider tasks: stage/direction selection, consent declined, permission denied, route chosen explicitly, cancel, persistent correction and deletion status.
- Offline integrity: process death during save/upload, duplicate retry, server-accepted timeout, reconnect, full storage, clock drift, revoked access and deletion before retry.
- Accessibility: TalkBack labels and focus, 200% font scaling, small screens, light/dark contrast, 48dp touch targets, reduced motion and reviewed translation.
- Enumerator integrity: simultaneous open vehicles, missing departure, pause/resume coverage, event correction and invalid time order.
- ML: chronological grouped splits, no future information, interval coverage, stage holdout, reporting bias and abstention rate.
- Security: server-side roles, no embedded secrets, minimal permissions and restricted raw data.

Use appropriate unit, integration, instrumented and manual tests as implementation arrives. No Android or ML tests can be run against a documentation-only repository. Documentation verification checks local links, JSON, source consistency and exported document pagination.
