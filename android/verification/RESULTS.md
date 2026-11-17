# Observed verification — 17 November 2026

Scope: the team lead's reduced same-day delivery, on `frontend`.

- `gradlew.bat assembleDebug testDebugUnitTest lintDebug --console=plain`: BUILD SUCCESSFUL;
  final source check 33 seconds. 18 JVM tests, zero failures. Lint passed with warnings.
- Earlier WP4 device run: three Compose UI tests passed for direction, search-empty
  preservation and stage selection. The full device suite was not repeated after integration.
- Installed and launched the integrated debug APK on Librarian_Phone, Android API36,
  360dp-wide emulator, against the actual local demo API.
- Rider: browsed live ranges; explicitly consented, selected route237, reviewed and saved.
  Observed Queued then Synced. A later report automatically synced without manual Sync now.
- Undo: confirmed in Activity; state became Deleted; server reports count returned to zero
  and a retraction marker was retained.
- A later malformed server address was refused; the previous working server URL remained saved.
- Enumerator: role sign-in, Muthaiga/237/townbound session; three arrivals with crowding
  uploaded. Server query confirmed three arrivals and a closed session. Three missing
  departures were marked unobserved, without inventing departure events.
- Crew: crew sign-in and route237/60-minute view; Muthaiga showed one report, limited status,
  standing crowding and 13 arrivals, matching the API after refresh. The fixed disclaimer
  remained visible. Counts change as the live time window moves.
- Language: English → Kiswahili → English changed the screen and navigation labels without
  restart. Kiswahili is explicitly marked as needing fluent-speaker review.
- Deletion: confirmed deletion; consent became off, Activity emptied, server acknowledged
  one removed report, and the server's report count became zero.
- Wait alert: granted permission in context; enabled a threshold60 test. A real notification
  arrived without forcing a scheduler job. Tapping it opened Muthaiga Stage. Unit coverage
  checks expiration and prevents an old worker from finishing a replacement alert.
- Fixed during smoke: tab navigation restoring the wrong nested screen; permission launcher
  ownership under a localized context; local-debug workers blocked by Android's public
  Internet-validation requirement; delayed uploads behind an old retry; compact crew layout.

All observations are synthetic pilot/test data. No credentials appear in the screenshots.
See final/ for actual light/dark screens, and ../DEMO.md for the walkthrough.

## Deliberately unverified under reduced scope

Full accessibility/TalkBack/200%-font/rotation/width matrix; complete offline and process-death
matrix; physical phones; release/minification runtime; two-hour wall-clock alert endurance;
field accuracy; fluent review of Kiswahili; Docker build and public tunnel. WorkManager
delivery may be delayed by Android. These are not claimed as passed.
