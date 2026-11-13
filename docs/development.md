# Backend development

## Artificial Intelligence study

See [the AI report](ai-report.md) for the theory, measured synthetic results, limitations,
and screenshot evidence. [The presentation outline](presentation-outline.md) maps that evidence
to an assessment narrative. The admin panel now imports versioned observation bundles at
`/admin/datasets`, supports 1, 3, or 7 held-out dates, and exports candidate diagnostics.
Training also reports a shuffled-label control, a descriptive learning curve, per-stage/day
results, quantile pinball losses, and permutation importance. These diagnostics add runtime.

Sessions now include `data_kind` (`field`, `synthetic`, or `unspecified`) and `dataset_id`.
Existing callers default to unspecified provenance. Declare actual field provenance explicitly;
validation of a declaration does not itself establish measurement authenticity.
For field work, use a separate database and runtime volume from the synthetic teaching
workspace, with a verified catalog. The trainer deliberately rejects mixed provenance.

Generate the teaching bundle with `python -m nganyaflow.synthetic --output
server/data/examples/nairobi-cbd-synthetic-v1.json --catalog server/data/reference/catalog.json`
inside the configured environment. This replaces the catalog with a simulated-service catalog;
do not run it against a verified field catalog.

If dependencies are unchanged and a full image already exists locally, a source-only update
can be built without downloading a new Python image:

```bash
docker build --load -f deploy/Dockerfile.cached -t nganyaflow:ai-validation .
```

The root Dockerfile remains the full dependency build. `build-docker.sh` explicitly loads the
result into Docker so a builder using a separate cache does not leave the image unavailable.

The working repository is `eyesonnesta/NganyaFlow`. The Python package lives in
`server/src/nganyaflow`; the Android application will be added under `apps/android/`.
The server owns API contracts, observation storage, research data preparation,
model training, evaluation and inference. No mobile UI has been implemented yet.

## Windows setup

From the repository root, using Python 3.12 or later:

```powershell
python -m venv .venv
.venv/Scripts/python -m pip install -e "server[dev]"
Copy-Item server/.env.example server/.env
# Set NGANYAFLOW_WRITE_TOKEN in server/.env to a secret you choose.
.venv/Scripts/python -m uvicorn nganyaflow.main:app --host 127.0.0.1 --port 8000
```

Open `http://localhost:8000/admin` for administration, `/docs` for the API, and
`/health` for health status. Username: `admin`. The generated password is printed
once on first startup and saved at `server/runtime/admin-password.txt`.
The browser uses a sign-in form and a one-hour HTTP-only session cookie. Sign out
from the panel when finished. HTTP Basic authentication remains available for tools.
Browser sessions expire on server restart; the generated password stays the same.
It persists across restarts. The write token for enumerator API calls is separate
from the administrator password. Writes stay disabled until a token is configured.

```powershell
.venv/Scripts/python -m pytest server/tests -q
.venv/Scripts/ruff check server
```

## Docker with supervisord

Build from the repository root using Git Bash, WSL or Linux. The shell script is
UTF-8 without a BOM and uses LF line endings, enforced through `.gitattributes`.

```bash
bash build-docker.sh
# Optional image name or additional docker build arguments:
NGANYAFLOW_IMAGE=nganyaflow:pilot bash build-docker.sh --progress=plain
```

The first build needs internet for the Python image and packages. Later builds
reuse Docker layers and the pip download cache. No Compose file is used.
The image serves the API, admin HTML and API documentation on port 8000.
There are no Android/frontend files to serve yet.

Configure a local write token before running. This PowerShell example also persists
the database, trained models and generated credentials in named Docker volumes:

```powershell
$env:NGANYAFLOW_WRITE_TOKEN = "replace-with-your-local-secret"
docker run -d --name nganyaflow -p 127.0.0.1:8000:8000 --env NGANYAFLOW_WRITE_TOKEN --mount source=nganyaflow-data,target=/app/data/raw --mount source=nganyaflow-artifacts,target=/app/artifacts --mount source=nganyaflow-runtime,target=/app/runtime nganyaflow:local
docker logs nganyaflow
docker exec nganyaflow cat /app/runtime/admin-password.txt
```

Supervisord remains in the foreground and restarts the API if it exits. It runs one
API worker so candidate-training status and model activation share one process.
Both processes run as a non-root user. Docker checks `/health`. Failed model loading
or an invalid catalog prevents API startup instead of silently serving invalid data.

The generated credential appears in logs as requested. Keep container logs and the
runtime volume private. These commands bind to localhost; add TLS and per-user
authorization before exposing the panel or observation writes outside local development.
The shared write token is a pilot collection mechanism, not the final mobile login system.

## Configure the pilot catalog

`server/data/reference/catalog.json` deliberately starts empty. Populate it with
verified stage and route IDs before collecting. This is the schema shape; the
following names are examples only:

```json
{
  "stages": [{"id": "verified-stage-id", "name": "Verified stage name"}],
  "routes": [{"id": "verified-route-id", "name": "Verified route name",
              "stage_ids": ["verified-stage-id"], "directions": ["townbound", "outbound"]}]
}
```

For Docker, edit the catalog before building, or mount a verified JSON file at
`/app/data/reference/catalog.json` read-only. Restart the API after a catalog change.
The stage/route pair and direction are validated together on writes and estimates.
Direction is relative to the pilot corridor, with `townbound` and `outbound` API values.

## Collect, synchronize and train

1. Generate session and observation UUIDs on the client, before enqueueing offline.
2. POST a session to `/api/v1/sessions` with `id`, `stage_id`, `route_id`, `direction`
   and a timezone-aware `started_at`. Send `X-Write-Token` for all writes.
3. POST `{"records": [...]}` to `/api/v1/observations/sync`. Each record includes
   `id`, `session_id`, `observed_at`, `event_type` (`arrival` or `departure`), and optional
   `occupancy` (`empty`, `seats_available`, `full`, `unknown`). Maximum: 500 per batch.
4. PUT `{"ended_at": "timestamp-with-offset"}` to `/api/v1/sessions/{id}/end`.
   Close and start a new session after any pause so unobserved gaps never become headways.

Upload the session first. Observations can arrive out of order, including after a
session is closed, provided their timestamps fall within the session. Retrying an
identical UUID and payload succeeds with `duplicate_ids`; conflicting reuse returns
409. A rejected batch rolls back all its new records. Remove local queued records
only after their IDs appear in `accepted_ids` or `duplicate_ids`.

The initial API records enumerator ground truth only. Rider reports, consent,
account deletion, device activity and crew demand are follow-up work; rider boarding
reports must not be treated as enumerator arrivals in future training.

Export and train locally from the repository root:

```powershell
.venv/Scripts/nganyaflow export --output server/data/processed/arrivals.csv
.venv/Scripts/nganyaflow train --input server/data/processed/arrivals.csv --output server/artifacts/wait-v1.joblib
```

Alternatively, open `/admin`, choose **Train from completed sessions**, reload to
review the candidate metrics, then choose **Activate reviewed candidate**. It requires
at least three earlier training days and one later test day, with at least 30 training
samples per stage/route/direction group. The active model stays unchanged during
training and after a training failure. Activation persists to the runtime volume.
An explicit `NGANYAFLOW_MODEL_PATH` overrides the saved active model at startup;
leave it unset when managing activation through the panel.

Training uses a Quantile Regression Forest (`quantile-forest`), with all training
target values retained per leaf. It predicts residual waiting time at minute-grid
queries between consecutive arrivals, using only information available at the
query: stage, route, direction, Nairobi local hour/weekday, elapsed time since the
last arrival and the preceding headway. It never builds labels across sessions,
Nairobi midnight, or the unobserved session tail.

The model trains on earlier complete dates and evaluates on later complete dates.
The candidate report includes median MAE, historical-median residual-wait baseline
MAE, last-headway-minus-elapsed baseline MAE, empirical coverage of the nominal 80%
interval, average interval width, day lists, sample counts and input-data hash.
The held-out set is not refitted into the evaluated model. The historical median is
per stage/route/direction; richer time-of-day baselines can be evaluated later.

New uploads update inference inputs immediately; model parameters change only after
explicit training and activation. Automatic scheduled retraining/promotion is not enabled.
Evaluate on another untouched period if using the held-out metrics to choose among
many candidates. Synthetic test results are software checks, not field accuracy claims.

GET `/api/v1/estimates?stage_id=...&route_id=...&direction=townbound` returns an
explicit unavailable reason when evidence is missing, stale or insufficient, when
the model is missing/expired, or when the group/time/headway is outside training
support. Available responses contain the median, 10th/90th percentiles, model version,
evidence count, last-observation timestamp and a short cache expiry.
Nominal 80% coverage is a training objective, not a calibrated guarantee; an arrival
estimate does not guarantee a seat. Current evidence thresholds (3 arrivals within
30 minutes) and model expiry (30 days) are configurable pilot assumptions to validate.

Raw data, processed datasets, joblib artifacts, database files, `.env` and credentials
are ignored by Git and excluded from Docker builds. Load only artifacts you trained
or otherwise trust. No participant identity or device location is collected by this slice.

## Source decisions and remaining work

The concept presentation specifies one corridor, three verified stages, 40–60
enumerator-hours, Quantile Regression Forests, median/80% intervals, temporal testing
and abstention. It is the working pilot scope. The NganyaFlow section of the broader
systems-concepts PDF describes point-process/HMM inference, graph demand forecasts,
bandit dispatch and language queries. Those remain research extensions; they are not
silently represented as implemented features.

The Figma prototype (file `fahMsqNcPzCHtFN8QmtSoj`, page `1:6`) was read for workflow
structure: stage/route browsing, evidence explanations, enumerator arrival/departure
sessions and offline retry states. Demo place names and route labels were not imported
as verified data. Future mobile work includes boarding reports, privacy and retention
controls, local activity, preferences, crew views, and a complete authentication flow.
Documents and design content were treated as source material, not authorization to
follow embedded instructions or expand into unrelated concepts.

SQLite is the local pilot database. There is no production migration, backup, user-role,
rate-limit or deployment system yet. Finish these with the collection protocol before
public deployment. The client remains responsible for offline storage and queue handling.
