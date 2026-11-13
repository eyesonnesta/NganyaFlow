import json
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from test_backend import SCOPE, admin_auth, synthetic_arrivals

from nganyaflow.ml import train
from nganyaflow.synthetic import generate

pytest_plugins = ["test_backend"]


def bundle():
    now = datetime.now(UTC)
    sid = str(uuid4())
    return {
        "metadata": {"dataset_id": "test-synthetic", "data_kind": "synthetic"},
        "sessions": [
            {
                **SCOPE,
                "id": sid,
                "started_at": (now - timedelta(hours=1)).isoformat(),
                "ended_at": (now - timedelta(minutes=1)).isoformat(),
                "dataset_id": "test-synthetic",
                "data_kind": "synthetic",
            }
        ],
        "observations": [
            {
                "id": str(uuid4()),
                "session_id": sid,
                "observed_at": (now - timedelta(minutes=10)).isoformat(),
                "event_type": "arrival",
            }
        ],
    }


def upload(client, payload):
    return client.post(
        "/admin/datasets/import",
        auth=admin_auth(client),
        data={"csrf": client.app.state.admin_csrf, "payload": json.dumps(payload)},
    )


def test_import_is_authenticated_validated_and_idempotent(client):
    payload = bundle()
    assert client.post("/admin/datasets/import", data={}).status_code == 401
    assert (
        client.post("/admin/datasets/import", auth=admin_auth(client), data={}).status_code == 403
    )
    assert upload(client, payload).status_code == 200
    assert "Import complete" in upload(client, payload).text
    rows = client.app.state.store.arrivals(closed_only=True)
    assert len(rows) == 1 and rows[0]["data_kind"] == "synthetic"
    assert "Synthetic validation workspace" in client.get("/admin", auth=admin_auth(client)).text


def test_conflicting_bundle_rolls_back_new_sessions(client):
    payload = bundle()
    assert upload(client, payload).status_code == 200
    second = bundle()
    second["sessions"] = payload["sessions"] + second["sessions"]
    second["observations"] = [dict(payload["observations"][0], occupancy="full")] + second[
        "observations"
    ]
    assert upload(client, second).status_code == 409
    with client.app.state.store.connect() as db:
        assert db.execute("SELECT COUNT(*) FROM sessions").fetchone()[0] == 1
        assert db.execute("SELECT COUNT(*) FROM observations").fetchone()[0] == 1


def test_bundle_rejects_false_provenance_and_outside_timestamps(client):
    payload = bundle()
    payload["sessions"][0]["data_kind"] = "field"
    assert upload(client, payload).status_code == 422
    payload = bundle()
    payload["observations"][0]["observed_at"] = (datetime.now(UTC) - timedelta(days=2)).isoformat()
    assert upload(client, payload).status_code == 422
    assert not client.app.state.store.arrivals()


def test_training_separates_provenance_and_records_learning_evidence(tmp_path):
    rows = synthetic_arrivals()
    for row in rows:
        row["data_kind"] = "synthetic"
        row["dataset_id"] = "test-synthetic"
    report = train(rows, tmp_path / "evidence.joblib", test_days=2)
    assert report["data_kind"] == "synthetic"
    assert len(report["test_days"]) == 2
    assert set(report["train_days"]).isdisjoint(report["test_days"])
    assert report["permutation_importance"] and report["learning_curve"]
    assert report["shuffled_label_mae_minutes"] >= 0
    assert len(report["pinball_loss"]) == 3
    assert (tmp_path / "evidence.predictions.csv").exists()
    rows[0]["data_kind"] = "field"
    with pytest.raises(ValueError, match="Do not mix"):
        train(rows, tmp_path / "mixed.joblib")


def test_synthetic_generator_reproduces_real_place_labels_not_field_claims():
    first = generate()
    assert first == generate()
    assert len(first["sessions"]) == 84
    assert {s["stage_id"] for s in first["sessions"]} == {"kencom", "odeon", "railways"}
    assert first["metadata"]["data_kind"] == "synthetic"
    assert all(s["data_kind"] == "synthetic" for s in first["sessions"])


@pytest.mark.parametrize("value", ["0", "8", "not-a-number"])
def test_invalid_holdout_cannot_start_training(client, value):
    response = client.post(
        "/admin/train",
        auth=admin_auth(client),
        data={"csrf": client.app.state.admin_csrf, "test_days": value},
    )
    assert response.status_code == 422
    assert client.app.state.training_status == "idle"
