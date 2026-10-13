# Proposed architecture

## Boundaries

The course's three-tier view maps to presentation (Android), application logic (API and inference) and storage (database). Within Android, use UI and data layers, with an optional domain layer for reused use cases. Compose replaces XML screen layouts; one Activity per screen is unnecessary.

| Part | Responsibility |
| --- | --- |
| Single Activity and Compose navigation | Rendering, input and screen transitions |
| ViewModels and immutable UI state | Loading/content/error states, selection and events |
| Repositories | Coordinate local/remote sources; expose observable local data |
| Room | Registry cache, sessions, reports and durable outbox |
| DataStore | Language, appearance and consent version preferences |
| WorkManager | Network-constrained retries with backoff and process recovery |
| Python API, proposed FastAPI | Validation, role authorization, deduplication and inference |
| PostgreSQL | Registry versions, accepted events, access grants and model metadata |
| Offline training pipeline | Dataset snapshots, grouped chronological splits and evaluation |

Coroutines keep asynchronous work off the UI thread. ViewModels must not retain Activity references. AndroidManifest.xml declares required components and minimal permissions. Background location, contacts, SMS and camera are unnecessary. Optional location is requested at the action that needs it.

## Save and sync contract

1. Validate stage, route, direction, consent and event fields.
2. Atomically save the report and outbox entry with a client-generated UUID in Room.
3. Only after success display “Saved on this phone”. Storage errors must not produce success feedback.
4. Retry with the same UUID. Enforce uniqueness server-side and return the canonical status.
5. Mark synced after acknowledgement. A timeout after server acceptance must be safe to retry.
6. Back off on transient failures; expose permanent validation errors for correction. Keep event time separate from upload time.

Record clock quality and flag impossible times. Match route/direction versions to the observation period. Apply deletion tombstones before replaying queued uploads so removed reports cannot reappear. Research payloads are immutable or explicitly versioned when corrected; do not silently overwrite reference events.

## Proposed API

These endpoints do not exist yet.

| Endpoint | Contract |
| --- | --- |
| `GET /v1/stages` | Stable ID, coordinates, direction, registry/source version |
| `GET /v1/routes` | Route ID, direction and ordered stage IDs |
| `GET /v1/estimates` | Stage/route/direction, status, quantiles, observation time, expiry, model version |
| `POST /v1/reports` | UUID, event time, selected stage, route, direction and consent version |
| `POST /v1/observations` | Session, vehicle-local ID, event type, occupancy and coverage |
| `POST /v1/deletion-requests` | Request UUID and scoped deletion credential |

Illustrative prediction response:

```json
{
  "stage_id": "demo-muthaiga-townbound",
  "route_id": "demo-A",
  "direction_id": "townbound",
  "status": "available",
  "wait_minutes": {"p10": 4, "p50": 6, "p90": 9},
  "nominal_coverage": 0.8,
  "evidence_status": "recent",
  "last_observed_at": "2026-10-13T06:38:00Z",
  "as_of": "2026-10-13T06:40:00Z",
  "expires_at": "2026-10-13T06:45:00Z",
  "model_version": "demo-only",
  "occupancy": "unknown"
}
```

This five-minute example lifetime is not a validated policy. Select expiry and evidence gates using validation data. Unavailable responses omit numeric waits and include a reason. Validate `0 <= p10 <= p50 <= p90`. Never count down a cached range as though a particular vehicle were tracked.

## Deployment and security

Use TLS, server authorization, rate limits and schema validation. Keep model artifacts and administrative credentials off the phone. Access codes need expiry and retry limits; hiding a screen is not authorization. Separate research records from public aggregates. Record dataset/model versions and roll back to a baseline or no estimate on quality failure. Hosting, cost, runtime versions and operational ownership remain to be selected.
