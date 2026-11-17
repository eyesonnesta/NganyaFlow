# API reference for the Android app

Base path `/api/v1`. All times are ISO 8601 with a UTC offset (for example
`2026-11-16T15:30:00+03:00`); the server answers in UTC. Interactive documentation is
served at `/docs` on a running server. JSON field names use `snake_case`.

**Provenance.** The demo server ships clearly synthetic arrival data on real stage and
route names (Digital Matatus 2019 network). Estimates carry `data_kind` (`synthetic` in
the demo). The app must show a visible "Demo data" label whenever `data_kind` is not
`field`, and `GET /meta` reports `demo_mode`.

## Roles and sign-in

| Role | How they get in | Can use |
| --- | --- | --- |
| Rider | Nothing. No account | Catalogue, estimates, boarding reports, deletion |
| Enumerator | Access code from the admin panel | Sessions and observations |
| Crew | Access code from the admin panel | Demand view |

`POST /auth/redeem` with `{"code": "NF-ABCD-EFGH", "device_name": "optional"}` returns
`{"token", "token_type": "Bearer", "role", "expires_at"}`. Codes ignore case, dashes and
spaces. Send `Authorization: Bearer <token>` afterwards. Tokens last 12 hours and never
outlive their code; on `401` ask for the code again. Ten failed redemptions in ten minutes
from one address return `429`. `GET /auth/me` returns `{"role"}`. Store tokens in
encrypted storage, never in logs.

A `403` means the token's role cannot use that endpoint. The shared `X-Write-Token`
header is for scripts only and acts as every role. It is disabled unless the operator sets
`NGANYAFLOW_WRITE_TOKEN`; there is no default value. The app never uses it.

## Catalogue and status

| Endpoint | Returns |
| --- | --- |
| `GET /meta` | `catalog_version`, `consent_version`, `model_version`, `model_data_kind`, `demo_mode`, `evidence_window_minutes`, `server_time` |
| `GET /stages` | `[{id, name, latitude, longitude}]` (coordinates may be null) |
| `GET /routes?stage_id=` | `[{id, name, short_name, corridor, stage_ids, directions, source}]`; `stage_ids` run from the town end outward |

Find the nearest stage **on the phone** from the coordinates. Never send the rider's
location to the server. `directions` values are `townbound` and `outbound`.

## Estimates (no sign-in)

`GET /estimates?stage_id=&route_id=&direction=` and
`GET /stages/{stage_id}/estimates?direction=` (every route that can be boarded at the
stage, one call). An estimate:

```json
{
  "status": "available",
  "median_minutes": 3.7, "lower_minutes": 1.9, "upper_minutes": 5.3,
  "nominal_coverage": 0.8, "evidence_count": 6,
  "last_observed_at": "2026-11-16T12:36:10Z", "expires_at": "2026-11-16T12:38:40Z",
  "model_version": "61691e4bda619045", "data_kind": "synthetic",
  "occupancy": "seats_available", "occupancy_observed_at": "2026-11-16T12:36:10Z",
  "reason": null
}
```

When `status` is `unavailable`, the numbers are null and `reason` is one of
`insufficient_evidence`, `stale_evidence`, `model_not_trained`, `model_expired`,
`unsupported_training_group`, `outside_training_support`, `evidence_provenance_mismatch`.
Show the range `lower–upper`, never only the median. Hide the numbers once `expires_at`
has passed. Say "next vehicle", not a boarding promise: seats are never guaranteed.
`occupancy` is `empty`, `seats_available`, `full` or `unknown`; show its age.

## Boarding reports (riders, anonymous)

Each install makes one random UUID (`install_id`) and keeps it. No name, phone number or
location is sent.

`POST /reports` with `{"records": [{id, install_id, stage_id, route_id, direction,
boarded_at, consent_version}]}`, up to 100. Use the `consent_version` from `/meta`; a
different one returns `422`. Response:
`{"accepted_ids": [], "duplicate_ids": [], "rejected": [{"id", "reason"}]}`. Remove a
queued report locally once its id is accepted or duplicate. `rejected.reason` is
`retracted` (the rider undid it), `deleted_by_request` (the install was deleted) or
`daily_limit` (300 a day per install); drop those. Reports older
than seven days or in the future return `422`.

`DELETE /reports/{id}?install_id=` undoes one report (`204`, or `404` if it was never
received). The server keeps a marker, so repeating the undo returns `204` again and a
delayed retry of the original upload is refused as `retracted`. If the upload is still in
flight when the rider taps Undo, wait for its result and then send the `DELETE`.

## Deletion

`POST /deletion-requests` with `{"id": "<new uuid>", "install_id": "<uuid>"}` erases every
report from that install. It completes immediately and is safe to retry:
`{"id", "status": "completed", "reports_deleted", "requested_at", "completed_at", "note"}`.
`GET /deletion-requests/{id}` returns the same, or `404`.

Deletion **retires the `install_id` permanently**. Every later report carrying it is
refused as `deleted_by_request`, whatever its timestamp, so the outcome does not depend on
the phone's clock. The app must, in this order: drop its local report queue, create a new
random `install_id`, then queue the deletion request for the old one. A rider who reports
again afterwards does so under the new id.

## Enumerator (role `enumerator`)

1. `POST /sessions` with `{id, stage_id, route_id, direction, started_at, data_kind,
   dataset_id}`. `data_kind` is `field` for real observation. Generate ids on the phone.
2. `POST /observations/sync` with `{"records": [{id, session_id, observed_at, event_type,
   occupancy}]}`, up to 500. `event_type` is `arrival` or `departure`. Upload the session
   before its observations. Identical retries return `duplicate_ids`; reusing an id with
   different content is `409`; a rejected batch stores nothing.
3. `PUT /sessions/{id}/end` with `{"ended_at"}`. Start a new session after any pause.

Observations must lie inside their session and not in the future (`422` otherwise).
Because the demo model is synthetic, a session marked `field` makes that stage's estimate
`evidence_provenance_mismatch` until a field-trained model is activated.

## Crew (role `crew`)

`GET /demand?route_id=&direction=&window_minutes=60` (15 to 720). Returns the stages
where that direction can be boarded:

```json
{"route_id": "237-thika", "direction": "townbound", "window_minutes": 60,
 "min_reports_for_ok": 5, "disclaimer": "Counts include only people who chose to report ...",
 "stages": [{"stage_id": "muthaiga", "stage_name": "Muthaiga", "boarding_reports": 6,
   "observed_arrivals": 13, "latest_arrival_at": "...", "latest_occupancy": "full",
   "occupancy_observed_at": "...", "status": "ok"}]}
```

`status` is `no_data`, `limited` (fewer than `min_reports_for_ok` reports) or `ok`. Always
show the `disclaimer`: app reports are not total passengers.

## Errors

`401` sign in again, `403` wrong role, `404` unknown stage/route/direction/id, `409`
conflicting reuse of an id, `422` invalid body or timestamp, `429` slow down. Error bodies
are `{"detail": "..."}` with a readable message. Network failure is not an API error:
keep the record queued and retry with the same ids.
