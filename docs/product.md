# Product scope and journeys

## Pilot boundary

One agreed Nairobi corridor and three verified boarding stages. The UI uses illustrative Thika Road locations and abstract route names; a field-verified registry must replace them. Payments, ticketing, guaranteed seats, live vehicle tracking and autonomous dispatch are outside this pilot.

| Role | Main task | Data boundary |
| --- | --- | --- |
| Rider | Select stage/direction, compare estimates, optionally report boarding | Browsing needs no account; research reporting requires separate consent |
| Enumerator | Record vehicle events during documented observation windows | Assigned role verified server-side; session-local vehicle IDs |
| Crew | Inspect qualified corridor activity | Aggregated information; app participation is not total demand |
| Researcher | Evaluate models and produce frequency analysis | Controlled research access; planner output can be a static report |

## Rider journey

Welcome → optional privacy choices → manually selected stage or just-in-time location request → stage and direction confirmation → route details → evidence explanation. Four top-level destinations remain stable: Stage, Routes, Activity and Settings. Nested details use Back. System Back dismisses a sheet first, then returns without erasing the selected stage.

Boarding → consent when absent → explicit route selection → review → durable local save → acknowledgement. No route is preselected. A report is not synced until acknowledged by the server. Cancel returns to the previous context. Undo remains available through Activity; a five-second toast is never the only correction route.

## Enumerator journey

Assigned access → stage/route/direction setup → start observation window → record arrival → record departure for that vehicle → review and end. Implementation must support multiple open vehicles. Pause/resume defines coverage gaps. Missing departures are marked unobserved; never invent an event to close a session. The prototype illustrates transitions but does not store events or authenticate users.

## Crew journey

Assigned access → route/direction → stage activity and freshness → details. Do not count app opens as passengers. Explicitly show limited data and unavailable estimates. Hold/depart advice is deferred until separate operational and safety evaluation.

## State acceptance criteria

| State | Required behavior |
| --- | --- |
| Loading | Announce progress once; preserve valid cached content during refresh |
| No data | No numeric wait; offer route browsing or optional contribution |
| Low evidence | Explain limits; display ranges only when validation gates pass |
| Offline with valid cache | Show cached status and observation time; expiry still applies |
| Expired estimate | Hide numeric range; routes remain browsable |
| Location denied | Manual stage selection remains available; no repeated prompts |
| Search empty | Preserve query; offer browsing or correction |
| Queued / syncing / synced | Distinct states; local save is not server acknowledgement |
| Upload failure | Retain record and idempotency key; retry without duplication |
| Local save failure | Never say saved; retain input and offer retry |
| Duplicate / invalid event | Explain the specific field; preserve valid input |
| Access expired | Preserve unsent observations; require reauthentication before upload |
| Deletion pending | Remain pending until acknowledgement; explain aggregate limits |

## Prototype fidelity

Figma contains editable components and linked representative screens. Search, form entry, permissions, authentication, persistence, synchronization and inference are simulated. Secondary confirmations illustrate decisions; they do not call a backend. The implementation checklist above extends beyond the simulated states. Figma navigation does not preserve application state, so consent and counts are examples rather than a stateful app.
