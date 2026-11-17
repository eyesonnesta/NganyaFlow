# NganyaFlow build plan and agent guide

Status date: 17 November 2026. Written for Codex (or any coding agent or teammate) picking
up the project. Read all of Part A before writing code. Part B is the work, split into
packages that are done one at a time.

Contents

- Part A: context
  - A1. What NganyaFlow is
  - A2. Team, assessment and hard requirements
  - A3. Repository map
  - A4. Status: done, needs improvement, not started
  - A5. Backend you are building against
  - A6. Design sources and the visual direction
  - A7. Product rules that must not be broken
- Part B: the work
  - B0. Working rules for the agent
  - B1. Target Android architecture
  - B2. Screen and feature specification
  - B3. Work packages WP0 to WP10
  - B4. Definition of done for the whole project
  - B5. Decisions the team still owes
  - B6. Things that are unverified

---

# Part A: context

## A1. What NganyaFlow is

NganyaFlow is an Android app that tells a commuter roughly how long until the next matatu
at a chosen stage, on a chosen route, in a chosen direction. It shows a **range**
(for example "4–9 min"), never a single promise, and it says "no estimate" when the
evidence is weak. "Nganya" is Nairobi slang for a matatu.

Three kinds of people use the app:

| Role | What they do | Sign-in |
| --- | --- | --- |
| Rider | Pick a stage and direction, compare routes, optionally report "I'm boarding" | None. Anonymous |
| Enumerator | Stand at a stage and record each vehicle's arrival, departure and crowding | Access code |
| Crew | See boarding-report counts and recent activity along their route | Access code |

A Python server stores the observations and serves estimates from a Quantile Regression
Forest (QRF): the 10th, 50th and 90th percentile of the wait. The server refuses to answer
(status `unavailable` plus a reason) when it has fewer than three arrivals in the last
30 minutes, when the model is missing or older than 30 days, or when the question falls
outside what the model was trained on.

It is a research pilot for two course units, so honesty about data matters as much as
features. Every number in the current system is **simulated**. The stage and route names
are real (Digital Matatus 2019 network feed); the arrival times are generated.

## A2. Team, assessment and hard requirements

- Team: Makau (owner of this plan), Karani (backend, ML and docs so far), Obino.
- Assessed under Artificial Intelligence (the model and its evaluation) and Mobile
  Application Development (the Android app).
- Hard requirements from the team lead:
  1. A working mobile application with **all features fully implemented and functional**.
     No placeholder screens, no "coming soon", no buttons that do nothing.
  2. Written in **Kotlin**, developed in **Android Studio**, committed and pushed to GitHub.
  3. It must look **better and more modern than the Figma prototype**.
  4. Estimates come from the forest model on the server. The server starts with seeded
     (synthetic) data so there is something to show.
  5. A test server runs locally (Docker if needed). For the demo it is exposed through a
     short-lived public URL.

## A3. Repository map

Repository: `https://github.com/eyesonnesta/NganyaFlow`

| Branch | Holds | Notes |
| --- | --- | --- |
| `main` | README and MIT licence only | Nothing has been merged into it |
| `docs/material-redesign-ai-concept` | Product, architecture, design-system, privacy and data-dictionary docs; `design/` tokens and screen inventory; concept note | Written before any code existed. Its "proposed API" is superseded by `docs/api.md` |
| `AI-Branch` | `ai/`: public datasets and a pandas cleaning script | Not needed by the app |
| `backend` | `server/` FastAPI app, model pipeline, admin panel, Docker files, `docs/api.md` | The branch you build against |

**Important:** the newest backend commit ("Add roles, rider reports, crew demand, expanded
catalogue and demo server") may not be on GitHub yet. It exists as the patch file
`nganyaflow-backend-update.patch`. WP0 checks for it and applies it. Without it there is
no `docs/api.md`, no access codes, no rider reports, no crew endpoint and only three stages.

Layout on `backend` after the patch:

```
Dockerfile, docker-compose.yml, build-docker.sh, deploy/   container setup
docs/api.md              API contract for the Android app  <- read this
docs/development.md      how to run, seed, train, deploy the server
docs/ai-report.md        the model, its evaluation and limits
docs/build-plan.md       this file
server/src/nganyaflow/   main.py (API), storage.py (SQLite), schemas.py, ml.py (QRF),
                         demo.py (synthetic data + live simulator), access.py, admin*.py, cli.py
server/data/reference/catalog.json   66 stages, 13 routes
server/tests/            34 tests
android/                 does not exist yet. You create it
```

## A4. Status

### Done

| Item | Evidence |
| --- | --- |
| Concept note, product flows, architecture, privacy, data dictionary | Docs branch |
| Two Figma files (original and Material 3 redesign, 41 frames) | Links in A6 |
| FastAPI server: catalogue, sessions, observation sync, estimates | `server/`, tests pass |
| QRF training, temporal evaluation, model activation, admin panel | `server/src/nganyaflow/ml.py`, `/admin` |
| Access codes with enumerator and crew roles, admin page to create and revoke | `/admin/access`, `POST /api/v1/auth/redeem` |
| Rider boarding reports with undo, deletion requests, crew demand view | `docs/api.md` |
| Crowding in estimates, per-stage estimates endpoint, meta endpoint | `docs/api.md` |
| Catalogue of 66 stages with coordinates and 13 routes | `server/data/reference/catalog.json` |
| Synthetic history for every route, `seed-demo` command, live simulator | `server/src/nganyaflow/demo.py` |
| 34 backend tests passing, lint clean on `server/src`, `server/tests`, `server/scripts` | `pytest server/tests -q` |
| Live check: 137 of 143 stage/route/direction groups returned an estimate | Manual run, 16 Nov |

### Needs improvement

| Item | Problem | Where it is fixed |
| --- | --- | --- |
| Backend commit not on GitHub | Push was blocked; exists only as a patch | WP0 |
| Branches never merged | `main` is empty; docs and design live on another branch | WP0 |
| Docker image | `Dockerfile` and `docker-compose.yml` were changed but never built | WP1 |
| Simulator versus a human enumerator | In demo mode the simulator and a real session at the same stage compete; the estimate can flip to "insufficient evidence" or "provenance mismatch" | WP1 |
| Simulator data grows without limit | About 1,700 rows an hour; open sessions are left behind on every restart; estimate queries slow down after days | WP1 |
| Enumerator data model is thinner than the design | No vehicle label, no `standing` crowding level, no way to void a mistaken event | WP1 |
| Code-redeem rate limit | Counts by client address, so behind one tunnel everyone shares a limit | WP1 |
| Kiswahili copy | None exists; needs a human speaker to review | WP6, B5 |
| All accuracy figures | Describe the simulator, not Nairobi. No field data exists | Out of scope; keep the labels honest |

### Not started

- The entire Android app.
- Continuous integration.
- The short-lived public URL (instructions exist in `docs/development.md`).

## A5. Backend you are building against

`docs/api.md` is the contract. Summary of what matters for the app:

- Base path `/api/v1`. JSON, `snake_case`. Times are ISO 8601 with offset; the server
  replies in UTC. Display in Africa/Nairobi.
- Public: `GET /meta`, `GET /stages`, `GET /routes`, `GET /estimates`,
  `GET /stages/{stage_id}/estimates`, `POST /reports`, `DELETE /reports/{id}`,
  `POST /deletion-requests`, `GET /deletion-requests/{id}`, `POST /auth/redeem`.
- Enumerator token: `POST /sessions`, `POST /observations/sync`, `PUT /sessions/{id}/end`.
- Crew token: `GET /demand`.
- Errors: `401` sign in again, `403` wrong role, `404` unknown id, `409` id reused with
  different content, `422` invalid body or time, `429` slow down. Body is `{"detail": ...}`.

Behaviour the app must respect:

1. **Estimates expire fast.** `expires_at` is at most one minute ahead. Refresh the visible
   stage about every 30 seconds and hide numbers once `expires_at` passes.
2. **Unavailable is normal.** `reason` is one of `insufficient_evidence`, `stale_evidence`,
   `model_not_trained`, `model_expired`, `unsupported_training_group`,
   `outside_training_support`, `evidence_provenance_mismatch`. Each needs plain wording.
3. **Idempotent writes.** The phone generates every UUID. Retrying the same id with the
   same content is safe; the server answers with `duplicate_ids`.
4. **Order for enumerators:** create session, then sync observations, then end session.
   Observations must fall inside the session and must not be in the future.
5. **Clock skew.** A phone clock that runs ahead produces `422` on observations. Read
   `server_time` from `/meta`, keep the offset, and correct timestamps with it.
6. **Terminus rule.** `/stages/{id}/estimates` already leaves out directions nobody can
   board (townbound at the town terminus, outbound at the far end). Route `stage_ids` run
   from the town end outward.
7. **Location stays on the phone.** Nearest stage is computed locally from catalogue
   coordinates. No endpoint accepts a location and none may be added.
8. **Provenance.** Estimates carry `data_kind`; `/meta` carries `demo_mode`. When
   `data_kind` is not `field`, the UI shows a "Demo data" label.
9. **Consent version.** Reports must carry the `consent_version` from `/meta` (currently
   `2026-11-v1`) or they are rejected with `422`.

Running the server locally:

```bash
# from the repository root, Python 3.12 or later
python -m venv .venv && . .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -e "server[dev]"
cd server
nganyaflow seed-demo --enumerator-code NF-DEMO-ENUM --crew-code NF-DEMO-CREW   # ~30 s
NGANYAFLOW_DEMO_MODE=1 NGANYAFLOW_WRITE_TOKEN=local-secret \
  uvicorn nganyaflow.main:app --host 0.0.0.0 --port 8000
# checks
curl localhost:8000/health
curl localhost:8000/api/v1/stages/muthaiga/estimates
```

On Windows PowerShell set variables with `$env:NGANYAFLOW_DEMO_MODE = "1"` first.
Interactive API docs are at `/docs`, the admin panel at `/admin` (user `admin`, password
printed at first start and saved in `server/runtime/admin-password.txt`). The Android
emulator reaches the host machine at `http://10.0.2.2:8000`.

## A6. Design sources and the visual direction

| Source | Link | Use |
| --- | --- | --- |
| Material 3 redesign, 41 frames | `https://www.figma.com/design/8gGZPr44bkjNq1zWNM4YYH` | Structure, flows, wording, states. **Baseline to beat** |
| Original prototype | `https://www.figma.com/proto/fahMsqNcPzCHtFN8QmtSoj` | Comparison only |
| `design/tokens.json`, `design/screens.json` | Docs branch (copied in by WP0) | Colours, type scale, frame ids |
| `docs/product.md`, `docs/design-system.md` | Docs branch (copied in by WP0) | Journeys, state table, accessibility rules |

Frames in the redesign: Welcome, Privacy, PrivacyOn, Stage, Stage selected, Stage picker,
Route, Evidence, Routes, Search empty, Boarding, Boarding selected (A and B), Consent gate,
Saved (A and B), Activity, Sync, Synced, Sync failure, Offline, No data, Loading, Location,
Location denied, Settings, Language, Notifications, Data, Delete, Deletion pending, Access,
Setup, Logging, Occupancy, Arrival saved, Departure saved, Summary, Session closed, Crew,
Dark.

What the redesign looks like today: flat pale-blue cards on a near-white surface, one dark
blue pill button per screen, a four-item bottom bar (Stage, Routes, Activity, Settings),
amber notice cards. It is clean but plain: every screen is a stack of identical list rows,
there is no data visualisation, no motion and little hierarchy.

### Brand palette (keep these roles; contrast has been checked)

| Role | Light | Dark |
| --- | --- | --- |
| primary / onPrimary | `#1B4965` / `#FFFFFF` | `#9ACCEB` / `#00344D` |
| primaryContainer / on | `#CAE9F5` / `#092E42` | `#204C63` / `#CAE9F5` |
| secondary / onSecondary | `#526471` / `#FFFFFF` | `#B9C9D5` / `#243440` |
| secondaryContainer / on | `#D6E5F1` / `#10202B` | `#3B4B57` / `#D6E5F1` |
| tertiary / onTertiary | `#9B431E` / `#FFFFFF` | `#FFB596` / `#572008` |
| tertiaryContainer / on | `#FFDBCD` / `#351000` | `#783016` / `#FFDBCD` |
| surface / onSurface | `#F7FAFC` / `#172127` | `#10181D` / `#DFE7ED` |
| surfaceContainerLow / Container / High | `#F0F5F8` / `#EBF1F5` / `#E2EAF0` | `#162027` / `#1C262D` / `#26313A` |
| onSurfaceVariant | `#465660` | `#BCC8D1` |
| outline / outlineVariant | `#6C7D88` / `#BBC9D3` | `#8797A2` / `#3D4C56` |
| error / onError | `#BA1A1A` / `#FFFFFF` | `#FFB4AB` / `#690005` |
| errorContainer / on | `#FFDAD6` / `#410002` | `#93000A` / `#FFDAD6` |
| successContainer / on (custom) | `#D2E8D2` / `#153B1E` | `#193820` / `#B6DDBB` |
| warningContainer / on (custom) | `#FFDEA8` / `#392B0C` | `#49320A` / `#FFDEA8` |

Type scale (Roboto in the mockups): display 36/44, headline 32/40 and 24/32, title 22/28
and 16/24 medium, body 16/24 and 14/20, label 14/20 and 12/16 medium. Spacing base 4 dp;
16 dp screen edges; gaps of 8, 12, 16, 24 dp.

### How to make it better than the Figma

"More modern" here means clearer hierarchy, real data visuals and considered motion. It
does not mean more decoration. Concretely:

1. **Wait range as the hero.** Large numerals for the range, plus a small horizontal range
   bar: a track for 0 to 20+ minutes, a filled band from lower to upper, a tick at the
   median. This replaces three lines of small text.
2. **Chips instead of sentences.** Freshness ("2 min ago"), crowding (icon plus word),
   evidence strength (words, never a percentage), and a "Demo data" chip.
3. **Route identity.** Show the matatu route number in a rounded badge (237, 111, 14A)
   with the corridor name, the way riders actually talk about routes.
4. **Route line diagram.** On route detail, draw the stages as dots on a vertical line with
   the rider's stage highlighted and the direction of travel marked. No map tiles and no
   API key are needed.
5. **One-handed enumerator screen.** Two very large buttons in the thumb zone ("Arrival",
   "Departure"), a live session timer, open vehicles as cards, a haptic tick on each
   record, and an event log that slides up.
6. **Crew view as a comparison.** Horizontal bars per stage for boarding reports, with a
   crowding chip and a "limited data" tag, instead of text rows.
7. **Motion with purpose.** Animated number changes when an estimate refreshes, shared
   bounds from a route card into route detail, skeleton placeholders while loading,
   pull to refresh. All of it off when the system "remove animations" setting is on.
8. **System fit.** Edge-to-edge drawing with correct insets, predictive back, splash
   screen, themed adaptive icon, light and dark themes, optional dynamic colour (off by
   default so the brand palette shows).
9. **Shape and depth.** Larger corner radii on hero cards (28 dp), tonal elevation rather
   than shadows, a bottom sheet for the stage picker instead of a full page.
10. Use the newest **stable** Material 3 Compose library. If it includes the Material 3
    Expressive components, use them where they fit. Do not depend on alpha artefacts.

Keep from the Figma: the wording discipline, the four destinations, the separation of
consent from location, and every state in the product state table.

## A7. Product rules that must not be broken

These come from the concept note and product docs. They are graded.

1. Always a range, never only the median. Say "next vehicle". Never promise a seat.
2. No number when the server says `unavailable` or the estimate has expired.
3. Browsing needs no account, no location and no research consent.
4. Research consent is a separate, explicit, off-by-default choice. Location permission is
   a different switch, asked only at the moment "use my location" is tapped. No background
   location, ever.
5. Boarding report: no route is preselected. The rider picks the route, reviews, confirms.
6. "Saved on this phone" appears only after the local database write succeeds. "Synced"
   appears only after the server acknowledges. They are different states.
7. Undo and deletion are always reachable from Activity. A brief snackbar is never the
   only way to correct something.
8. Enumerators never invent an event. A missing departure is recorded as unobserved.
   Pauses are gaps in coverage, not zero vehicles.
9. Crew figures are app reports, not total passengers. The disclaimer is always visible.
   No hold-or-depart advice.
10. Simulated data is labelled wherever a number from it appears.
11. No secrets, access codes, tokens or personal data in Git or in logs.
12. Minimum permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_COARSE_LOCATION`
    (optional feature), `POST_NOTIFICATIONS` (optional feature). Nothing else without a
    written reason.

---

# Part B: the work

## B0. Working rules for the agent

- Do one work package at a time, in order. Stop at the end of each and report. Do not
  start the next until told.
- Report format at the end of every package: (1) what changed, by file or module;
  (2) commands run and their real results; (3) anything in the package not done, and why;
  (4) screenshots for UI work, light and dark; (5) questions.
- Never report something as working unless you ran it. If you could not run it (no
  emulator, no Docker), say so plainly.
- Kotlin only for the app. Jetpack Compose only for UI; no XML layouts.
- Use current stable versions of the Android Gradle Plugin, Kotlin, Compose BOM and
  libraries, declared in `gradle/libs.versions.toml`. Check the official release pages
  rather than relying on memory; do not use alpha or deprecated artefacts without saying
  so.
- Branch `android` from the prepared `backend` branch (WP0). Small commits with clear
  messages. Never force-push. Never commit to `main` directly.
- Do not change `server/src/nganyaflow/ml.py`, `docs/ai-report.md` or `docs/evidence/`
  unless a package says so. They are Karani's assessed AI work.
- Any backend change comes with a test and an update to `docs/api.md` in the same commit.
- When a requirement here conflicts with the Figma, this document wins. When this document
  is silent, follow `docs/product.md`, then the redesign Figma.
- When blocked or when a decision in B5 is needed, ask. Do not guess silently.

## B1. Target Android architecture

Project in `android/`, single Gradle module `app` to start (split only if build times
demand it). Suggested application id `com.nganyaflow.app` (confirm, B5). `minSdk` 26,
target and compile the current stable API level. Kotlin with coroutines and Flow.

```
android/app/src/main/java/com/nganyaflow/app/
  NganyaFlowApp.kt, MainActivity.kt        single activity, edge to edge
  core/designsystem/   theme, colour roles, type, shapes, StatusColors, shared components
  core/network/        API service, DTOs, auth interceptor, error mapping, clock offset
  core/database/       Room database, entities, DAOs, migrations
  core/datastore/      preferences, install id, consent, token (encrypted)
  core/sync/           outbox, WorkManager workers, sync status
  core/location/       one-shot coarse location, nearest-stage calculation
  core/model/          domain models (Stage, Route, Estimate, Report, ...)
  data/                repositories: Catalogue, Estimates, Reports, Session, Demand, Settings
  feature/onboarding/  welcome, privacy choices
  feature/stage/       stage board, stage picker
  feature/routes/      routes list, route detail, evidence
  feature/boarding/    consent gate, route choice, review, saved
  feature/activity/    contributions, sync details
  feature/settings/    language, appearance, alerts, data and privacy, sync, server, about
  feature/access/      code entry, role landing
  feature/enumerator/  setup, live observation, summary
  feature/crew/        demand view
  feature/alerts/      wait alert worker and notification
```

| Concern | Choice |
| --- | --- |
| UI | Jetpack Compose, Material 3, Navigation Compose with type-safe routes |
| State | One ViewModel per screen exposing an immutable `UiState` through `StateFlow`; one-off events through a channel |
| Dependency injection | Hilt (a hand-written container is acceptable if it keeps the build simpler) |
| HTTP | Retrofit with OkHttp and kotlinx.serialization (or Ktor client; pick one) |
| Local data | Room for catalogue cache, cached estimates, reports, sessions, vehicle events, outbox, deletion requests |
| Preferences | DataStore: install id, consent record, language, theme, selected stage and direction, server address, clock offset |
| Token storage | Encrypted with an Android Keystore key. Check current platform guidance before choosing a library |
| Background | WorkManager: outbox upload with network constraint and exponential backoff; wait-alert checks |
| Location | One-shot coarse fix through the platform or fused provider; result used once, then discarded |
| Time | `java.time`; store UTC instants; show Africa/Nairobi |
| Language | English and Kiswahili through per-app language support, switchable in Settings |

Data rules:

- The UI reads from Room and DataStore. The network writes into Room. Screens keep working
  from cache when offline.
- Every write that must reach the server goes through the **outbox**: in one Room
  transaction, save the record and an outbox row holding its client-generated UUID. A
  worker uploads, then marks the row. Outcomes: accepted or duplicate means synced; `422`
  or `409` means needs correction (keep, show the server's message); `401` means paused
  until sign-in; network failure means retry later with the same id.
- Sync states shown to people: Queued, Uploading, Synced, Will retry, Needs attention,
  Deletion pending, Deleted.
- A deletion request clears the local report queue first, then is itself queued.
- Server address: default from a build field (`http://10.0.2.2:8000` in debug). Because
  the demo URL is short-lived, Settings has a "Server address" field that validates
  against `/health` before saving. Debug builds allow cleartext to `10.0.2.2` and
  `localhost` only; everything else must be HTTPS.

## B2. Screen and feature specification

Every screen handles: loading, content, empty, error with retry, and offline. Every
interactive element is at least 48 dp, labelled for TalkBack, and survives 200% font size.

### Rider

| Screen | What it does | Data |
| --- | --- | --- |
| Welcome | One-screen introduction, "Get started" and "Explore without contributing". Shown once | Local flag |
| Your choices | Three independent items: location (off), research participation (off), data and privacy. Continue works with everything off | DataStore |
| Stage board (tab 1) | Selected stage and direction; one card per route that can be boarded there, each with range hero, range bar, freshness, crowding, demo chip, or an "unavailable" card with plain reason. Auto-refresh about every 30 s while visible, pull to refresh, numbers hidden after expiry. Direction switch. "I'm boarding" button. Favourite toggle | `GET /stages/{id}/estimates`, cache |
| Stage picker (sheet) | Search by name, favourites first, "Use my location" (asks permission at that moment, sorts by distance, shows distance), manual list grouped by corridor. Location denied state keeps manual choice and does not ask again | Room catalogue, one-shot location |
| Routes (tab 2) | All routes with number badge, name, corridor; search by number, name, stage or corridor; empty-search state keeps the query | Room catalogue |
| Route detail | Direction toggle, line diagram of stages with the rider's stage marked, estimate at that stage, crowding, "How reliable is this estimate?", "I'm boarding this route" | Catalogue, `GET /estimates` |
| Evidence | Explains this estimate: arrivals counted in the last 30 minutes, last observation time, model version, 80% range meaning, data kind, what "unavailable" means | Estimate fields, `/meta` |
| Boarding flow | Consent gate if consent is absent (read policy, agree, or keep browsing) -> choose route (none preselected) -> review stage, route, direction, time -> save locally -> "Saved on this phone" with link to Activity | Room, outbox, `POST /reports` |
| Activity (tab 3) | List of own reports with state; detail with Undo (unsynced: local delete; synced: `DELETE /reports/{id}`), sync details, retry now | Room, outbox |
| Settings (tab 4) | Language; Appearance (system, light, dark, dynamic colour); Wait alerts; Data and privacy; Sync and storage; Field team and crew; Server address; About | DataStore |
| Data and privacy | Consent status with version and date, withdraw consent, location permission status with link to system settings, what is collected, "Delete my data" with confirmation, deletion status | `POST /deletion-requests` |
| Wait alerts | Pick a stage, route, direction and a threshold ("tell me when the wait is likely under N minutes"). Asks for notification permission at that moment. Runs for up to two hours, then switches itself off. Notification opens the stage board. One alert at a time | WorkManager, `GET /estimates` |
| Sync and storage | Counts by state, last successful sync, "Sync now", clear cached estimates | Room |
| About | Version, data attribution from `/meta`, model version, data kind, licences | `/meta` |

Plain wording for unavailable reasons (English; translate for Kiswahili):

| `reason` | Title | Supporting line |
| --- | --- | --- |
| `insufficient_evidence` | Not enough recent vehicles | Fewer than three arrivals seen in the last 30 minutes. Allow extra time. |
| `stale_evidence` | Last observation is too old | Nobody has recorded a vehicle here recently. |
| `model_not_trained`, `model_expired` | Estimates are paused | The estimator is being updated. |
| `unsupported_training_group`, `outside_training_support` | No estimate for this time yet | We have not observed this route enough at this hour. |
| `evidence_provenance_mismatch` | Estimate on hold | New observations here are being checked. |

### Access (enumerator and crew)

Settings -> Field team and crew -> enter code (auto-uppercase, dashes optional) ->
`POST /auth/redeem` -> role landing. Store the token encrypted, with role and expiry. Show
role and "expires in" on the landing screen, with Sign out. `401` anywhere returns here
without losing unsent work. `429` shows "Too many attempts, wait a few minutes".
No "preview" buttons that skip authentication (the Figma has them; the app must not).

### Enumerator

| Screen | What it does |
| --- | --- |
| Setup | Choose stage, route, direction (only valid combinations). Shows coverage guidance. "Start session" creates the session locally and queues `POST /sessions`. Session `data_kind`: `synthetic` with `dataset_id` `android-practice` when `/meta.demo_mode` is true (shown as "Practice session"), otherwise `field` |
| Live observation | Session timer, large "Record arrival" and "Record departure". Arrival creates an open vehicle with a session-local label (Vehicle 01, 02, ...), never a number plate. Departure asks which open vehicle. Crowding picker per event. Several vehicles can be open at once. Event log with void (see WP1). Works fully offline; events queue and sync in the background. Screen stays awake during a session. Leaving the screen does not end the session; an unfinished session is restored after process death |
| Pause | "Pause" ends the current server session at that moment. "Resume" starts a new server session for the same stage, route and direction. Locally both belong to one observation window, shown with the gap |
| Summary | Counts of arrivals and departures, open vehicles. For each open vehicle: add its departure now, or mark departure unobserved. Then "End session" queues `PUT /sessions/{id}/end`. "Session closed" confirmation shows sync state |
| History | Past sessions on this phone with counts and sync state |

### Crew

One screen plus detail. Choose route and direction, window of 30, 60 or 120 minutes.
Permanent disclaimer card ("These counts only describe participating riders"). Per stage:
bar for boarding reports, observed arrivals, latest crowding with age, status tag
(`ok`, `limited`, `no_data`), and whether a wait estimate is currently available. Refresh
button and pull to refresh. Closing line: no hold-or-depart recommendation is offered.

## B3. Work packages

Each package lists scope and acceptance checks. A package is finished only when every
check is met or explicitly reported as not met.

### WP0. Prepare the repository

1. Clone, check out `backend`, and look for `docs/api.md`. If missing, apply
   `nganyaflow-backend-update.patch` with `git am --3way`, then push `backend`.
2. Copy the design and product material from the docs branch without merging it (both
   branches have their own `README.md` and `docs/development.md`):
   `git checkout origin/docs/material-redesign-ai-concept -- design docs/product.md
   docs/design-system.md docs/architecture.md docs/privacy-and-research.md
   docs/data-dictionary.md docs/concept-note.md`. Add a line at the top of
   `docs/architecture.md` saying its API table is superseded by `docs/api.md`.
3. Create branch `android` from the result. Add `AGENTS.md` if absent.
4. Run the backend locally as in A5 and confirm estimates are returned.

Accept: `pytest server/tests -q` shows 34 passed; `curl` of a stage's estimates returns at
least one `available`; `design/tokens.json` exists on the branch.

### WP1. Backend improvements

1. **Docker.** Build and run with `docker compose up --build`. Fix whatever fails. The
   container must seed on first start, come up healthy, and keep its data across restarts.
2. **Simulator and human sessions.** In demo mode the simulator must skip a
   stage/route/direction while a non-simulator session is open on it, and resume when it
   closes. In `compute_estimate`, choose the most recent session that has enough recent
   arrivals rather than only the session of the very last arrival. Tests for both.
3. **Simulator housekeeping.** On start, close simulator sessions left open by a previous
   run. Delete simulator observations older than 24 hours. Add a database index or stored
   columns so per-group arrival queries stay fast. A test proves old rows are removed and
   training data from `seed-demo` is untouched.
4. **Enumerator fields.** Add optional `vehicle_label` (session-local text, at most 20
   characters) to observations. Add crowding level `standing` between `seats_available`
   and `full` everywhere crowding is validated or generated. Add
   `POST /api/v1/observations/{id}/void` (enumerator role): a voided event is kept but
   excluded from estimates, demand and training exports; voiding twice is safe. Tests.
5. **Rate limit.** Make the failed-redeem limit configurable and document that behind a
   tunnel all users share one address. Do not trust forwarded headers by default.
6. Update `docs/api.md` and `docs/development.md` for every change above.

Accept: all tests pass including new ones; lint clean; the container runs and
`/api/v1/meta` answers; a manual run shows an enumerator session at one stage produces an
estimate there in demo mode without flicker.

### WP2. Android project, design system and navigation shell

1. Create the project in `android/` with the Gradle wrapper, version catalogue, Kotlin,
   Compose, Material 3, Navigation, Hilt, Room, DataStore, WorkManager, HTTP client,
   serialization, test libraries.
2. Theme: light and dark colour schemes from A6, `StatusColors` extension for success and
   warning, type scale, shapes, spacing constants. Optional dynamic colour.
3. Shared components with previews: range hero and range bar, route badge, freshness chip,
   crowding chip, demo-data chip, notice card (info, warning, error, success), route card
   (available and unavailable), skeletons, primary and secondary buttons, empty and error
   states.
4. Single activity, edge to edge, splash screen, adaptive icon, bottom navigation with the
   four destinations, placeholder-free navigation graph (screens may show real loading
   states but nothing fake).
5. GitHub Actions workflow: build debug APK, run unit tests and lint for `android/`; run
   `pytest` and `ruff` for `server/`.
6. `android/README.md` with exact build and run commands that were actually executed.

Accept: `./gradlew assembleDebug testDebugUnitTest lintDebug` succeeds; the app launches
on an emulator; component previews render in light and dark; the workflow file is valid.

### WP3. Data layer

1. API service and DTOs for every endpoint in `docs/api.md`; a sealed result type mapping
   HTTP and network failures; auth interceptor that adds the bearer token only to
   enumerator and crew calls.
2. Room schema with exported schema files; DAOs; DataStore preferences; install id created
   once.
3. Catalogue repository: fetch stages and routes, store them, refresh when
   `/meta.catalog_version` changes; works offline after the first load.
4. Estimates repository: fetch per stage, cache with `expires_at`, expose a flow that
   drops numbers at expiry; clock offset from `/meta.server_time`.
5. Outbox and upload worker as described in B1.

Accept: unit tests with a mock web server cover success, `401`, `409`, `422`, `429`,
timeout and malformed body; Room tests cover the outbox transaction; an estimates test
proves numbers disappear after expiry; a test proves a retried upload reuses the same id.

### WP4. Rider core

Welcome, Your choices, Stage board, Stage picker with search, favourites and one-shot
location, Routes with search, Route detail with line diagram, Evidence.

Accept: on a running demo server the stage board shows live ranges that refresh; turning
on aeroplane mode shows cached values marked offline and then hides them at expiry;
denying location still allows manual selection and does not re-prompt; UI tests cover
stage selection, direction switch and search-empty; screenshots light and dark.

### WP5. Boarding reports and Activity

Consent gate, route choice, review, local save, outbox upload, Activity list and detail,
undo for unsynced and synced reports, sync details.

Accept: a report made offline shows Queued, then Synced after reconnecting, and appears in
the crew demand count on the server; killing the app between save and upload loses
nothing and creates no duplicate; undo removes it on the server; declining consent returns
to browsing with nothing stored; UI test for the full flow.

### WP6. Settings

Language (English and Kiswahili for every string; mark Kiswahili "needs review" in About
until a speaker has checked it), appearance, data and privacy with consent withdrawal and
"Delete my data", sync and storage, server address with health check, about.

Accept: switching language changes the whole app without restart problems; deleting data
empties Activity, shows pending then completed, and a report queued before the request is
not uploaded afterwards; changing the server address to an unreachable one is refused.

### WP7. Enumerator

Access code entry, Setup, Live observation, Pause and Resume, Summary, History, as in B2.

Accept: with code `NF-DEMO-ENUM`, a session recorded on the emulator appears on the server
(check `/admin`), and after three arrivals the stage shows an estimate; recording offline
then reconnecting uploads session, events and end in the right order; process death during
a session restores it; an expired or revoked code keeps unsent events and asks for a new
code; two vehicles can be open at once; a missing departure can be marked unobserved;
voiding an event removes it from the estimate.

### WP8. Crew

Role landing, demand screen and stage detail as in B2.

Accept: with code `NF-DEMO-CREW`, counts match `GET /api/v1/demand` for the same window;
an enumerator code is refused with a clear message; the disclaimer is visible without
scrolling on a 360 dp wide screen; `limited` and `no_data` are visually distinct without
relying on colour.

### WP9. Wait alerts and visual polish

1. Wait alerts as specified in B2, with permission asked in context and automatic expiry.
2. Motion: animated estimate changes, shared-bounds transitions, list item placement
   animations, reduced-motion support.
3. Haptics on enumerator records. Predictive back verified.
4. Review every screen against A6 "better than the Figma" and fix the weak ones.

Accept: an alert fires on the emulator when the threshold is met and stops after expiry or
when cancelled; side-by-side screenshots of each main screen against its Figma frame.

### WP10. Hardening and hand-over

1. Accessibility pass: TalkBack walk-through of the three flows, 200% font, 320 and 360 dp
   widths, landscape, contrast in both themes.
2. Offline matrix: process death during save and
   upload, duplicate retry, timeout after server acceptance, reconnect, revoked access,
   deletion before retry, clock ahead of server.
3. Performance: no jank on the stage board, no network or database work on the main
   thread, baseline release build with minification that still runs.
4. Final documents: root `README.md` rewritten for the whole project; `android/README.md`;
   a demo script (start server, open tunnel, paste URL into Settings, walk the three
   roles); screenshot set; a list of known limitations.
5. Merge `android` into `main` through a pull request.

Accept: all backend and Android tests pass in CI; a signed-debug APK is attached to the
pull request; a person who has never seen the project can follow the demo script from a
clean clone.

## B4. Definition of done for the whole project

- Every screen listed in B2 exists and every control on it does something real.
- The three roles work end to end against the server: rider sees and reports, enumerator
  records and the estimate changes, crew sees the counts.
- The app is usable offline for reading cached data and for recording, and it syncs
  without loss or duplication.
- No number appears without its range, its freshness and, in demo, its "Demo data" label.
- English and Kiswahili, light and dark, small and large fonts all hold up.
- CI is green. Nothing secret is in the repository.
- The honesty labels are intact: synthetic data is called synthetic everywhere.

## B5. Decisions the team still owes

| Decision | Suggested default | Who |
| --- | --- | --- |
| Application id and app name | `com.nganyaflow.app`, "NganyaFlow" | Makau |
| Minimum Android version | API 26 (Android 8.0) | Team |
| Branch flow | `android` branch from `backend`, pull request into `main` at the end | Makau and Karani |
| Who reviews Kiswahili text | A fluent team member | Team |
| Real map view | Not included; the route line diagram needs no key or tiles | Team |
| Hosting for the demo URL | Local Docker plus a temporary tunnel, on the presenter's laptop | Team |
| Field data collection | Out of scope until supervisor approval; app supports it but ships in demo mode | Supervisor |

## B6. Things that are unverified

- The Docker image has not been built since the entrypoint and compose file were added.
- Nothing in this plan has been tested on a physical phone.
- Library choices in B1 name families of tools, not versions. Versions must be checked at
  the time of setup.
- The catalogue comes from a 2019 feed. Stage positions and route numbers have not been
  checked in the field.
- Model accuracy figures (MAE 0.91 min against a 1.75 min baseline, 79% interval coverage)
  measure the simulator only.
- Only part of the Figma was reviewed frame by frame: Welcome, Your choices, Stage, Route,
  Routes, Activity, Settings, Access, Setup, Logging, Summary and Crew. The rest is known
  from the frame list and the product state table.
