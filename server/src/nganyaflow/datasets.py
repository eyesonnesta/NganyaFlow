"""Validate a portable observation bundle before one atomic database import."""

import re

from .schemas import CloseSession, Observation, Session


def validate_bundle(bundle, catalog):
    metadata = bundle.get("metadata", {})
    dataset_id = metadata.get("dataset_id", "")
    kind = metadata.get("data_kind")
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", dataset_id):
        raise ValueError(
            "Dataset ID must contain only letters, numbers, dots, dashes or underscores"
        )
    if kind not in {"field", "synthetic"}:
        raise ValueError("Explicit field or synthetic provenance is required")
    raw_sessions, raw_events = bundle.get("sessions", []), bundle.get("observations", [])
    if not raw_sessions or not raw_events or len(raw_events) > 20000:
        raise ValueError("Require sessions and between 1 and 20000 observations")
    sessions, ends = {}, {}
    for raw in raw_sessions:
        item = dict(raw)
        end = CloseSession(ended_at=item.pop("ended_at")).ended_at
        session = Session.model_validate(item)
        sid = str(session.id)
        if sid in sessions:
            raise ValueError("Duplicate session ID in bundle")
        if session.dataset_id != dataset_id or session.data_kind != kind:
            raise ValueError("Session provenance must match the dataset metadata")
        if end < session.started_at or (end - session.started_at).total_seconds() > 43200:
            raise ValueError(
                "A continuous session must last at most 12 hours and end after its start"
            )
        if not any(
            r.id == session.route_id
            and session.stage_id in r.stage_ids
            and session.direction in r.directions
            for r in catalog.routes
        ):
            raise ValueError("Bundle references an unsupported stage, route or direction")
        sessions[sid], ends[sid] = session, end
    events, ids, instants = [], set(), set()
    for raw in raw_events:
        event = Observation.model_validate(raw)
        sid = str(event.session_id)
        session = sessions.get(sid)
        if session is None:
            raise ValueError("Each observation must reference a session in this bundle")
        if not session.started_at <= event.observed_at <= ends[sid]:
            raise ValueError("Observation lies outside its session")
        instant = (sid, event.observed_at, event.event_type)
        if str(event.id) in ids or instant in instants:
            raise ValueError("Duplicate observation in bundle")
        ids.add(str(event.id))
        instants.add(instant)
        events.append(event)
    return metadata, sessions, ends, events
