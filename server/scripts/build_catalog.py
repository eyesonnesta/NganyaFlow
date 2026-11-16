"""Build the stage/route catalogue from the Digital Matatus 2019 GTFS network feed.

Stage names, coordinates, route numbers and stage order come from the feed.
The feed is historical: it describes the mapped 2019 network, not today's services,
and it contains no observed arrivals. Run ``prepare_real_dataset.py`` first, or
pass ``--gtfs`` pointing at a folder holding routes/trips/stop_times/stops tables
(``.txt`` or ``.csv``).
"""

import argparse
import json
import math
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_GTFS = ROOT / "server/data/external/digital-matatus-2019/source"
OUTPUT = ROOT / "server/data/reference/catalog.json"
CBD = (-1.2841, 36.8233)

# The original teaching catalogue stays valid: same IDs, names and simulated route.
LEGACY_STAGES = {
    "kencom": "Kencom bus stage",
    "odeon": "Odeon bus stage",
    "railways": "Railways area at Nairobi Central Station",
}
LEGACY_ROUTE = {
    "id": "cbd-study-simulated",
    "name": "CBD study service SIMULATED",
    "stage_ids": ["kencom", "odeon", "railways"],
    "directions": ["townbound"],
    "source": "simulated",
}

# route_short_name -> (route id, display name, corridor, [(feed stop name, stage id, display name)])
ROUTES = {
    "237": (
        "237-thika",
        "Thika – Koja",
        "Thika Road",
        [
            ("Thika Town", "thika-town", "Thika Town"),
            ("Juja Mall", "juja", "Juja"),
            ("KU", "kenyatta-university", "Kenyatta University (KU)"),
            ("Githurai", "githurai", "Githurai"),
            ("TRM/Roysambu", "roysambu", "Roysambu (TRM)"),
            ("Allsops", "allsops", "Allsops"),
            ("Muthaiga", "muthaiga", "Muthaiga"),
            ("Ngara Stage", "ngara", "Ngara"),
            ("Koja", "koja", "Koja"),
        ],
    ),
    "17B": (
        "17b-kasarani",
        "Mwiki – Bus Station",
        "Thika Road",
        [
            ("Mwiki", "mwiki", "Mwiki"),
            ("Sunton", "sunton", "Sunton"),
            ("Seasons", "kasarani-seasons", "Kasarani Seasons"),
            ("TRM", "roysambu", "Roysambu (TRM)"),
            ("Allsops", "allsops", "Allsops"),
            ("Muthaiga", "muthaiga", "Muthaiga"),
            ("Bus Station", "bus-station", "Bus Station"),
        ],
    ),
    "100": (
        "100-kiambu",
        "Kiambu – OTC",
        "Kiambu Road",
        [
            ("Kiambu Bus Terminus", "kiambu", "Kiambu Town"),
            ("Kirigiti/Ruui", "kirigiti", "Kirigiti"),
            ("Thindigwa", "thindigwa", "Thindigwa"),
            ("Naivas Ciata Mall/Ridgeways Mall", "ridgeways", "Ridgeways"),
            ("muthaiga Golf Club Stage", "muthaiga-golf-club", "Muthaiga Golf Club"),
            ("OTC", "otc", "OTC"),
        ],
    ),
    "111": (
        "111-ngong",
        "Ngong – Railways",
        "Ngong Road",
        [
            ("Ngong Bus Terminus", "ngong", "Ngong Town"),
            ("Bulbul", "bulbul", "Bulbul"),
            ("Karen/Shell", "karen", "Karen"),
            ("Racecourse", "racecourse", "Racecourse"),
            ("Junction", "junction", "Junction (Dagoretti Corner)"),
            ("Adams Arcade", "adams-arcade", "Adams Arcade"),
            ("Kenyatta National Hospital", "knh", "Kenyatta National Hospital"),
            ("Railways", "railways", None),
        ],
    ),
    "125": (
        "125-rongai",
        "Ongata Rongai – Railways",
        "Langata Road",
        [
            ("Tumaini/Ongata Rongai", "ongata-rongai", "Ongata Rongai"),
            ("Multimedia University", "multimedia-university", "Multimedia University"),
            ("Bomas", "bomas", "Bomas"),
            ("Langata Barracks", "langata-barracks", "Langata Barracks"),
            ("Blue Sky/T-Mall", "t-mall", "T-Mall"),
            ("Nyayo Stadium", "nyayo-stadium", "Nyayo Stadium"),
            ("Railways", "railways", None),
        ],
    ),
    "23KG": (
        "23-kangemi",
        "Kangemi – Odeon",
        "Waiyaki Way",
        [
            ("Kangemi Terminus", "kangemi", "Kangemi"),
            ("Kari/James Gichuru", "james-gichuru", "James Gichuru"),
            ("Westlands Stage", "westlands", "Westlands"),
            ("Chiromo/Mirage", "chiromo", "Chiromo"),
            ("Museum", "museum-hill", "Museum Hill"),
            ("Odeon", "odeon", None),
        ],
    ),
    "105": (
        "105-kikuyu",
        "Kikuyu – Odeon",
        "Waiyaki Way",
        [
            ("Kikuyu Terminus", "kikuyu", "Kikuyu Town"),
            ("Jacmil Supermarket/Kinoo Stage", "kinoo", "Kinoo"),
            ("Uthiru Junction", "uthiru", "Uthiru"),
            ("Kangemi Market", "kangemi", "Kangemi"),
            ("ABC Place", "abc-place", "ABC Place"),
            ("Westlands/Parklands Baptist", "westlands", "Westlands"),
            ("Museum", "museum-hill", "Museum Hill"),
            ("Odeon", "odeon", None),
        ],
    ),
    "58": (
        "58-buruburu",
        "Buruburu – Kencom",
        "Jogoo Road",
        [
            ("Buru Roundabout", "buruburu", "Buruburu"),
            ("Mutindwa", "mutindwa", "Mutindwa"),
            ("Hamza", "hamza", "Hamza"),
            ("Makadara/DC Jogoo Road", "makadara", "Makadara"),
            ("City Stadium", "city-stadium", "City Stadium"),
            ("Kencom/Ambassadeur", "kencom", None),
        ],
    ),
    "34J": (
        "34-jkia",
        "JKIA – Kencom",
        "Jogoo Road",
        [
            ("JKIA - Airport Town Stage", "jkia", "JKIA"),
            ("Cabanas", "cabanas", "Cabanas"),
            ("Fedha", "fedha", "Fedha"),
            ("Donholm Interchange/Stage", "donholm", "Donholm"),
            ("Makadara/DC Jogoo Road", "makadara", "Makadara"),
            ("City Stadium", "city-stadium", "City Stadium"),
            ("Kencom/Ambassadeur", "kencom", None),
        ],
    ),
    "110": (
        "110-kitengela",
        "Kitengela – Railways",
        "Mombasa Road",
        [
            ("Kitengela", "kitengela", "Kitengela"),
            ("Mlolongo", "mlolongo", "Mlolongo"),
            ("Syokimau", "syokimau", "Syokimau"),
            ("GM", "general-motors", "General Motors (GM)"),
            ("Belleview", "belleview", "Belleview"),
            ("Nyayo", "nyayo-stadium", "Nyayo Stadium"),
            ("Easy Coach/Uchumi", "railways", None),
        ],
    ),
    "46K": (
        "46-kawangware",
        "Kawangware – Kencom",
        "Valley Road",
        [
            ("Kawangware Mwisho", "kawangware", "Kawangware"),
            ("Congo", "congo", "Congo"),
            ("Valley Arcade", "valley-arcade", "Valley Arcade"),
            ("Yaya", "yaya", "Yaya Centre"),
            ("Hurlingham", "hurlingham", "Hurlingham"),
            ("GPO", "gpo", "GPO"),
            ("Kencom/Ambassadeur", "kencom", None),
        ],
    ),
    "14A": (
        "14a-strathmore",
        "Strathmore – Bus Station",
        "Langata Road",
        [
            ("Strathmore University/Siwaka", "strathmore", "Strathmore University"),
            ("Madaraka Shopping Centre", "madaraka", "Madaraka"),
            ("Nyayo Stadium", "nyayo-stadium", "Nyayo Stadium"),
            ("Bus Station", "bus-station", "Bus Station"),
        ],
    ),
}


def table(folder, name):
    for suffix in (".txt", ".csv"):
        path = folder / (name + suffix)
        if path.exists():
            return pd.read_csv(path, dtype="string")
    raise FileNotFoundError(f"{name} table not found in {folder}")


def distance(a, b):
    return math.hypot(a[0] - b[0], (a[1] - b[1]) * math.cos(math.radians(a[0])))


def build(folder):
    routes, trips = table(folder, "routes"), table(folder, "trips")
    stop_times, stops = table(folder, "stop_times"), table(folder, "stops")
    stop_times["stop_sequence"] = stop_times["stop_sequence"].astype(int)
    stages = {ident: {"id": ident, "name": name} for ident, name in LEGACY_STAGES.items()}
    built = [LEGACY_ROUTE]
    for short, (route_id, name, corridor, wanted) in ROUTES.items():
        source = routes.loc[routes["route_short_name"].str.strip() == short, "route_id"].iloc[0]
        trip = trips.loc[
            (trips["route_id"] == source) & (trips["direction_id"] == "0"), "trip_id"
        ].iloc[0]
        sequence = (
            stop_times.loc[stop_times["trip_id"] == trip]
            .sort_values("stop_sequence")
            .merge(stops, on="stop_id", validate="many_to_one")
        )
        sequence["stop_name"] = sequence["stop_name"].str.strip()
        ordered = []
        for feed_name, stage_id, display in wanted:
            match = sequence.loc[sequence["stop_name"] == feed_name]
            if match.empty:
                raise ValueError(f"{short}: stop {feed_name!r} is not on the feed trip")
            row = match.iloc[0]
            position = (float(row["stop_lat"]), float(row["stop_lon"]))
            stage = stages.setdefault(stage_id, {"id": stage_id, "name": display or stage_id})
            # The first route to place a shared stage fixes its coordinates.
            stage.setdefault("latitude", round(position[0], 6))
            stage.setdefault("longitude", round(position[1], 6))
            ordered.append((int(row["stop_sequence"]), stage_id, position))
        ordered.sort()
        # Publish every route from its town terminus outward.
        if distance(ordered[0][2], CBD) > distance(ordered[-1][2], CBD):
            ordered.reverse()
        built.append(
            {
                "id": route_id,
                "name": name,
                "short_name": short.rstrip("KGJ") if short in {"23KG", "34J", "46K"} else short,
                "corridor": corridor,
                "stage_ids": [stage_id for _, stage_id, _ in ordered],
                "directions": ["townbound", "outbound"],
                "source": "digital-matatus-2019",
            }
        )
    return {
        "version": "2026.11-network-1",
        "attribution": (
            "Stage names, coordinates and route numbers: Digital Matatus 2019 GTFS feed "
            "(digitalmatatus.com), a historical mapped network. Arrival times served with "
            "this catalogue are simulated unless a model report says otherwise."
        ),
        "stages": sorted(stages.values(), key=lambda s: s["id"]),
        "routes": built,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gtfs", type=Path, default=DEFAULT_GTFS)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    arguments = parser.parse_args()
    catalog = build(arguments.gtfs)
    arguments.output.write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")
    print(f"{len(catalog['stages'])} stages, {len(catalog['routes'])} routes -> {arguments.output}")
