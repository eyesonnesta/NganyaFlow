import argparse
import csv
import json
from pathlib import Path

from .config import Settings
from .storage import Store


def main():
    parser = argparse.ArgumentParser(description="NganyaFlow local research commands")
    commands = parser.add_subparsers(dest="command", required=True)
    export = commands.add_parser("export", help="Export arrivals from completed sessions")
    export.add_argument("--output", type=Path, required=True)
    training = commands.add_parser(
        "train", help="Train and evaluate using earlier/later whole days"
    )
    training.add_argument("--input", type=Path, required=True)
    training.add_argument("--output", type=Path, required=True)
    training.add_argument("--min-samples", type=int, default=30)
    training.add_argument("--min-days", type=int, default=3)
    training.add_argument("--test-days", type=int, default=1)
    args = parser.parse_args()
    if args.command == "export":
        settings = Settings()
        rows = Store(settings.resolve(settings.database)).arrivals(closed_only=True)
        columns = [
            "id",
            "session_id",
            "stage_id",
            "route_id",
            "direction",
            "observed_at",
            "event_type",
            "occupancy",
            "data_kind",
            "dataset_id",
        ]
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=columns, extrasaction="ignore")
            writer.writeheader()
            writer.writerows(rows)
        print(f"Exported {len(rows)} arrivals from completed sessions")
    else:
        from .ml import train

        with args.input.open(newline="", encoding="utf-8-sig") as handle:
            records = list(csv.DictReader(handle))
        print(
            json.dumps(
                train(
                    records,
                    args.output,
                    min_samples=args.min_samples,
                    min_days=args.min_days,
                    test_days=args.test_days,
                ),
                indent=2,
            )
        )


if __name__ == "__main__":
    main()
