"""Minute-grid residual-wait labels from continuous enumerator sessions."""

import csv
import hashlib
import importlib.metadata
import json
import platform
from collections import defaultdict
from datetime import UTC, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import joblib
import numpy as np
from quantile_forest import RandomForestQuantileRegressor
from sklearn.feature_extraction import DictVectorizer
from sklearn.metrics import mean_pinball_loss

NAIROBI = ZoneInfo("Africa/Nairobi")
KEYS = ("stage_id", "route_id", "direction")


def key(record):
    return tuple(record[k] for k in KEYS)


def timestamp(value):
    dt = datetime.fromisoformat(value)
    if dt.tzinfo is None:
        raise ValueError("Timezone-aware timestamps are required")
    return dt.astimezone(UTC)


def features(group, at, elapsed, headway):
    local = at.astimezone(NAIROBI)
    return dict(
        zip(KEYS, group),
        hour=local.hour + local.minute / 60,
        weekday=local.weekday(),
        elapsed_minutes=elapsed,
        last_headway_minutes=headway,
    )


def build_samples(records):
    sessions = defaultdict(list)
    seen = set()
    for record in records:
        if record.get("event_type", "arrival") != "arrival":
            continue
        group = key(record)
        at = timestamp(record["observed_at"])
        unique = (record["session_id"], group, at)
        if unique in seen:
            raise ValueError("Duplicate arrival timestamp in a session")
        seen.add(unique)
        sessions[(record["session_id"], group)].append(at)
    samples = []
    for (session_id, group), times in sessions.items():
        times.sort()
        for previous, current, following in zip(times, times[1:], times[2:]):
            if (
                max((current - previous).total_seconds(), (following - current).total_seconds())
                > 7200
            ):
                continue
            # Never bridge midnight, observation sessions, or an unobserved session tail.
            day = current.astimezone(NAIROBI).date()
            if previous.astimezone(NAIROBI).date() != day:
                continue
            if following.astimezone(NAIROBI).date() != day:
                continue
            headway = (current - previous).total_seconds() / 60
            at = current + timedelta(minutes=1)
            while at < following:
                elapsed = (at - current).total_seconds() / 60
                samples.append(
                    {
                        "group": group,
                        "day": day.isoformat(),
                        "features": features(group, at, elapsed, headway),
                        "wait": (following - at).total_seconds() / 60,
                        "query_at": at.isoformat(),
                        "session_id": session_id,
                    }
                )
                at += timedelta(minutes=1)
    return samples


def new_forest():
    return RandomForestQuantileRegressor(
        n_estimators=100,
        min_samples_leaf=5,
        max_samples_leaf=None,
        max_features=1.0,
        random_state=42,
        n_jobs=1,
    )


def train(records, output: Path, *, min_samples=30, min_days=3, test_days=1, diagnostics=True):
    if min_samples < 2 or min_days < 2 or test_days < 1:
        raise ValueError("Require min_samples >= 2, min_days >= 2, test_days >= 1")
    records = [r for r in records if r.get("event_type", "arrival") == "arrival"]
    if not records:
        raise ValueError("Need completed observations across training days and test days")
    kinds = {r.get("data_kind", "unspecified") for r in records}
    if len(kinds) != 1:
        raise ValueError("Do not mix field, synthetic or unspecified data in one model")
    if not kinds <= {"field", "synthetic", "unspecified"}:
        raise ValueError("Unknown dataset provenance")
    samples = build_samples(records)
    days = sorted({s["day"] for s in samples})
    if len(days) < min_days + test_days:
        raise ValueError(f"Need at least {min_days} training days and {test_days} later test days")
    cutoff = days[-test_days]
    earlier = [s for s in samples if s["day"] < cutoff]
    later = [s for s in samples if s["day"] >= cutoff]
    groups = defaultdict(list)
    for sample in earlier:
        groups[sample["group"]].append(sample)
    supported = {
        g: rows
        for g, rows in groups.items()
        if len(rows) >= min_samples and len({r["day"] for r in rows}) >= min_days
    }
    earlier = [s for s in earlier if s["group"] in supported]
    later = [s for s in later if s["group"] in supported]
    if not earlier or not later:
        raise ValueError("Insufficient training or held-out evidence for supported groups")
    vectorizer = DictVectorizer(sparse=False)
    x_train = vectorizer.fit_transform([s["features"] for s in earlier])
    y_train = np.array([s["wait"] for s in earlier])
    model = new_forest()
    model.fit(x_train, y_train)
    x_test = vectorizer.transform([s["features"] for s in later])
    low, median, high = model.predict(x_test, quantiles=[0.1, 0.5, 0.9]).T
    truth = np.array([s["wait"] for s in later])
    historical = {g: float(np.median([r["wait"] for r in rows])) for g, rows in supported.items()}
    historical_pred = np.array([historical[s["group"]] for s in later])
    last_headway = np.array(
        [
            max(0, s["features"]["last_headway_minutes"] - s["features"]["elapsed_minutes"])
            for s in later
        ]
    )
    digest = hashlib.sha256(json.dumps(records, sort_keys=True).encode()).hexdigest()
    config = {
        "min_samples": min_samples,
        "min_days": min_days,
        "test_days": test_days,
        "n_estimators": 100,
        "min_samples_leaf": 5,
        "max_features": 1.0,
        "max_samples_leaf": "all",
        "random_state": 42,
        "label_grid_minutes": 1,
        "maximum_interval_minutes": 120,
    }
    version = hashlib.sha256((digest + json.dumps(config, sort_keys=True)).encode()).hexdigest()[
        :16
    ]
    report = {
        "model_version": version,
        "data_kind": next(iter(kinds)),
        "dataset_ids": sorted({r.get("dataset_id", "unregistered") for r in records}),
        "data_sha256": digest,
        "configuration": config,
        "software": {
            "python": platform.python_version(),
            **{
                name: importlib.metadata.version(name)
                for name in ["quantile-forest", "scikit-learn", "numpy"]
            },
        },
        "input_arrivals": len(records),
        "input_sessions": len({r["session_id"] for r in records}),
        "train_sessions": len({s["session_id"] for s in earlier}),
        "test_sessions": len({s["session_id"] for s in later}),
        "train_days": sorted({s["day"] for s in earlier}),
        "test_days": sorted({s["day"] for s in later}),
        "train_samples": len(earlier),
        "test_samples": len(later),
        "excluded_test_samples": sum(
            s["group"] not in supported for s in samples if s["day"] >= cutoff
        ),
        "mae_minutes": float(np.mean(abs(truth - median))),
        "historical_median_mae_minutes": float(np.mean(abs(truth - historical_pred))),
        "last_headway_mae_minutes": float(np.mean(abs(truth - last_headway))),
        "nominal_coverage": 0.8,
        "empirical_coverage": float(np.mean((truth >= low) & (truth <= high))),
        "mean_interval_width_minutes": float(np.mean(high - low)),
        "pinball_loss": {
            str(q): float(mean_pinball_loss(truth, pred, alpha=q))
            for q, pred in [(0.1, low), (0.5, median), (0.9, high)]
        },
        "target": "Minutes until next observed arrival at a minute-grid query time",
        "limitations": "Uncalibrated pilot intervals; no seat guarantee; complete sessions only",
    }
    report["per_group"] = []
    for group in supported:
        mask = np.array([s["group"] == group for s in later])
        if not mask.any():
            continue
        report["per_group"].append(
            {
                **dict(zip(KEYS, group)),
                "test_samples": int(mask.sum()),
                "mae_minutes": float(np.mean(abs(truth[mask] - median[mask]))),
                "empirical_coverage": float(
                    np.mean((truth[mask] >= low[mask]) & (truth[mask] <= high[mask]))
                ),
            }
        )
    report["per_day"] = [
        {
            "day": day,
            "test_samples": sum(s["day"] == day for s in later),
            "mae_minutes": float(
                np.mean(
                    [abs(s["wait"] - pred) for s, pred in zip(later, median) if s["day"] == day]
                )
            ),
        }
        for day in report["test_days"]
    ]
    if diagnostics:
        # A negative control, not a formal significance test.
        shuffled = new_forest().fit(x_train, np.random.default_rng(42).permutation(y_train))
        shuffled_pred = shuffled.predict(x_test, quantiles=0.5)
        report["shuffled_label_mae_minutes"] = float(np.mean(abs(truth - shuffled_pred)))
        report["learning_curve"] = []
        train_dates = report["train_days"]
        for count in sorted({min_days, max(min_days, len(train_dates) // 2), len(train_dates)}):
            selected = set(train_dates[:count])
            mask = np.array([s["day"] in selected for s in earlier])
            subset = new_forest().fit(x_train[mask], y_train[mask])
            prediction = subset.predict(x_test, quantiles=0.5)
            report["learning_curve"].append(
                {
                    "training_days": count,
                    "training_samples": int(mask.sum()),
                    "train_mae_minutes": float(
                        np.mean(abs(y_train[mask] - subset.predict(x_train[mask], quantiles=0.5)))
                    ),
                    "test_mae_minutes": float(np.mean(abs(truth - prediction))),
                }
            )
        # Correlated features and permutation-created combinations limit causal interpretation.
        names = vectorizer.get_feature_names_out()
        importance = []
        rng = np.random.default_rng(43)
        for feature in [*KEYS, "hour", "weekday", "elapsed_minutes", "last_headway_minutes"]:
            columns = [
                i
                for i, name in enumerate(names)
                if name == feature or name.startswith(feature + "=")
            ]
            changes = []
            for repeat in range(3):
                changed = x_test.copy()
                permutation = rng.permutation(len(changed))
                changed[:, columns] = x_test[permutation][:, columns]
                pred = model.predict(changed, quantiles=0.5)
                changes.append(float(np.mean(abs(truth - pred))) - report["mae_minutes"])
            importance.append(
                {
                    "feature": feature,
                    "mae_increase_minutes": float(np.mean(changes)),
                    "std_minutes": float(np.std(changes)),
                }
            )
        report["permutation_importance"] = sorted(
            importance, key=lambda row: row["mae_increase_minutes"], reverse=True
        )
    artifact = {
        "model": model,
        "vectorizer": vectorizer,
        "report": report,
        "trained_at": datetime.now(UTC).isoformat(),
        "groups": {
            g: {
                "samples": len(rows),
                "min_elapsed": min(r["features"]["elapsed_minutes"] for r in rows),
                "max_elapsed": max(r["features"]["elapsed_minutes"] for r in rows),
                "min_headway": min(r["features"]["last_headway_minutes"] for r in rows),
                "max_headway": max(r["features"]["last_headway_minutes"] for r in rows),
                "hours": sorted({int(r["features"]["hour"]) for r in rows}),
                "weekdays": sorted({r["features"]["weekday"] for r in rows}),
            }
            for g, rows in supported.items()
        },
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    joblib.dump(artifact, output)
    output.with_suffix(".metrics.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    with output.with_suffix(".predictions.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=[
                *KEYS,
                "session_id",
                "query_at",
                "day",
                "actual_wait_minutes",
                "lower_minutes",
                "median_minutes",
                "upper_minutes",
                "historical_median_minutes",
                "last_headway_minutes",
            ],
        )
        writer.writeheader()
        for s, lo, med, hi, hist, last in zip(
            later, low, median, high, historical_pred, last_headway
        ):
            writer.writerow(
                {
                    **dict(zip(KEYS, s["group"])),
                    "session_id": s["session_id"],
                    "query_at": s["query_at"],
                    "day": s["day"],
                    "actual_wait_minutes": s["wait"],
                    "lower_minutes": lo,
                    "median_minutes": med,
                    "upper_minutes": hi,
                    "historical_median_minutes": hist,
                    "last_headway_minutes": last,
                }
            )
    return report
