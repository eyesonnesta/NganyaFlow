import secrets
from contextlib import asynccontextmanager
from datetime import UTC, datetime, timedelta
from uuid import UUID

import joblib
from fastapi import Depends, FastAPI, Header, HTTPException

from .config import Settings
from .schemas import Catalog, CloseSession, Direction, Estimate, ObservationBatch, Session
from .storage import Conflict, Store


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
        from .admin import initialize_admin

        initialize_admin(app, settings)
        yield

    app = FastAPI(title="NganyaFlow pilot API", version="0.1.0", lifespan=lifespan)

    def writer(x_write_token: str | None = Header(default=None)):
        if not settings.write_token:
            raise HTTPException(503, "Writes disabled: configure NGANYAFLOW_WRITE_TOKEN")
        if not x_write_token or not secrets.compare_digest(x_write_token, settings.write_token):
            raise HTTPException(401, "Invalid write token")

    def validate_scope(stage_id, route_id, direction):
        routes = app.state.catalog.routes
        if not any(
            r.id == route_id and stage_id in r.stage_ids and direction in r.directions
            for r in routes
        ):
            raise HTTPException(404, "Unsupported stage, route or direction")

    @app.get("/health")
    def health():
        return {"status": "ok", "model_loaded": app.state.model is not None}

    @app.get("/api/v1/stages")
    def stages():
        return app.state.catalog.stages

    @app.get("/api/v1/routes")
    def routes(stage_id: str | None = None):
        return [r for r in app.state.catalog.routes if stage_id is None or stage_id in r.stage_ids]

    @app.post("/api/v1/sessions", dependencies=[Depends(writer)])
    def sessions(session: Session):
        validate_scope(session.stage_id, session.route_id, session.direction)
        try:
            app.state.store.add_session(session)
        except Conflict as exc:
            raise HTTPException(409, str(exc)) from exc
        return {"id": session.id, "status": "received"}

    @app.put("/api/v1/sessions/{session_id}/end", dependencies=[Depends(writer)])
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

    @app.post("/api/v1/observations/sync", dependencies=[Depends(writer)])
    def sync(batch: ObservationBatch):
        try:
            return app.state.store.add_batch(batch.records)
        except Conflict as exc:
            raise HTTPException(409, str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc

    @app.get("/api/v1/estimates", response_model=Estimate)
    def estimates(stage_id: str, route_id: str, direction: Direction):
        from .ml import features, key, timestamp

        validate_scope(stage_id, route_id, direction)
        now = datetime.now(UTC)
        group = (stage_id, route_id, direction)
        evidence = [
            r
            for r in app.state.store.arrivals()
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
        if (
            elapsed < support["min_elapsed"]
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

    from .admin import admin_router

    app.include_router(admin_router(app))
    return app


app = create_app()
