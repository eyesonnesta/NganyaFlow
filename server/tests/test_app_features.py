"""Access codes, rider reports, deletion, crew demand, crowding and the demo data."""

import json
import threading
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from nganyaflow import access
from nganyaflow.config import Settings
from nganyaflow.demo import generate_history, regime, run_live_simulator, served_groups
from nganyaflow.main import create_app, served_directions
from nganyaflow.ml import train
from nganyaflow.schemas import Catalog

CONSENT = "2026-11-v1"
REAL_CATALOG = Path(__file__).resolve().parents[1] / "data/reference/catalog.json"


@pytest.fixture
def api(tmp_path):
    catalog = tmp_path / "catalog.json"
    catalog.write_text(
        json.dumps(
            {
                "version": "test",
                "stages": [
                    {"id": "town", "name": "Town", "latitude": -1.28, "longitude": 36.82},
                    {"id": "mid", "name": "Middle", "latitude": -1.25, "longitude": 36.85},
                    {"id": "far", "name": "Far", "latitude": -1.2, "longitude": 36.9},
                ],
                "routes": [
                    {
                        "id": "r1",
                        "name": "Test route",
                        "short_name": "1",
                        "stage_ids": ["town", "mid", "far"],
                        "directions": ["townbound", "outbound"],
                        "source": "digital-matatus-2019",
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
        write_token="service-secret",
        _env_file=None,
    )
    with TestClient(create_app(settings)) as client:
        yield client


def bearer(api, role, *, code=None, days=7):
    code = code or access.generate_code()
    api.app.state.store.add_code(
        access.hash_code(code), role, "test", datetime.now(UTC) + timedelta(days=days)
    )
    response = api.post("/api/v1/auth/redeem", json={"code": code})
    assert response.status_code == 200, response.text
    return {"Authorization": "Bearer " + response.json()["token"]}


def report(stage="mid", direction="townbound", install=None, minutes_ago=5, **extra):
    return {
        "id": str(uuid4()),
        "install_id": install or str(uuid4()),
        "stage_id": stage,
        "route_id": "r1",
        "direction": direction,
        "boarded_at": (datetime.now(UTC) - timedelta(minutes=minutes_ago)).isoformat(),
        "consent_version": CONSENT,
        **extra,
    }


# ---- access codes -----------------------------------------------------------------------


def test_redeem_roles_expiry_revocation_and_legacy_token(api):
    assert api.post("/api/v1/auth/redeem", json={"code": "NOT-A-CODE"}).status_code == 401
    enum, crew = bearer(api, "enumerator"), bearer(api, "crew")
    assert api.get("/api/v1/auth/me", headers=enum).json() == {"role": "enumerator"}
    started = (datetime.now(UTC) - timedelta(hours=1)).isoformat()
    session = {
        "id": str(uuid4()),
        "stage_id": "mid",
        "route_id": "r1",
        "direction": "townbound",
        "started_at": started,
    }
    assert api.post("/api/v1/sessions", json=session, headers=crew).status_code == 403
    assert api.post("/api/v1/sessions", json=session, headers=enum).status_code == 200
    demand = {"route_id": "r1", "direction": "townbound"}
    assert api.get("/api/v1/demand", params=demand, headers=enum).status_code == 403
    assert api.get("/api/v1/demand", params=demand, headers=crew).status_code == 200
    assert api.get("/api/v1/demand", params=demand).status_code == 401
    legacy = {"X-Write-Token": "service-secret"}
    assert api.get("/api/v1/demand", params=demand, headers=legacy).status_code == 200
    bad = {"Authorization": "Bearer nonsense"}
    assert api.get("/api/v1/auth/me", headers=bad).status_code == 401
    # Revoking a code ends the tokens it issued.
    rows = api.app.state.store.codes()
    for row in rows:
        api.app.state.store.revoke_code(row["id"])
    assert api.get("/api/v1/auth/me", headers=enum).status_code == 401


def test_expired_code_and_rate_limit(api):
    api.app.state.store.add_code(
        access.hash_code("OLD-CODE-1"), "crew", "old", datetime.now(UTC) - timedelta(days=1)
    )
    assert api.post("/api/v1/auth/redeem", json={"code": "OLD-CODE-1"}).status_code == 401
    for _ in range(9):
        api.post("/api/v1/auth/redeem", json={"code": "WRONG-CODE"})
    assert api.post("/api/v1/auth/redeem", json={"code": "WRONG-CODE"}).status_code == 429


def test_code_matching_ignores_case_and_dashes():
    assert access.hash_code("nf-abcd-efgh") == access.hash_code("NFABCDEFGH")
    code = access.generate_code()
    assert code.startswith("NF-") and len(access.normalize(code)) == 10


def test_admin_creates_and_revokes_codes(api):
    auth = ("admin", api.app.state.admin_password)
    csrf = api.app.state.admin_csrf
    assert api.get("/admin/access", auth=auth).status_code == 200
    assert api.post("/admin/access", auth=auth, data={}).status_code == 403
    form = {"csrf": csrf, "role": "crew", "label": "Test crew", "days": "3", "code": ""}
    created = api.post("/admin/access", auth=auth, data=form)
    assert created.status_code == 200 and "NF-" in created.text
    bad = api.post("/admin/access", auth=auth, data=dict(form, role="admin"))
    assert "Choose a role" in bad.text
    row = api.app.state.store.codes()[0]
    assert row["role"] == "crew" and not row["revoked"]
    api.post(f"/admin/access/{row['id']}/revoke", auth=auth, data={"csrf": csrf})
    assert api.app.state.store.codes()[0]["revoked"] == 1


# ---- rider reports and deletion -----------------------------------------------------------


def test_reports_are_idempotent_validated_and_retractable(api):
    install = str(uuid4())
    first = report(install=install)
    body = {"records": [first]}
    assert api.post("/api/v1/reports", json=body).json()["accepted_ids"] == [first["id"]]
    assert api.post("/api/v1/reports", json=body).json()["duplicate_ids"] == [first["id"]]
    changed = dict(first, direction="outbound")
    assert api.post("/api/v1/reports", json={"records": [changed]}).status_code == 409
    assert (
        api.post("/api/v1/reports", json={"records": [report(consent_version="old")]}).status_code
        == 422
    )
    assert (
        api.post("/api/v1/reports", json={"records": [report(stage="nowhere")]}).status_code == 404
    )
    assert (
        api.post("/api/v1/reports", json={"records": [report(minutes_ago=60 * 24 * 9)]}).status_code
        == 422
    )
    assert (
        api.post("/api/v1/reports", json={"records": [report(minutes_ago=-30)]}).status_code == 422
    )
    assert (
        api.delete(
            f"/api/v1/reports/{first['id']}", params={"install_id": str(uuid4())}
        ).status_code
        == 404
    )
    assert (
        api.delete(f"/api/v1/reports/{first['id']}", params={"install_id": install}).status_code
        == 204
    )
    # Undo is repeatable, and a delayed retry of the original upload cannot restore it.
    again = api.delete(f"/api/v1/reports/{first['id']}", params={"install_id": install})
    assert again.status_code == 204
    replay = api.post("/api/v1/reports", json=body).json()
    assert replay["accepted_ids"] == [] and replay["duplicate_ids"] == []
    assert replay["rejected"] == [{"id": first["id"], "reason": "retracted"}]
    since = datetime.now(UTC) - timedelta(hours=1)
    assert api.app.state.store.reports_for_route("r1", "townbound", since) == []
    unknown = api.delete(f"/api/v1/reports/{uuid4()}", params={"install_id": install})
    assert unknown.status_code == 404


def test_deletion_erases_and_blocks_replay_of_queued_reports(api):
    install = str(uuid4())
    old = [report(install=install, minutes_ago=m) for m in (30, 20)]
    api.post("/api/v1/reports", json={"records": old})
    request = {"id": str(uuid4()), "install_id": install}
    done = api.post("/api/v1/deletion-requests", json=request).json()
    assert done["status"] == "completed" and done["reports_deleted"] == 2
    assert api.post("/api/v1/deletion-requests", json=request).json()["reports_deleted"] == 2
    assert api.get(f"/api/v1/deletion-requests/{request['id']}").json()["status"] == "completed"
    assert api.get(f"/api/v1/deletion-requests/{uuid4()}").status_code == 404
    other = {"id": request["id"], "install_id": str(uuid4())}
    assert api.post("/api/v1/deletion-requests", json=other).status_code == 409
    # A report that was still queued on the phone must not reappear after deletion.
    queued = report(install=install, minutes_ago=10)
    result = api.post("/api/v1/reports", json={"records": [queued]}).json()
    assert result["accepted_ids"] == [] and result["rejected"][0]["reason"] == "deleted_by_request"
    # Reporting again is a fresh choice, made under a new installation ID.
    assert api.post("/api/v1/reports", json={"records": [report()]}).json()["accepted_ids"]


def test_deletion_holds_when_the_phone_clock_runs_ahead(api):
    """An uploaded report stamped ahead of server time is deleted and then retried."""
    install = str(uuid4())
    ahead = report(install=install)
    ahead["boarded_at"] = (datetime.now(UTC) + timedelta(seconds=90)).isoformat()
    body = {"records": [ahead]}
    assert api.post("/api/v1/reports", json=body).json()["accepted_ids"] == [ahead["id"]]
    request = {"id": str(uuid4()), "install_id": install}
    assert api.post("/api/v1/deletion-requests", json=request).json()["reports_deleted"] == 1
    replay = api.post("/api/v1/reports", json=body).json()
    assert replay["accepted_ids"] == [] and replay["duplicate_ids"] == []
    assert replay["rejected"][0]["reason"] == "deleted_by_request"
    # The retired ID stays retired, whatever timestamp a later report carries.
    later = report(install=install)
    later["boarded_at"] = (datetime.now(UTC) + timedelta(seconds=100)).isoformat()
    assert api.post("/api/v1/reports", json={"records": [later]}).json()["accepted_ids"] == []
    since = datetime.now(UTC) - timedelta(hours=1)
    assert api.app.state.store.reports_for_route("r1", "townbound", since) == []


def test_shared_write_token_is_off_unless_configured(tmp_path):
    catalog = tmp_path / "catalog.json"
    route = {"id": "r", "name": "R", "stage_ids": ["s"], "directions": ["townbound"]}
    catalog.write_text(
        json.dumps({"stages": [{"id": "s", "name": "S"}], "routes": [route]}), encoding="utf-8"
    )
    settings = Settings(
        database=tmp_path / "db.sqlite3",
        catalog=catalog,
        runtime_dir=tmp_path / "runtime",
        write_token="",
        _env_file=None,
    )
    params = {"route_id": "r", "direction": "townbound"}
    with TestClient(create_app(settings)) as client:
        for guess in ("change-me-before-sharing", "anything"):
            headers = {"X-Write-Token": guess}
            assert client.get("/api/v1/demand", params=params, headers=headers).status_code == 503
        # Access codes work without any shared token, and the token stays refused.
        crew = bearer(client, "crew")
        assert client.get("/api/v1/demand", params=params, headers=crew).status_code == 200
        headers = {"X-Write-Token": "change-me-before-sharing"}
        assert client.get("/api/v1/demand", params=params, headers=headers).status_code == 401


def test_compose_file_has_no_default_write_token():
    compose = (Path(__file__).resolve().parents[2] / "docker-compose.yml").read_text("utf-8")
    assert "NGANYAFLOW_WRITE_TOKEN: ${NGANYAFLOW_WRITE_TOKEN:-}" in compose
    assert "change-me" not in compose


def test_seed_demo_keeps_codes_out_of_output(tmp_path, monkeypatch, capsys):
    import stat
    from types import SimpleNamespace

    from nganyaflow import cli

    monkeypatch.setenv("NGANYAFLOW_DATABASE", str(tmp_path / "db.sqlite3"))
    monkeypatch.setenv("NGANYAFLOW_RUNTIME_DIR", str(tmp_path / "runtime"))
    args = SimpleNamespace(
        days=1,
        test_days=1,
        code_days=1,
        skip_training=True,
        show_codes=False,
        enumerator_code="NF-TEST-ENUM",
        crew_code=None,
    )
    cli.seed_demo(args)
    output = capsys.readouterr().out
    saved = tmp_path / "runtime" / "demo-access-codes.txt"
    text = saved.read_text("utf-8")
    assert "NF-TEST-ENUM" in text and "crew: NF-" in text
    assert "NF-" not in output and "demo-access-codes.txt" in output
    assert stat.S_IMODE(saved.stat().st_mode) == 0o600


def test_daily_report_limit_per_install(api):
    from nganyaflow import storage

    install = str(uuid4())
    storage.DAILY_REPORT_LIMIT = 3
    try:
        records = [report(install=install, minutes_ago=m) for m in range(1, 6)]
        result = api.post("/api/v1/reports", json={"records": records}).json()
    finally:
        storage.DAILY_REPORT_LIMIT = 300
    assert len(result["accepted_ids"]) == 3
    assert {r["reason"] for r in result["rejected"]} == {"daily_limit"}


# ---- demand, crowding and stage estimates ---------------------------------------------------


def seed_arrivals(api, stage, direction, minutes, occupancy="unknown"):
    from nganyaflow.schemas import Observation, Session

    sid = str(uuid4())
    start = datetime.now(UTC) - timedelta(hours=1)
    api.app.state.store.add_session(
        Session.model_validate(
            {
                "id": sid,
                "stage_id": stage,
                "route_id": "r1",
                "direction": direction,
                "started_at": start.isoformat(),
            }
        )
    )
    api.app.state.store.add_batch(
        [
            Observation.model_validate(
                {
                    "id": str(uuid4()),
                    "session_id": sid,
                    "observed_at": (datetime.now(UTC) - timedelta(minutes=m)).isoformat(),
                    "event_type": "arrival",
                    "occupancy": occupancy,
                }
            )
            for m in minutes
        ]
    )


def test_demand_counts_status_and_terminus_rules(api):
    crew = bearer(api, "crew")
    for _ in range(6):
        api.post("/api/v1/reports", json={"records": [report("mid")]})
    api.post("/api/v1/reports", json={"records": [report("far")]})
    seed_arrivals(api, "mid", "townbound", [20, 10], occupancy="full")
    params = {"route_id": "r1", "direction": "townbound"}
    body = api.get("/api/v1/demand", params=params, headers=crew).json()
    rows = {r["stage_id"]: r for r in body["stages"]}
    assert "town" not in rows  # nobody boards townbound at the town terminus
    assert rows["mid"]["boarding_reports"] == 6 and rows["mid"]["status"] == "ok"
    assert rows["mid"]["latest_occupancy"] == "full" and rows["mid"]["observed_arrivals"] == 2
    assert rows["far"]["status"] == "limited"
    assert "not total passengers" in body["disclaimer"]
    outbound = api.get(
        "/api/v1/demand", params=dict(params, direction="outbound"), headers=crew
    ).json()
    assert [r["stage_id"] for r in outbound["stages"]] == ["town", "mid"]
    assert {r["status"] for r in outbound["stages"]} == {"no_data"}
    assert (
        api.get("/api/v1/demand", params=dict(params, window_minutes=5), headers=crew).status_code
        == 422
    )
    assert (
        api.get(
            "/api/v1/demand", params={"route_id": "zz", "direction": "townbound"}, headers=crew
        ).status_code
        == 404
    )


def test_estimate_reports_crowding_and_stage_overview(api):
    seed_arrivals(api, "mid", "townbound", [25, 15, 5], occupancy="seats_available")
    params = {"stage_id": "mid", "route_id": "r1", "direction": "townbound"}
    estimate = api.get("/api/v1/estimates", params=params).json()
    assert estimate["occupancy"] == "seats_available" and estimate["occupancy_observed_at"]
    assert estimate["reason"] == "model_not_trained"
    overview = api.get("/api/v1/stages/mid/estimates").json()
    assert {r["direction"] for r in overview["routes"]} == {"townbound", "outbound"}
    assert api.get("/api/v1/stages/town/estimates").json()["routes"][0]["direction"] == "outbound"
    only = api.get("/api/v1/stages/far/estimates", params={"direction": "townbound"}).json()
    assert [r["direction"] for r in only["routes"]] == ["townbound"]
    assert api.get("/api/v1/stages/nowhere/estimates").status_code == 404


def test_meta_and_coordinates(api):
    meta = api.get("/api/v1/meta").json()
    assert meta["consent_version"] == CONSENT and meta["demo_mode"] is False
    stage = {s["id"]: s for s in api.get("/api/v1/stages").json()}["mid"]
    assert stage["latitude"] == -1.25 and stage["longitude"] == 36.85


# ---- catalogue and demo data ------------------------------------------------------------------


def test_real_catalogue_is_consistent():
    catalog = Catalog.model_validate_json(REAL_CATALOG.read_text("utf-8"))
    stages = {s.id: s for s in catalog.stages}
    assert len(catalog.routes) >= 13 and len(stages) >= 60
    assert all(s.latitude is not None and s.longitude is not None for s in stages.values())
    assert {"cbd-study-simulated", "237-thika", "111-ngong"} <= {r.id for r in catalog.routes}
    for route in catalog.routes:
        assert len(route.stage_ids) == len(set(route.stage_ids))
        assert set(route.stage_ids) <= set(stages)
    # Stages sit in the Nairobi region, not at the equator default.
    assert all(-1.6 < s.latitude < -0.9 and 36.5 < s.longitude < 37.3 for s in stages.values())
    thika = next(r for r in catalog.routes if r.id == "237-thika")
    assert served_directions(thika, thika.stage_ids[0]) == ["outbound"]
    assert served_directions(thika, thika.stage_ids[-1]) == ["townbound"]
    legacy = next(r for r in catalog.routes if r.id == "cbd-study-simulated")
    assert served_directions(legacy, "kencom") == ["townbound"]


def test_history_is_deterministic_synthetic_and_trainable(tmp_path):
    catalog = Catalog.model_validate_json(REAL_CATALOG.read_text("utf-8"))
    small = Catalog(
        stages=catalog.stages,
        routes=[r for r in catalog.routes if r.id in {"cbd-study-simulated", "14a-strathmore"}],
    )
    today = datetime(2026, 11, 16).date()
    first = generate_history(small, today=today)
    assert [e.model_dump_json() for e in first[2]] == [
        e.model_dump_json() for e in generate_history(small, today=today)[2]
    ]
    sessions, ends, events = first
    assert all(s.data_kind == "synthetic" for s in sessions.values())
    assert len(sessions) == len(served_groups(small)) * 12 * 2
    assert all(ends[sid] > s.started_at for sid, s in sessions.items())
    assert {e.occupancy for e in events} >= {"full", "seats_available"}
    rows = [
        {
            "session_id": str(e.session_id),
            "stage_id": sessions[str(e.session_id)].stage_id,
            "route_id": sessions[str(e.session_id)].route_id,
            "direction": sessions[str(e.session_id)].direction,
            "observed_at": e.observed_at.isoformat(),
            "data_kind": "synthetic",
            "dataset_id": "t",
        }
        for e in events
    ]
    report = train(rows, tmp_path / "m.joblib", test_days=2, diagnostics=False)
    assert report["mae_minutes"] < report["historical_median_mae_minutes"]


def test_regime_follows_peaks_and_weekends():
    group = ("kencom", "cbd-study-simulated", "townbound")
    monday_peak = datetime(2026, 11, 16, 7, tzinfo=UTC)
    monday_noon = datetime(2026, 11, 16, 12, tzinfo=UTC)
    saturday_peak = datetime(2026, 11, 21, 7, tzinfo=UTC)
    assert regime(group, monday_peak) < regime(group, monday_noon)
    assert regime(group, saturday_peak) > regime(group, monday_peak)


def test_live_simulator_adds_open_synthetic_sessions(api):
    stop = threading.Event()
    catalog = api.app.state.catalog
    worker = threading.Thread(
        target=run_live_simulator,
        args=(api.app.state.store, catalog, stop),
        kwargs={"interval": 0.05, "backfill_hours": 1},
    )
    worker.start()
    stop.wait(1.0)
    stop.set()
    worker.join(timeout=5)
    group = ("mid", "r1", "townbound")
    arrivals = api.app.state.store.arrivals_for(group)
    assert len(arrivals) >= 3 and {a["data_kind"] for a in arrivals} == {"synthetic"}
    assert not api.app.state.store.arrivals(closed_only=True)
    estimate = api.get(
        "/api/v1/estimates", params=dict(zip(("stage_id", "route_id", "direction"), group))
    ).json()
    # No model is loaded in this fixture, so the server must say why it has no number.
    assert estimate["status"] == "unavailable"
    assert estimate["reason"] in {"model_not_trained", "insufficient_evidence"}
