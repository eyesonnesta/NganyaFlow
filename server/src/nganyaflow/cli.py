import argparse
import csv
import json
import os
from pathlib import Path

from .config import Settings
from .storage import Store


def seed_demo(args):
    import shutil
    from datetime import UTC, datetime, timedelta

    from . import access
    from .demo import seed_history
    from .schemas import Catalog
    from .storage import Conflict

    settings = Settings()
    catalog = Catalog.model_validate_json(settings.resolve(settings.catalog).read_text("utf-8"))
    store = Store(settings.resolve(settings.database))
    runtime = settings.resolve(settings.runtime_dir)
    runtime.mkdir(parents=True, exist_ok=True)
    print("Seeding synthetic history ...", flush=True)
    result = seed_history(store, catalog, runtime, days=args.days)
    print(f"  {result}", flush=True)
    if not args.skip_training:
        from .ml import train

        print("Training the quantile forest (a few minutes) ...", flush=True)
        candidate = runtime / "candidate.joblib"
        rows = store.arrivals(closed_only=True)
        report = train(rows, candidate, test_days=args.test_days, diagnostics=False)
        shutil.copy2(candidate, runtime / "active.joblib")
        print(
            f"  model {report['model_version']}: MAE {report['mae_minutes']:.2f} min, "
            f"80% interval coverage {report['empirical_coverage']:.2f} "
            f"(baseline MAE {report['historical_median_mae_minutes']:.2f})",
            flush=True,
        )
    expires = datetime.now(UTC) + timedelta(days=args.code_days)
    lines = []
    for role, wanted in (("enumerator", args.enumerator_code), ("crew", args.crew_code)):
        code = wanted or access.generate_code()
        try:
            store.add_code(access.hash_code(code), role, "demo seed", expires)
            lines.append(f"{role}: {code}")
        except Conflict:
            lines.append(f"{role}: {code} (already registered)")
    # Keep codes out of logs; POSIX modes restrict the file to its owner.
    # On Windows, protection relies on the runtime folder's permissions.
    note = runtime / "demo-access-codes.txt"
    note.touch(mode=0o600, exist_ok=True)
    if os.name == "posix":
        note.chmod(0o600)
    note.write_text("\n".join(lines) + f"\nvalid until {expires.isoformat()}\n", "utf-8")
    print(f"Access codes for enumerator and crew saved to {note} (not shown here).")
    if args.show_codes:
        print("\n".join("  " + line for line in lines))


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
    seed = commands.add_parser(
        "seed-demo",
        help="Load synthetic history for every catalogue route, train, activate, create codes",
    )
    seed.add_argument("--days", type=int, default=12)
    seed.add_argument("--test-days", type=int, default=2)
    seed.add_argument("--code-days", type=int, default=30, help="Validity of the access codes")
    seed.add_argument("--enumerator-code", help="Use this code instead of a random one")
    seed.add_argument("--crew-code", help="Use this code instead of a random one")
    seed.add_argument("--skip-training", action="store_true")
    seed.add_argument(
        "--show-codes",
        action="store_true",
        help="Also print the codes. Only for a private terminal, never where output is logged",
    )
    args = parser.parse_args()
    if args.command == "seed-demo":
        seed_demo(args)
        return
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
            "vehicle_label",
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
