"""Download and prepare the official historical Nairobi Digital Matatus network feed."""

import argparse
import hashlib
import io
import json
from datetime import UTC, datetime
from pathlib import Path
from urllib.request import urlopen
from zipfile import ZipFile

import pandas as pd

SOURCE_URL = "https://www.digitalmatatus.com/data/GTFS_FEED_2019.zip"
LANDING_URL = "https://www.digitalmatatus.com/map.html"
ROOT = Path(__file__).resolve().parents[2]
FOLDER = ROOT / "server/data/external/digital-matatus-2019"
TABLES = {
    "agency", "calendar", "calendar_dates", "feed_info", "frequencies",
    "routes", "shapes", "stop_times", "stops", "trips",
}


def prepare(archive=None):
    payload = Path(archive).read_bytes() if archive else urlopen(SOURCE_URL, timeout=60).read()
    source = FOLDER / "source"
    processed = FOLDER / "processed"
    source.mkdir(parents=True, exist_ok=True)
    processed.mkdir(parents=True, exist_ok=True)
    (source / "GTFS_FEED_2019.zip").write_bytes(payload)
    tables = {}
    with ZipFile(io.BytesIO(payload)) as zipped:
        # Read only named text tables; never extract arbitrary archive paths.
        for name in sorted(TABLES):
            member = name + ".txt"
            contents = zipped.read(member)
            (source / member).write_bytes(contents)
            tables[name] = pd.read_csv(io.BytesIO(contents), dtype="string")

    stops = tables["stops"].copy()
    stops["stop_id"] = stops["stop_id"].str.strip()
    stops["stop_name"] = stops["stop_name"].str.strip()
    for column in ["stop_lat", "stop_lon"]:
        stops[column] = pd.to_numeric(stops[column], errors="coerce")
    reason = pd.Series("", index=stops.index, dtype="string")
    invalid = (
        stops["stop_id"].isna() | stops["stop_id"].eq("")
        | stops["stop_name"].isna() | stops["stop_name"].eq("")
        | ~stops["stop_lat"].between(-90, 90)
        | ~stops["stop_lon"].between(-180, 180)
    ).fillna(True)
    reason.loc[invalid] = "missing identifier/name or invalid coordinate"
    conflicting = stops.drop_duplicates().duplicated("stop_id", keep=False)
    conflicts = stops.drop_duplicates().loc[conflicting, "stop_id"]
    reason.loc[stops["stop_id"].isin(conflicts)] = "conflicting stop ID"
    accepted = stops.loc[reason.eq("")].copy()
    retries = accepted.duplicated(keep="first")
    reason.loc[accepted.index[retries]] = "exact duplicate stop"
    cleaned = stops.loc[reason.eq("")].copy()
    rejected = stops.loc[reason.ne("")].copy()
    rejected["reason"] = reason.loc[rejected.index]
    assert cleaned["stop_id"].is_unique
    assert len(cleaned) + len(rejected) == len(stops)

    # An explicit study rectangle, not an official administrative CBD boundary.
    cbd = cleaned.loc[
        cleaned["stop_lat"].between(-1.295, -1.275)
        & cleaned["stop_lon"].between(36.815, 36.835)
    ].copy()
    stop_times, trips, routes = (tables[n] for n in ["stop_times", "trips", "routes"])
    assert trips["trip_id"].is_unique and routes["route_id"].is_unique
    assert stop_times["trip_id"].isin(trips["trip_id"]).all()
    assert stop_times["stop_id"].isin(stops["stop_id"]).all()
    assert trips["route_id"].isin(routes["route_id"]).all()
    links = stop_times.loc[stop_times["stop_id"].isin(cbd["stop_id"]), ["trip_id", "stop_id"]]
    links = links.merge(trips[["trip_id", "route_id", "direction_id"]],
                        on="trip_id", validate="many_to_one")
    links = links[["route_id", "direction_id", "stop_id"]].drop_duplicates()
    cbd_routes = routes.loc[routes["route_id"].isin(links["route_id"])].copy()
    for name, frame in {
        "stops": cleaned, "rejected_stops": rejected, "routes": routes,
        "cbd_stops": cbd, "cbd_routes": cbd_routes, "cbd_route_stops": links,
    }.items():
        frame.to_csv(processed / (name + ".csv"), index=False)

    digest = hashlib.sha256(payload).hexdigest()
    summary = {
        "dataset_id": "digital-matatus-2019-network-v1",
        "data_kind": "external_network",
        "title": "Digital Matatus Nairobi GTFS feed, published 2019-05-30",
        "landing_url": LANDING_URL,
        "download_url": SOURCE_URL,
        "methodology_url": "https://www.digitalmatatus.com/about.html",
        "retrieved_at": datetime.now(UTC).isoformat(),
        "archive_sha256": digest,
        "archive_bytes": len(payload),
        "source_rows": {name: len(frame) for name, frame in tables.items()},
        "clean_stops": len(cleaned),
        "rejected_stops": len(rejected),
        "rejection_reasons": rejected["reason"].value_counts().to_dict(),
        "cbd_stops": len(cbd),
        "cbd_routes": len(cbd_routes),
        "cbd_route_stop_links": len(links),
        "cbd_rectangle": {"min_lat": -1.295, "max_lat": -1.275,
                          "min_lon": 36.815, "max_lon": 36.835},
        "feed_metadata": tables["feed_info"].fillna("").to_dict(orient="records"),
        "calendar": tables["calendar"].fillna("").to_dict(orient="records"),
        "wait_model_training_eligible": False,
        "reason": "Static GTFS network/frequency feed; no dated, repeated observed arrivals.",
        "license_status": "Official site offers data freely; no explicit license in ZIP. "
                          "Original and processed files remain local and excluded from Git.",
    }
    (FOLDER / "source.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, help="Use an already downloaded official ZIP")
    arguments = parser.parse_args()
    prepare(arguments.archive)
