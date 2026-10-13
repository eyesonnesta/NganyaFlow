# Proposed data dictionary

Use UTC ISO 8601 timestamps in storage and Africa/Nairobi for local display. Stable IDs are opaque; route direction and boarding side are not interchangeable. All proposed schemas require implementation and validation.

| Record | Essential fields | Quality rule |
| --- | --- | --- |
| Stage | `stage_id`, name, coordinates, boarding side, registry version | Verify location and side in field; do not use display name as key |
| Route | `route_id`, direction, ordered stages, source/version | Preserve route/direction consistency over time |
| Consent | pseudonymous client ID, consent version, research choice, recorded time | Separate from OS location permission; no default opt-in |
| Rider report | UUID, stage, route, direction, `event_at`, `received_at`, consent version, sync state | Only affirmative boarding action; repeated retries share UUID |
| Observation session | UUID, authorized enumerator, stage, route scope, direction, start/end, pauses | Coverage excludes pauses; incomplete sessions stay flagged |
| Vehicle event | UUID, session ID, local vehicle ID, arrival/departure, event time, occupancy band | Departure cannot precede matched arrival; support unknown occupancy |
| Correction | record ID, revision, changed fields, reason, correction time | Preserve provenance; identify voided events explicitly |
| Estimate | stage/route/direction, quantiles, status, evidence, observation time, expiry, model version | Nonnegative ordered quantiles; absent numbers when unavailable |
| Deletion request | request ID, scope, request time, server status/acknowledgement | Do not say deleted while queued; prevent upload resurrection |

Occupancy vocabulary: `seats_available`, `standing`, `full`, `unknown`. Sync vocabulary: `queued`, `uploading`, `synced`, `retryable_failure`, `needs_correction`, `deletion_pending`, `deleted`. A paused observation window is missing coverage, not zero vehicles.

Optional coordinates require a specific user action and permission. Prefer resolving the stage on-device and discarding precise coordinates when the research question only needs the stage. Avoid licence plates, names, phone numbers and persistent location traces. Linked contributions are pseudonymous, not inherently anonymous.

Data ingestion should reject unknown IDs, mismatched route/direction, impossible times, invalid occupancy enums and negative durations. Quarantine suspected duplicates and clock drift with reasons; do not silently delete inconvenient records. Keep raw restricted snapshots separate from de-identified analysis exports. Publish a schema version with every export.
