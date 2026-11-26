"""Offline, validated preparation using the same labels as mobile waiting-time training."""

import argparse
import csv
import hashlib
import json
from pathlib import Path

from .datasets import validate_bundle
from .ml import build_samples
from .schemas import Catalog


def prepare(bundle, catalog):
    metadata, sessions, _, events = validate_bundle(bundle, catalog)
    arrivals = []
    for event in events:
        if event.event_type != "arrival":
            continue
        session = sessions[str(event.session_id)]
        row = event.model_dump(mode="json")
        row.update({k: getattr(session, k) for k in (
            "stage_id", "route_id", "direction", "data_kind", "dataset_id"
        )})
        arrivals.append(row)
    samples = build_samples(arrivals)
    if not samples:
        raise ValueError("No eligible waiting-time labels; need continuous arrival sequences")
    rows = [{
        **s["features"], "session_id": s["session_id"], "query_at": s["query_at"],
        "day": s["day"], "wait_minutes": s["wait"],
        "data_kind": metadata["data_kind"], "dataset_id": metadata["dataset_id"],
    } for s in samples]
    return arrivals, rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    source = args.bundle.read_bytes()
    bundle = json.loads(source)
    catalog = Catalog.model_validate_json(args.catalog.read_text(encoding="utf-8-sig"))
    arrivals, rows = prepare(bundle, catalog)
    audit = {
        "objective": "minutes_until_next_vehicle_arrival",
        "source_sha256": hashlib.sha256(source).hexdigest(),
        "dataset_id": bundle["metadata"]["dataset_id"],
        "data_kind": bundle["metadata"]["data_kind"],
        "arrival_count": len(arrivals), "query_count": len(rows),
        "days": sorted({r["day"] for r in rows}),
        "warning": "Synthetic validation is not Nairobi field accuracy. "
                   "Field provenance is declared, not independently certified.",
    }
    args.output.mkdir(parents=True, exist_ok=False)
    for name, data in (("arrivals.csv", arrivals), ("waiting_queries.csv", rows)):
        with (args.output / name).open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(data[0]))
            writer.writeheader()
            writer.writerows(data)
    (args.output / "audit.json").write_text(json.dumps(audit, indent=2), encoding="utf-8")
    print(json.dumps(audit, indent=2))


if __name__ == "__main__":
    main()
