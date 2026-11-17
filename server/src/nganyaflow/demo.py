"""Synthetic demo data across the whole catalogue. None of this is measured.

Real stage and route names (from the Digital Matatus 2019 network feed) carry
simulated arrival times. Every record is marked ``data_kind="synthetic"`` so the
trainer never mixes it with field observations and the API exposes the provenance.
"""

import json
import logging
import random
from datetime import UTC, datetime, timedelta
from uuid import NAMESPACE_URL, UUID, uuid5
from zoneinfo import ZoneInfo

from .schemas import CloseSession, Observation, Session

logger = logging.getLogger("uvicorn.error")

NAIROBI = ZoneInfo("Africa/Nairobi")
# Occupancy generation changed with the standing level; use new deterministic IDs
# rather than conflict with already imported v1 records when seed-demo is rerun.
DATASET_ID = "nairobi-network-synthetic-v2"
LIVE_DATASET_ID = "nairobi-network-synthetic-live"

# Typical minutes between vehicles in the busiest period. Generator assumptions only.
ROUTE_BASE = {
    "cbd-study-simulated": None,  # per-stage values below keep the original teaching data
    "237-thika": 3.5,
    "17b-kasarani": 4.5,
    "100-kiambu": 5.5,
    "111-ngong": 4.5,
    "125-rongai": 4.0,
    "23-kangemi": 3.5,
    "105-kikuyu": 5.0,
    "58-buruburu": 4.0,
    "34-jkia": 5.0,
    "110-kitengela": 5.5,
    "46-kawangware": 4.5,
    "14a-strathmore": 6.0,
}
LEGACY_STAGE_BASE = {"kencom": 4.5, "odeon": 6.0, "railways": 8.0}
FIRST_HOUR, LAST_HOUR = 6, 21


def served_groups(catalog):
    """(stage, route, direction) combinations a rider can actually board."""
    from .main import served_directions

    return [
        (stage_id, route.id, direction)
        for route in catalog.routes
        for stage_id in route.stage_ids
        for direction in served_directions(route, stage_id)
    ]


def regime(group, local):
    """Target headway in minutes at a local time. Simple, learnable assumptions."""
    stage_id, route_id, direction = group
    base = ROUTE_BASE.get(route_id) or LEGACY_STAGE_BASE.get(stage_id, 6.0)
    hour = local.hour
    if 6 <= hour < 9:
        value = base * (0.75 if direction == "townbound" else 1.2)
    elif 16 <= hour < 19:
        value = base * (0.75 if direction == "outbound" else 1.2)
    elif 9 <= hour < 16:
        value = base * 1.4
    else:
        value = base * 1.6
    return value * (1.15 if local.weekday() >= 5 else 1.0)


def next_headway(rng, previous, target):
    return max(2.0, min(22.0, 0.35 * previous + 0.65 * target + rng.gauss(0, 1.2)))


def occupancy(rng, group, local):
    """Rough crowding pattern: full towards town in the morning, outbound in the evening."""
    direction = group[2]
    rush = (6 <= local.hour < 9 and direction == "townbound") or (
        16 <= local.hour < 19 and direction == "outbound"
    )
    weights = (0.1, 0.25, 0.2, 0.35, 0.1) if rush else (0.25, 0.5, 0.1, 0.05, 0.1)
    return rng.choices(["empty", "seats_available", "standing", "full", "unknown"], weights)[0]


def generate_history(catalog, today=None, days=12, sessions_per_day=2, minutes=40):
    """Closed observation sessions for every served group on the days before ``today``."""
    today = today or datetime.now(NAIROBI).date()
    sessions, ends, events = {}, {}, []
    for number, group in enumerate(served_groups(catalog)):
        stage_id, route_id, direction = group
        for back in range(days, 0, -1):
            date = today - timedelta(days=back)
            rng = random.Random(f"{DATASET_ID}/{group}/{date}")
            for k in range(sessions_per_day):
                # Offsets rotate so that, over the days, every hour is observed.
                hour = FIRST_HOUR + ((back * sessions_per_day + k + number) % 16)
                start = datetime(date.year, date.month, date.day, hour, tzinfo=NAIROBI)
                end = start + timedelta(minutes=minutes)
                sid = str(uuid5(NAMESPACE_URL, f"{DATASET_ID}/{group}/{start.isoformat()}"))
                session = Session.model_validate(
                    {
                        "id": sid,
                        "stage_id": stage_id,
                        "route_id": route_id,
                        "direction": direction,
                        "started_at": start.isoformat(),
                        "data_kind": "synthetic",
                        "dataset_id": DATASET_ID,
                    }
                )
                sessions[sid] = session
                ends[sid] = CloseSession(ended_at=end.isoformat()).ended_at
                previous = regime(group, start)
                at = start + timedelta(seconds=round(rng.uniform(0, previous) * 60))
                counter = 0
                while at < end:
                    events.append(
                        Observation.model_validate(
                            {
                                "id": str(uuid5(NAMESPACE_URL, f"{sid}/{counter}")),
                                "session_id": sid,
                                "observed_at": at.isoformat(),
                                "event_type": "arrival",
                                "occupancy": occupancy(rng, group, at.astimezone(NAIROBI)),
                            }
                        )
                    )
                    previous = next_headway(rng, previous, regime(group, at.astimezone(NAIROBI)))
                    at += timedelta(seconds=round(previous * 60))
                    counter += 1
    return sessions, ends, events


def seed_history(store, catalog, runtime_dir=None, **options):
    sessions, ends, events = generate_history(catalog, **options)
    result = store.import_bundle(sessions, ends, events)
    if runtime_dir is not None:
        registry = runtime_dir / "datasets"
        registry.mkdir(parents=True, exist_ok=True)
        (registry / f"{DATASET_ID}.json").write_text(
            json.dumps(
                {
                    "dataset_id": DATASET_ID,
                    "data_kind": "synthetic",
                    "generator": "nganyaflow.demo",
                    "note": "Real stage names; every arrival time is simulated.",
                    **result,
                },
                indent=2,
            ),
            "utf-8",
        )
    return result


def run_live_simulator(store, catalog, stop, *, interval=20.0, backfill_hours=2):
    """Keep one open synthetic session per group supplied with fresh arrivals.

    Demo mode only. Arrivals are labelled synthetic so estimates carry that provenance.
    """
    groups = served_groups(catalog)
    now = datetime.now(UTC)
    store.clean_simulator(LIVE_DATASET_ID, now)
    token = now.strftime("%Y%m%dT%H%M%S%f")
    state = {}
    started = now - timedelta(hours=backfill_hours)
    try:
        for group in groups:
            sid = str(uuid5(NAMESPACE_URL, f"{LIVE_DATASET_ID}/{group}/{token}"))
            store.add_session(
                Session.model_validate(
                    {
                        "id": sid,
                        "stage_id": group[0],
                        "route_id": group[1],
                        "direction": group[2],
                        "started_at": started.isoformat(),
                        "data_kind": "synthetic",
                        "dataset_id": LIVE_DATASET_ID,
                    }
                )
            )
            rng = random.Random(f"{LIVE_DATASET_ID}/{group}/{token}")
            first = regime(group, started.astimezone(NAIROBI))
            state[group] = {
                "sid": sid,
                "rng": rng,
                "previous": first,
                "next": started + timedelta(seconds=round(rng.uniform(0, first) * 60)),
                "counter": 0,
            }
    except Exception:
        logger.exception("Demo simulator could not start")
        return
    logger.warning("Demo mode: simulating arrivals for %d stage/route groups", len(groups))
    while True:
        moment = datetime.now(UTC)
        batch = []
        blocked = store.non_simulator_groups(LIVE_DATASET_ID)
        for group, s in state.items():
            if group in blocked:
                # Advance the schedule without inventing observations during the gap.
                s["next"] = moment + timedelta(
                    minutes=regime(group, moment.astimezone(NAIROBI))
                )
                continue
            while s["next"] <= moment:
                local = s["next"].astimezone(NAIROBI)
                batch.append(
                    Observation.model_construct(
                        id=uuid5(NAMESPACE_URL, f"{s['sid']}/{s['counter']}"),
                        session_id=UUID(s["sid"]),
                        observed_at=s["next"],
                        event_type="arrival",
                        occupancy=occupancy(s["rng"], group, local),
                    )
                )
                s["counter"] += 1
                s["previous"] = next_headway(s["rng"], s["previous"], regime(group, local))
                s["next"] += timedelta(seconds=round(s["previous"] * 60))
        if batch:
            try:
                # Recheck under the same write transaction, in case a human started
                # a session between the blocked-group read and this upload.
                store.add_batch(batch, simulator=True)
            except Exception:
                logger.exception("Demo simulator batch failed")
        if stop.wait(interval):
            return
