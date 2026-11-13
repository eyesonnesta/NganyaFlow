import json
import re
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from nganyaflow.config import Settings
from nganyaflow.main import create_app
from nganyaflow.ml import build_samples, train


@pytest.fixture
def client(tmp_path):
    catalog = tmp_path / "catalog.json"
    catalog.write_text(
        json.dumps(
            {
                "stages": [{"id": "test-stage", "name": "Synthetic test stage"}],
                "routes": [
                    {
                        "id": "test-route",
                        "name": "Synthetic test route",
                        "stage_ids": ["test-stage"],
                        "directions": ["townbound"],
                    }
                ],
            }
        ),
        encoding="utf-8",
    )
    settings = Settings(
        database=tmp_path / "db.sqlite3",
        catalog=catalog,
        runtime_dir=tmp_path / "runtime",
        write_token="test-only",
        _env_file=None,
    )
    with TestClient(create_app(settings)) as instance:
        yield instance


AUTH = {"X-Write-Token": "test-only"}
SCOPE = {"stage_id": "test-stage", "route_id": "test-route", "direction": "townbound"}


def session(client):
    body = dict(
        SCOPE, id=str(uuid4()), started_at=(datetime.now(UTC) - timedelta(hours=1)).isoformat()
    )
    assert client.post("/api/v1/sessions", json=body, headers=AUTH).status_code == 200
    return body


def event(session_id, minutes=10):
    return {
        "id": str(uuid4()),
        "session_id": session_id,
        "event_type": "arrival",
        "observed_at": (datetime.now(UTC) - timedelta(minutes=minutes)).isoformat(),
    }


def test_auth_catalog_and_no_fabricated_estimate(client):
    assert client.get("/health").json()["status"] == "ok"
    assert client.get("/api/v1/stages").json()[0]["id"] == "test-stage"
    assert client.post("/api/v1/sessions", json={}).status_code == 401
    assert client.get("/api/v1/estimates", params=SCOPE).json()["reason"] == "insufficient_evidence"
    assert (
        client.get("/api/v1/estimates", params=dict(SCOPE, stage_id="unknown")).status_code == 404
    )


def test_sync_idempotence_conflict_and_rollback(client):
    s = session(client)
    first = event(s["id"])
    endpoint = "/api/v1/observations/sync"
    payload = {"records": [first]}
    assert client.post(endpoint, json=payload, headers=AUTH).json()["accepted_ids"] == [first["id"]]
    assert client.post(endpoint, json=payload, headers=AUTH).json()["duplicate_ids"] == [
        first["id"]
    ]
    second = event(s["id"], 9)
    bad = dict(first, occupancy="full")
    assert client.post(endpoint, json={"records": [second, bad]}, headers=AUTH).status_code == 409
    result = client.post(endpoint, json={"records": [second]}, headers=AUTH).json()
    assert result["accepted_ids"] == [second["id"]]  # prior batch fully rolled back


def test_closed_session_boundaries_and_export(client):
    s = session(client)
    end = datetime.now(UTC) - timedelta(minutes=15)
    endpoint = f"/api/v1/sessions/{s['id']}/end"
    assert client.put(endpoint, json={"ended_at": end.isoformat()}, headers=AUTH).status_code == 200
    assert (
        client.post(
            "/api/v1/observations/sync", json={"records": [event(s["id"], 20)]}, headers=AUTH
        ).status_code
        == 200
    )
    assert len(client.app.state.store.arrivals(closed_only=True)) == 1
    assert (
        client.post(
            "/api/v1/observations/sync", json={"records": [event(s["id"], 10)]}, headers=AUTH
        ).status_code
        == 422
    )


@pytest.mark.parametrize("timestamp", ["2026-01-01T10:00:00", "2099-01-01T10:00:00Z"])
def test_reject_bad_timestamps(client, timestamp):
    s = session(client)
    row = dict(event(s["id"]), observed_at=timestamp)
    assert (
        client.post("/api/v1/observations/sync", json={"records": [row]}, headers=AUTH).status_code
        == 422
    )


def test_estimate_stale_and_model_missing(client):
    s = session(client)
    assert (
        client.post(
            "/api/v1/observations/sync", json={"records": [event(s["id"], 40)]}, headers=AUTH
        ).status_code
        == 200
    )
    assert client.get("/api/v1/estimates", params=SCOPE).json()["reason"] == "stale_evidence"
    rows = [event(s["id"], m) for m in [12, 6, 2]]
    assert (
        client.post("/api/v1/observations/sync", json={"records": rows}, headers=AUTH).status_code
        == 200
    )
    assert client.get("/api/v1/estimates", params=SCOPE).json()["reason"] == "model_not_trained"


def synthetic_arrivals():
    rows = []
    for day in range(5):
        start = datetime(2026, 1, 5, 7, tzinfo=UTC) + timedelta(days=day)
        for arrival in range(8):
            rows.append(
                dict(
                    SCOPE,
                    session_id=f"synthetic-{day}",
                    observed_at=(start + timedelta(minutes=arrival * 6)).isoformat(),
                )
            )
    return rows


def test_labels_never_cross_sessions_or_midnight():
    rows = synthetic_arrivals()
    samples = build_samples(rows)
    assert len(samples) == 5 * 6 * 5
    assert all(0 < s["wait"] <= 5 for s in samples)
    with pytest.raises(ValueError, match="Duplicate"):
        build_samples(rows + [rows[0]])
    midnight = [
        dict(SCOPE, session_id="night", observed_at=t)
        for t in [
            "2026-01-01T23:40:00+03:00",
            "2026-01-01T23:50:00+03:00",
            "2026-01-02T00:10:00+03:00",
        ]
    ]
    assert build_samples(midnight) == []


def test_training_real_quantile_forest_and_disjoint_days(tmp_path):
    report = train(synthetic_arrivals(), tmp_path / "model.joblib")
    assert max(report["train_days"]) < min(report["test_days"])
    assert report["nominal_coverage"] == 0.8
    assert 0 <= report["empirical_coverage"] <= 1
    assert (tmp_path / "model.metrics.json").exists()
    with pytest.raises(ValueError, match="training days"):
        train(synthetic_arrivals()[:8], tmp_path / "bad.joblib")


def admin_auth(client):
    return ("admin", client.app.state.admin_password)


def test_admin_auth_persistent_secret_csrf_and_training_failure(client):
    assert "Sign in to administration" in client.get("/admin").text
    assert client.get("/admin", auth=("admin", "wrong")).status_code == 401
    response = client.get("/admin", auth=admin_auth(client))
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"
    assert client.app.state.admin_password not in response.text
    token = re.search(r'name="csrf"\s+value="([^"]+)"', response.text)[1]
    assert client.post("/admin/train", auth=admin_auth(client)).status_code == 403
    assert (
        client.post("/admin/activate", data={"csrf": token}, auth=admin_auth(client)).status_code
        == 409
    )
    assert (
        client.post("/admin/train", data={"csrf": token}, auth=admin_auth(client)).status_code
        == 200
    )
    assert client.app.state.training_status == "failed"
    assert "training days" in client.app.state.training_error
    path = client.app.state.active_model_path.parent / "admin-password.txt"
    assert path.read_text("utf-8").strip() == client.app.state.admin_password


def test_prediction_and_admin_activation(client, tmp_path):
    # Build real model, using historical days matching today's weekday/hour for support.
    now = datetime.now(UTC)
    rows = []
    for week in [4, 3, 2, 1]:
        start = now - timedelta(weeks=week, minutes=30)
        for i in range(15):
            rows.append(
                dict(
                    SCOPE,
                    session_id=f"synthetic-week-{week}",
                    observed_at=(start + timedelta(minutes=i * 5)).isoformat(),
                )
            )
    train(rows, client.app.state.candidate)
    auth = admin_auth(client)
    token = client.app.state.admin_csrf
    assert client.post("/admin/activate", data={"csrf": token}, auth=auth).status_code == 200
    s = session(client)
    recent = [
        dict(event(s["id"]), observed_at=(now - timedelta(minutes=m)).isoformat())
        for m in [12, 7, 2]
    ]
    assert (
        client.post("/api/v1/observations/sync", json={"records": recent}, headers=AUTH).status_code
        == 200
    )
    result = client.get("/api/v1/estimates", params=SCOPE).json()
    assert result["status"] == "available", result
    assert 0 <= result["lower_minutes"] <= result["median_minutes"] <= result["upper_minutes"]
    assert result["expires_at"] and result["model_version"]
    client.app.state.model["trained_at"] = (now - timedelta(days=31)).isoformat()
    assert client.get("/api/v1/estimates", params=SCOPE).json()["reason"] == "model_expired"


def test_password_persists_and_writes_disabled(client):
    settings = Settings(
        database=client.app.state.store.path,
        catalog=client.app.state.store.path.parent / "catalog.json",
        runtime_dir=client.app.state.active_model_path.parent,
        write_token="",
        _env_file=None,
    )
    original = client.app.state.admin_password
    with TestClient(create_app(settings)) as restarted:
        assert restarted.app.state.admin_password == original
        body = dict(
            SCOPE,
            id=str(uuid4()),
            started_at=(datetime.now(UTC) - timedelta(minutes=1)).isoformat(),
        )
        assert restarted.post("/api/v1/sessions", json=body).status_code == 503


def test_admin_form_login_logout_and_expiry(client):
    page = client.get("/admin")
    assert page.url.path == "/admin/login"
    token = re.search(r'name="csrf" value="([^"]+)"', page.text)[1]
    assert (
        client.post("/admin/login", data={"username": "admin", "password": "bad"}).status_code
        == 403
    )
    form = {"csrf": token, "username": "admin", "password": "bad"}
    assert client.post("/admin/login", data=form).status_code == 401
    form["password"] = client.app.state.admin_password
    result = client.post("/admin/login", data=form)
    assert result.status_code == 200 and "Collected observations" in result.text
    assert "nganyaflow_admin" in client.cookies
    assert client.post("/admin/train").status_code == 403
    assert (
        client.post("/admin/logout", data={"csrf": client.app.state.admin_csrf}).status_code == 200
    )
    assert "nganyaflow_admin" not in client.cookies
    client.post("/admin/login", data=form)
    token = client.cookies["nganyaflow_admin"]
    client.app.state.admin_sessions[token] = 0
    assert client.get("/admin").url.path == "/admin/login"
