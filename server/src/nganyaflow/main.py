import secrets
import threading
import time
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from datetime import UTC, datetime, timedelta
from uuid import UUID

import joblib
from fastapi import Depends, FastAPI, Header, HTTPException, Request, Response

from . import access
from .config import Settings
from .schemas import (
    Catalog,
    CloseSession,
    DeletionRequest,
    Direction,
    Estimate,
    ObservationBatch,
    RedeemCode,
    ReportBatch,
    Session,
)
from .storage import Conflict, Store

MAX_FAILED_REDEEMS = 10
REDEEM_WINDOW_SECONDS = 600


def served_directions(route, stage_id):
    """Directions in which people can board a route at a stage.

    On the mapped network nobody boards a townbound vehicle at the town terminus
    or an outbound one at the far terminus. Simulated study routes are exempt.
    """
    if route.source != "digital-matatus-2019" or len(route.stage_ids) < 2:
        return list(route.directions)
    position = route.stage_ids.index(stage_id)
    return [
        d
        for d in route.directions
        if not (d == "townbound" and position == 0)
        and not (d == "outbound" and position == len(route.stage_ids) - 1)
    ]


def create_app(settings: Settings | None = None):
    settings = settings or Settings()

    @asynccontextmanager
    async def lifespan(app):
        catalog = Catalog.model_validate_json(settings.resolve(settings.catalog).read_text("utf-8"))
        stage_ids = [s.id for s in catalog.stages]
        route_ids = [r.id for r in catalog.routes]
        if len(stage_ids) != len(set(stage_ids)) or len(route_ids) != len(set(route_ids)):
            raise ValueError("Catalog IDs must be unique")
        if any(set(r.stage_ids) - set(stage_ids) for r in catalog.routes):
            raise ValueError("Route references an unknown stage")
        app.state.catalog = catalog
        app.state.store = Store(settings.resolve(settings.database))
        app.state.model = (
            joblib.load(settings.resolve(settings.model_path)) if settings.model_path else None
        )
        app.state.redeem_failures = defaultdict(deque)
        from .admin import initialize_admin

        initialize_admin(app, settings)
        stop = threading.Event()
        worker = None
        if settings.demo_mode:
            from .demo import run_live_simulator

            worker = threading.Thread(
                target=run_live_simulator,
                args=(app.state.store, catalog, stop),
                name="nganyaflow-demo-simulator",
                daemon=True,
            )
            worker.start()
        yield
        stop.set()
        if worker:
            worker.join(timeout=5)

    app = FastAPI(title="NganyaFlow pilot API", version="0.2.0", lifespan=lifespan)

    # ---- authentication -----------------------------------------------------------

    def principal(
        authorization: str | None = Header(default=None),
        x_write_token: str | None = Header(default=None),
    ):
        """Return 'enumerator', 'crew' or 'service' (the legacy shared write token)."""

        def unavailable():
            if not settings.write_token and not app.state.store.has_codes():
                raise HTTPException(503, "Writes disabled: configure NGANYAFLOW_WRITE_TOKEN")
            raise HTTPException(401, "Sign in with an access code")

        if authorization:
            scheme, _, value = authorization.partition(" ")
            role = (
                app.state.store.token_role(access.hash_token(value.strip()))
                if scheme.lower() == "bearer" and value.strip()
                else None
            )
            if not role:
                raise HTTPException(401, "Access token is invalid or expired")
            return role
        if x_write_token:
            if not settings.write_token:
                unavailable()
            if not secrets.compare_digest(x_write_token, settings.write_token):
                raise HTTPException(401, "Invalid write token")
            return "service"
        unavailable()

    def require(*roles):
        def check(role: str = Depends(principal)):
            if role != "service" and role not in roles:
                raise HTTPException(403, "This access code cannot use this feature")
            return role

        return check

    enumerator = require("enumerator")
    crew = require("crew")

    def validate_scope(stage_id, route_id, direction):
        routes = app.state.catalog.routes
        if not any(
            r.id == route_id and stage_id in r.stage_ids and direction in r.directions
            for r in routes
        ):
            raise HTTPException(404, "Unsupported stage, route or direction")

    # ---- public catalogue ------------------------------------------------------------

    @app.get("/health")
    def health():
        return {"status": "ok", "model_loaded": app.state.model is not None}

    @app.get("/api/v1/meta")
    def meta():
        model = app.state.model
        return {
            "api_version": "0.2.0",
            "server_time": datetime.now(UTC),
            "catalog_version": app.state.catalog.version,
            "catalog_attribution": app.state.catalog.attribution,
            "consent_version": settings.consent_version,
            "model_version": model["report"]["model_version"] if model else None,
            "model_data_kind": model["report"].get("data_kind") if model else None,
            "demo_mode": settings.demo_mode,
            "evidence_window_minutes": settings.max_evidence_age_minutes,
        }

    @app.get("/api/v1/stages")
    def stages():
        return app.state.catalog.stages

    @app.get("/api/v1/routes")
    def routes(stage_id: str | None = None):
        return [r for r in app.state.catalog.routes if stage_id is None or stage_id in r.stage_ids]

    # ---- access codes ------------------------------------------------------------------

    @app.post("/api/v1/auth/redeem")
    def redeem(body: RedeemCode, request: Request):
        host = request.client.host if request.client else "unknown"
        failures = app.state.redeem_failures[host]
        now = time.monotonic()
        while failures and now - failures[0] > REDEEM_WINDOW_SECONDS:
            failures.popleft()
        if len(failures) >= MAX_FAILED_REDEEMS:
            raise HTTPException(429, "Too many attempts. Wait a few minutes and try again.")
        token = access.new_token()
        result = app.state.store.redeem(
            access.hash_code(body.code),
            access.hash_token(token),
            timedelta(hours=settings.token_ttl_hours),
            body.device_name,
        )
        if result is None:
            failures.append(now)
            raise HTTPException(401, "That access code is not valid or has expired")
        role, expires = result
        return {"token": token, "token_type": "Bearer", "role": role, "expires_at": expires}

    @app.get("/api/v1/auth/me")
    def me(role: str = Depends(principal)):
        return {"role": role}

    # ---- enumerator observations ---------------------------------------------------------

    @app.post("/api/v1/sessions", dependencies=[Depends(enumerator)])
    def sessions(session: Session):
        validate_scope(session.stage_id, session.route_id, session.direction)
        try:
            app.state.store.add_session(session)
        except Conflict as exc:
            raise HTTPException(409, str(exc)) from exc
        return {"id": session.id, "status": "received"}

    @app.put("/api/v1/sessions/{session_id}/end", dependencies=[Depends(enumerator)])
    def end_session(session_id: UUID, body: CloseSession):
        try:
            app.state.store.close_session(str(session_id), body.ended_at)
        except KeyError as exc:
            raise HTTPException(404, "Unknown session") from exc
        except Conflict as exc:
            raise HTTPException(409, str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc
        return {"id": session_id, "status": "closed"}

    @app.post("/api/v1/observations/sync", dependencies=[Depends(enumerator)])
    def sync(batch: ObservationBatch):
        try:
            return app.state.store.add_batch(batch.records)
        except Conflict as exc:
            raise HTTPException(409, str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc

    # ---- waiting-time estimates ---------------------------------------------------------

    def compute_estimate(stage_id, route_id, direction):
        from .ml import features, key, timestamp

        now = datetime.now(UTC)
        group = (stage_id, route_id, direction)
        evidence = [
            r
            for r in app.state.store.arrivals_for(group)
            if key(r) == group and timestamp(r["observed_at"]) <= now
        ]
        # Keep recent evidence within one continuous observation session.
        evidence.sort(key=lambda r: timestamp(r["observed_at"]))
        latest = timestamp(evidence[-1]["observed_at"]) if evidence else None
        recent = (
            [
                r
                for r in evidence
                if r["session_id"] == evidence[-1]["session_id"]
                and now - timestamp(r["observed_at"])
                <= timedelta(minutes=settings.max_evidence_age_minutes)
            ]
            if evidence
            else []
        )
        metadata = {"evidence_count": len(recent), "last_observed_at": latest}
        for row in reversed(recent):
            if row.get("occupancy", "unknown") != "unknown":
                metadata["occupancy"] = row["occupancy"]
                metadata["occupancy_observed_at"] = timestamp(row["observed_at"])
                break

        def unavailable(reason):
            return Estimate(status="unavailable", reason=reason, **metadata)

        if latest is None:
            return unavailable("insufficient_evidence")
        if now - latest > timedelta(minutes=settings.max_evidence_age_minutes):
            return unavailable("stale_evidence")
        if len(recent) < settings.min_recent_arrivals:
            return unavailable("insufficient_evidence")
        bundle = app.state.model
        if bundle is None:
            return unavailable("model_not_trained")
        if bundle["report"].get("data_kind", "unspecified") != evidence[-1].get(
            "data_kind", "unspecified"
        ):
            return unavailable("evidence_provenance_mismatch")
        if now - timestamp(bundle["trained_at"]) > timedelta(days=settings.max_model_age_days):
            return unavailable("model_expired")
        support = bundle["groups"].get(group)
        if support is None:
            return unavailable("unsupported_training_group")
        elapsed = (now - latest).total_seconds() / 60
        headway = (latest - timestamp(recent[-2]["observed_at"])).total_seconds() / 60
        f = features(group, now, elapsed, headway)
        # Training queries sit on a one-minute grid, so the first grid cell starts at zero.
        if (
            elapsed < support["min_elapsed"] - 1.0
            or elapsed > support["max_elapsed"]
            or headway < support["min_headway"]
            or headway > support["max_headway"]
            or int(f["hour"]) not in support["hours"]
            or f["weekday"] not in support["weekdays"]
        ):
            return unavailable("outside_training_support")
        low, median, high = bundle["model"].predict(
            bundle["vectorizer"].transform([f]), quantiles=[0.1, 0.5, 0.9]
        )[0]
        return Estimate(
            status="available",
            median_minutes=float(median),
            lower_minutes=float(low),
            upper_minutes=float(high),
            expires_at=min(
                latest + timedelta(minutes=settings.max_evidence_age_minutes),
                timestamp(bundle["trained_at"]) + timedelta(days=settings.max_model_age_days),
                now + timedelta(minutes=1),
            ),
            model_version=bundle["report"]["model_version"],
            data_kind=bundle["report"].get("data_kind", "unspecified"),
            **metadata,
        )

    @app.get("/api/v1/estimates", response_model=Estimate)
    def estimates(stage_id: str, route_id: str, direction: Direction):
        validate_scope(stage_id, route_id, direction)
        return compute_estimate(stage_id, route_id, direction)

    @app.get("/api/v1/stages/{stage_id}/estimates")
    def stage_estimates(stage_id: str, direction: Direction | None = None):
        """Every route serving a stage, with its estimate, in one request."""
        if not any(s.id == stage_id for s in app.state.catalog.stages):
            raise HTTPException(404, "Unknown stage")
        rows = []
        for route in app.state.catalog.routes:
            if stage_id not in route.stage_ids:
                continue
            for d in served_directions(route, stage_id):
                if direction and d != direction:
                    continue
                rows.append(
                    {
                        "route_id": route.id,
                        "route_name": route.name,
                        "short_name": route.short_name,
                        "corridor": route.corridor,
                        "direction": d,
                        "estimate": compute_estimate(stage_id, route.id, d),
                    }
                )
        return {"stage_id": stage_id, "as_of": datetime.now(UTC), "routes": rows}

    # ---- rider boarding reports (anonymous) ---------------------------------------------

    @app.post("/api/v1/reports")
    def reports(batch: ReportBatch):
        for record in batch.records:
            validate_scope(record.stage_id, record.route_id, record.direction)
            if record.consent_version != settings.consent_version:
                raise HTTPException(
                    422,
                    f"Reports need the current consent version {settings.consent_version}",
                )
        try:
            return app.state.store.add_reports(batch.records)
        except Conflict as exc:
            raise HTTPException(409, str(exc)) from exc

    @app.delete("/api/v1/reports/{report_id}", status_code=204)
    def retract(report_id: UUID, install_id: UUID):
        if not app.state.store.retract_report(str(report_id), str(install_id)):
            raise HTTPException(404, "No such report for this installation")
        return Response(status_code=204)

    @app.post("/api/v1/deletion-requests")
    def deletion(body: DeletionRequest):
        try:
            row = app.state.store.delete_install(str(body.id), str(body.install_id))
        except Conflict as exc:
            raise HTTPException(409, str(exc)) from exc
        return _deletion_view(row)

    @app.get("/api/v1/deletion-requests/{request_id}")
    def deletion_status(request_id: UUID):
        row = app.state.store.deletion_status(str(request_id))
        if not row:
            raise HTTPException(404, "Unknown deletion request")
        return _deletion_view(row)

    # ---- crew demand view ---------------------------------------------------------------

    @app.get("/api/v1/demand", dependencies=[Depends(crew)])
    def demand(route_id: str, direction: Direction, window_minutes: int = 60):
        from .ml import timestamp

        if not 15 <= window_minutes <= 720:
            raise HTTPException(422, "window_minutes must be between 15 and 720")
        route = next((r for r in app.state.catalog.routes if r.id == route_id), None)
        if route is None or direction not in route.directions:
            raise HTTPException(404, "Unsupported route or direction")
        now = datetime.now(UTC)
        since = now - timedelta(minutes=window_minutes)
        names = {s.id: s.name for s in app.state.catalog.stages}
        boarding = defaultdict(int)
        for row in app.state.store.reports_for_route(route_id, direction, since):
            boarding[row["stage_id"]] += 1
        arrivals = defaultdict(list)
        for row in app.state.store.arrivals_for_route(route_id, direction):
            at = timestamp(row["observed_at"])
            if since <= at <= now:
                arrivals[row["stage_id"]].append((at, row.get("occupancy", "unknown")))
        rows = []
        for stage_id in route.stage_ids:
            if direction not in served_directions(route, stage_id):
                continue
            seen = sorted(arrivals[stage_id])
            known = [a for a in seen if a[1] != "unknown"]
            reports_here = boarding[stage_id]
            if reports_here == 0 and not seen:
                status = "no_data"
            elif reports_here < settings.min_reports_for_demand:
                status = "limited"
            else:
                status = "ok"
            rows.append(
                {
                    "stage_id": stage_id,
                    "stage_name": names.get(stage_id, stage_id),
                    "boarding_reports": reports_here,
                    "observed_arrivals": len(seen),
                    "latest_arrival_at": seen[-1][0] if seen else None,
                    "latest_occupancy": known[-1][1] if known else "unknown",
                    "occupancy_observed_at": known[-1][0] if known else None,
                    "status": status,
                }
            )
        return {
            "route_id": route_id,
            "route_name": route.name,
            "direction": direction,
            "window_minutes": window_minutes,
            "as_of": now,
            "min_reports_for_ok": settings.min_reports_for_demand,
            "stages": rows,
            "disclaimer": (
                "Counts include only people who chose to report in this app. They are not "
                "total passengers or total demand."
            ),
        }

    from .admin import admin_router

    app.include_router(admin_router(app))
    return app


def _deletion_view(row):
    return {
        "id": row["id"],
        "status": "completed" if row["completed_at"] else "pending",
        "requested_at": row["requested_at"],
        "completed_at": row["completed_at"],
        "reports_deleted": row["reports_deleted"],
        "note": "Aggregated figures already computed from reports cannot be separated afterwards.",
    }


app = create_app()
