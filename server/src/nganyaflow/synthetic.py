"""Deterministic teaching observations at real CBD place names, not measured services."""

import argparse
import hashlib
import json
import random
from datetime import datetime, timedelta
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5
from zoneinfo import ZoneInfo

DATASET_ID = "nairobi-cbd-synthetic-v1"
STAGES = [
    ("kencom", "Kencom bus stage", 4.5),
    ("odeon", "Odeon bus stage", 6.0),
    ("railways", "Railways area at Nairobi Central Station", 8.0),
]
CATALOG = {
    "stages": [{"id": ident, "name": name} for ident, name, _ in STAGES],
    "routes": [
        {
            "id": "cbd-study-simulated",
            "name": "CBD study service SIMULATED",
            "stage_ids": [s[0] for s in STAGES],
            "directions": ["townbound"],
        }
    ],
}


def generate():
    rng = random.Random(20261027)
    sessions, events = [], []
    for day in range(14):
        date = datetime(2026, 10, 5, tzinfo=ZoneInfo("Africa/Nairobi")) + timedelta(days=day)
        for stage_id, name, base in STAGES:
            for hour in [7, 12]:
                start = date.replace(hour=hour)
                end = start + timedelta(minutes=90)
                sid = str(uuid5(NAMESPACE_URL, f"{DATASET_ID}/{stage_id}/{start.isoformat()}"))
                sessions.append(
                    {
                        "id": sid,
                        "stage_id": stage_id,
                        "route_id": "cbd-study-simulated",
                        "direction": "townbound",
                        "started_at": start.isoformat(),
                        "ended_at": end.isoformat(),
                        "data_kind": "synthetic",
                        "dataset_id": DATASET_ID,
                    }
                )
                at, counter = start + timedelta(minutes=1), 0
                regime = base * (1.55 if hour == 12 else 1) * (1.2 if date.weekday() >= 5 else 1)
                previous = regime
                while at < end:
                    events.append(
                        {
                            "id": str(uuid5(NAMESPACE_URL, f"{sid}/{counter}")),
                            "session_id": sid,
                            "observed_at": at.isoformat(),
                            "event_type": "arrival",
                            "occupancy": "unknown",
                        }
                    )
                    # Persistent but noisy positive headways; these are generator assumptions.
                    headway = max(
                        2.0, min(22.0, 0.35 * previous + 0.65 * regime + rng.gauss(0, 1.2))
                    )
                    previous = headway
                    at += timedelta(seconds=round(headway * 60))
                    counter += 1
    return {
        "metadata": {
            "dataset_id": DATASET_ID,
            "data_kind": "synthetic",
            "seed": 20261027,
            "generator_version": "1.0",
            "generated_for": "AI coursework validation",
            "description": "Real Nairobi CBD place names; all arrival times and route connectivity simulated.",
            "period": "2026-10-05 to 2026-10-18",
            "license": "Project-generated teaching data",
            "location_sources": [
                "https://africa.itdp.org/wp-content/uploads/2015/02/Nairobi-Ndovu-A104-BRT-Service-Plan.pdf",
                "https://krc.co.ke/nairobi-railway-city-3/",
            ],
            "assumptions": {
                "session_minutes": 90,
                "hours_local": [7, 12],
                "base_headways_minutes": {s[0]: s[2] for s in STAGES},
                "midday_multiplier": 1.55,
                "weekend_multiplier": 1.2,
                "innovation_std_minutes": 1.2,
                "headway_persistence": 0.35,
                "note": "Parameters illustrate learnable relationships, not Nairobi measurements.",
            },
        },
        "sessions": sessions,
        "observations": events,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--catalog", type=Path)
    args = parser.parse_args()
    payload = json.dumps(generate(), indent=2)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(payload, encoding="utf-8")
    if args.catalog:
        args.catalog.parent.mkdir(parents=True, exist_ok=True)
        args.catalog.write_text(json.dumps(CATALOG, indent=2), encoding="utf-8")
    print(f"Dataset SHA256: {hashlib.sha256(args.output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
