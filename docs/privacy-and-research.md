# Privacy and research readiness

This document proposes controls. It is not an approved participant information sheet or a legal-compliance certification.

## Consent and minimization

Browsing and manual stage selection remain available without location permission or research participation. Present research consent separately, explain the fields and purpose, and record the version accepted. Request OS location permission only for nearby-stage lookup or an explicitly chosen report attachment. No background location collection is planned.

Use session-local vehicle labels rather than plates. Prefer stage IDs to raw coordinates. Avoid collecting passenger identity. A client identifier and deletion credential can still link reports; describe these as pseudonymous. Never put private data, research exports or credentials in Git.

## Decisions required before collecting participants' data

- Supervisor/institutional research and relevant site/operator permissions.
- Approved participant information, consent and withdrawal process.
- Specific retention duration for raw events, linkage, logs and backups; do not invent a period in the app.
- Named controller/custodian, contact channel, hosting location and access roles.
- Deletion scope, verification mechanism, backup expiry and treatment of irrevocable aggregates.
- Safe fieldwork procedures, enumerator training, incident handling and participant recruitment.

The supplied course and planning documents do not themselves grant these permissions. Review applicable institutional and Kenyan requirements with the responsible supervisor before deployment.

## Deletion behavior

Unsent contributions can be removed locally. Synced contributions create an authenticated/scoped deletion request; show pending until acknowledged. Tombstones prevent queued retries from restoring removed data. Explain when irreversibly aggregated research results cannot be traced back for removal. Withdrawal stops future collection and triggers the approved handling of existing linked records.

## Access and research integrity

Authorize enumerators and crew server-side. Give crew only appropriate aggregates, with suppression rules for sparse data. Rate-limit codes, expire credentials and log privileged actions without leaking report contents. Keep original reference events and explicit corrections. Train staff to record missing observations honestly. Release only data that has passed disclosure review and has appropriate redistribution rights.
