"""Local pilot administration: explicit training and model activation."""

import hashlib
import json
import logging
import os
import secrets
import threading
import time
from pathlib import Path
from typing import Annotated
from urllib.parse import parse_qs

import joblib
from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Request
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, RedirectResponse
from fastapi.security import HTTPBasic, HTTPBasicCredentials

from .admin_ui import document, escape, render_dashboard, render_login
from .datasets import validate_bundle
from .ml import NAIROBI, timestamp, train
from .storage import Conflict

logger = logging.getLogger("uvicorn.error")


def initialize_admin(app, settings):
    runtime = settings.resolve(settings.runtime_dir)
    runtime.mkdir(parents=True, exist_ok=True)
    password_file = runtime / "admin-password.txt"
    try:
        descriptor = os.open(password_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        password = password_file.read_text("utf-8").strip()
        if not password:
            raise ValueError("Admin password file is empty")
    else:
        password = secrets.token_urlsafe(24)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write(password + "\n")
        # Explicitly requested bootstrap credential. Never log supplied request credentials.
        logger.warning("NganyaFlow admin username=admin password=%s", password)
    logger.info("Admin password file: %s", password_file)
    app.state.admin_password = password
    app.state.admin_csrf = secrets.token_urlsafe(32)
    app.state.login_csrf = secrets.token_urlsafe(32)
    app.state.admin_sessions = {}
    app.state.training_lock = threading.Lock()
    app.state.training_status = "idle"
    app.state.training_error = None
    app.state.candidate = runtime / "candidate.joblib"
    metrics_file = app.state.candidate.with_suffix(".metrics.json")
    app.state.training_test_days = (
        json.loads(metrics_file.read_text("utf-8")).get("configuration", {}).get("test_days", 1)
        if metrics_file.exists()
        else 1
    )
    app.state.active_model_path = runtime / "active.joblib"
    # An explicit environment model overrides an admin-selected model at startup.
    if not settings.model_path and app.state.active_model_path.exists():
        app.state.model = joblib.load(app.state.active_model_path)


def admin_router(app):
    router = APIRouter(prefix="/admin", include_in_schema=False)
    basic = HTTPBasic(auto_error=False)

    def authenticated(request, credentials):
        token = request.cookies.get("nganyaflow_admin", "")
        if app.state.admin_sessions.get(token, 0) > time.time():
            return True
        if credentials is None:
            return False
        username_ok = secrets.compare_digest(credentials.username.encode(), b"admin")
        password_ok = secrets.compare_digest(
            credentials.password.encode(), app.state.admin_password.encode()
        )
        return username_ok and password_ok

    def authenticate(
        request: Request, credentials: Annotated[HTTPBasicCredentials | None, Depends(basic)]
    ):
        if not authenticated(request, credentials):
            raise HTTPException(401, "Sign in at /admin/login")

    async def form_values(request):
        raw = await request.body()
        if len(raw) > 4096:
            raise HTTPException(413, "Form too large")
        try:
            return parse_qs(raw.decode("utf-8"))
        except UnicodeDecodeError as exc:
            raise HTTPException(400, "Form must use UTF-8") from exc

    async def csrf(request: Request):
        token = (await form_values(request)).get("csrf", [""])[0]
        if not secrets.compare_digest(token.encode(), app.state.admin_csrf.encode()):
            raise HTTPException(403, "Invalid form token; reload the admin page")

    def login_page(error="", status=200):
        return HTMLResponse(
            render_login(app.state.login_csrf, error),
            status_code=status,
            headers={"Cache-Control": "no-store"},
        )

    @router.get("/login", response_class=HTMLResponse)
    def login_form():
        return login_page()

    @router.post("/login")
    async def login(request: Request):
        values = await form_values(request)
        token = values.get("csrf", [""])[0]
        if not secrets.compare_digest(token.encode(), app.state.login_csrf.encode()):
            raise HTTPException(403, "Invalid form token; reload the login page")
        credentials = HTTPBasicCredentials(
            username=values.get("username", [""])[0], password=values.get("password", [""])[0]
        )
        if not authenticated(request, credentials):
            return login_page("Invalid username or password.", status=401)
        now = time.time()
        app.state.admin_sessions = {
            t: expiry for t, expiry in app.state.admin_sessions.items() if expiry > now
        }
        token = secrets.token_urlsafe(32)
        app.state.admin_sessions[token] = now + 3600
        response = RedirectResponse("/admin", status_code=303)
        response.set_cookie(
            "nganyaflow_admin",
            token,
            max_age=3600,
            httponly=True,
            secure=request.url.scheme == "https",
            samesite="strict",
            path="/admin",
        )
        response.headers["Cache-Control"] = "no-store"
        return response

    @router.post("/logout", dependencies=[Depends(authenticate), Depends(csrf)])
    def logout(request: Request):
        app.state.admin_sessions.pop(request.cookies.get("nganyaflow_admin", ""), None)
        response = RedirectResponse("/admin/login", status_code=303)
        response.delete_cookie("nganyaflow_admin", path="/admin")
        return response

    def training_job(test_days):
        temporary = app.state.candidate.with_suffix(".pending.joblib")
        try:
            rows = app.state.store.arrivals(closed_only=True)
            train(rows, temporary, test_days=test_days)
            temporary.replace(app.state.candidate)
            temporary.with_suffix(".metrics.json").replace(
                app.state.candidate.with_suffix(".metrics.json")
            )
            temporary.with_suffix(".predictions.csv").replace(
                app.state.candidate.with_suffix(".predictions.csv")
            )
            app.state.training_status = "candidate ready for review"
        except Exception as exc:
            logger.exception("Candidate training failed")
            app.state.training_status = "failed"
            app.state.training_error = str(exc)
        finally:
            app.state.training_lock.release()

    @router.get("", response_class=HTMLResponse)
    def dashboard(
        request: Request, credentials: Annotated[HTTPBasicCredentials | None, Depends(basic)]
    ):
        if not authenticated(request, credentials):
            if credentials is not None:
                raise HTTPException(401, "Invalid administrator credentials")
            return RedirectResponse("/admin/login", status_code=303)
        with app.state.store.connect() as db:
            counts = {
                "sessions": db.execute("SELECT COUNT(*) FROM sessions").fetchone()[0],
                "completed_sessions": db.execute(
                    "SELECT COUNT(*) FROM sessions WHERE ended_at IS NOT NULL"
                ).fetchone()[0],
                "observations": db.execute("SELECT COUNT(*) FROM observations").fetchone()[0],
            }
        report = app.state.model["report"] if app.state.model else None
        candidate_metrics = app.state.candidate.with_suffix(".metrics.json")
        candidate = (
            json.loads(candidate_metrics.read_text("utf-8"))
            if candidate_metrics.exists() and app.state.candidate.exists()
            else None
        )
        days = len(
            {
                timestamp(r["observed_at"]).astimezone(NAIROBI).date()
                for r in app.state.store.arrivals(closed_only=True)
            }
        )
        content = render_dashboard(
            counts=counts,
            stages=len(app.state.catalog.stages),
            routes=len(app.state.catalog.routes),
            report=report,
            candidate=candidate,
            csrf=app.state.admin_csrf,
            status=app.state.training_status,
            error=app.state.training_error,
            days=days,
            data_kinds=sorted(
                {
                    r.get("data_kind", "unspecified")
                    for r in app.state.store.arrivals(closed_only=True)
                }
            ),
            test_days=app.state.training_test_days,
        )
        return HTMLResponse(content, headers={"Cache-Control": "no-store"})

    @router.post("/train", dependencies=[Depends(authenticate), Depends(csrf)])
    async def train_candidate(request: Request, background: BackgroundTasks):
        values = await form_values(request)
        try:
            test_days = int(values.get("test_days", ["1"])[0])
        except ValueError as exc:
            raise HTTPException(422, "Held-out days must be an integer") from exc
        if not 1 <= test_days <= 7:
            raise HTTPException(422, "Choose between 1 and 7 held-out days")
        if not app.state.training_lock.acquire(blocking=False):
            raise HTTPException(409, "Training or activation already running")
        app.state.training_status = "training"
        app.state.training_test_days = test_days
        app.state.training_error = None
        background.add_task(training_job, test_days)
        return RedirectResponse("/admin", status_code=303)

    @router.get("/evaluation.json", dependencies=[Depends(authenticate)])
    def evaluation():
        path = app.state.candidate.with_suffix(".metrics.json")
        if not path.exists():
            raise HTTPException(404, "Train a candidate first")
        return JSONResponse(
            json.loads(path.read_text("utf-8")), headers={"Cache-Control": "no-store"}
        )

    @router.get("/predictions.csv", dependencies=[Depends(authenticate)])
    def predictions():
        path = app.state.candidate.with_suffix(".predictions.csv")
        if not path.exists():
            raise HTTPException(404, "Train a candidate first")
        return FileResponse(path, media_type="text/csv", filename="held-out-predictions.csv")

    @router.get("/datasets", response_class=HTMLResponse, dependencies=[Depends(authenticate)])
    def dataset_page():
        records = app.state.store.arrivals(closed_only=True)
        ids = sorted({r.get("dataset_id", "unregistered") for r in records})
        kinds = sorted({r.get("data_kind", "unspecified") for r in records})
        summary = (
            f"{len(records)} completed-session arrivals · {escape(', '.join(kinds) or 'No data')}"
        )
        return HTMLResponse(
            document(
                "Observation datasets · NganyaFlow",
                f'''
        <main class="content" style="max-width:1000px"><a class="button" href="/admin">← Overview</a>
        <div class="page-heading" style="margin-top:28px"><div><p class="eyebrow">RESEARCH DATA</p>
        <h1>Observation datasets</h1><p class="subtitle">Import, inspect and preserve data provenance.</p></div></div>
        <section class="panel"><div class="panel-body"><h2>Registered observations</h2><p>{summary}</p>
        <pre>{escape(chr(10).join(ids) or "No datasets imported")}</pre></div></section>
        <section class="panel" style="margin-top:22px"><div class="panel-body"><h2>Import an observation bundle</h2>
        <p>Use the versioned JSON bundle. All sessions and timestamps are validated before one atomic import.
        Reimporting identical IDs is safe. Synthetic and field observations cannot train one model together.</p>
        <form method="post" action="/admin/datasets/import"><input type="hidden" name="csrf" value="{app.state.admin_csrf}">
        <label for="dataset-file">Dataset JSON file</label><br><input id="dataset-file" type="file" accept=".json,application/json" required style="margin:14px 0">
        <textarea id="dataset-payload" name="payload" hidden></textarea><p id="file-status" role="status">Choose a dataset to import.</p>
        <button class="button primary" id="import-button" disabled>Validate and import dataset</button></form>
        <p class="row-description" style="margin-top:20px">Demo places are real; simulated arrival times are not field measurements.</p>
        </div></section></main><script>
        const fileInput=document.getElementById('dataset-file');
        fileInput.addEventListener('change', async () => {{
          const file=fileInput.files[0], button=document.getElementById('import-button');
          button.disabled=true;
          try {{
            if(!file || file.size>4000000) throw Error('Choose a JSON file smaller than 4 MB.');
            const text=await file.text(), data=JSON.parse(text);
            document.getElementById('dataset-payload').value=text;
            document.getElementById('file-status').textContent=data.metadata.dataset_id+' · '+data.metadata.data_kind+' · '+data.sessions.length+' sessions · '+data.observations.length+' observations';
            button.disabled=false;
          }} catch(error) {{ document.getElementById('file-status').textContent=error.message; }}
        }});</script>''',
            ),
            headers={"Cache-Control": "no-store"},
        )

    @router.post("/datasets/import", dependencies=[Depends(authenticate)])
    async def import_dataset(request: Request):
        raw = await request.body()
        if len(raw) > 6000000:
            raise HTTPException(413, "Dataset form is too large")
        try:
            values = parse_qs(raw.decode("utf-8"))
            token = values.get("csrf", [""])[0]
            if not secrets.compare_digest(token.encode(), app.state.admin_csrf.encode()):
                raise HTTPException(403, "Invalid form token")
            payload = values.get("payload", [""])[0]
            bundle = json.loads(payload)
            metadata, sessions, ends, events = validate_bundle(bundle, app.state.catalog)
            result = app.state.store.import_bundle(sessions, ends, events)
        except Conflict as exc:
            raise HTTPException(409, str(exc)) from exc
        except (ValueError, KeyError, TypeError, AttributeError) as exc:
            raise HTTPException(422, str(exc)) from exc
        registry = app.state.candidate.parent / "datasets"
        registry.mkdir(exist_ok=True)
        audit = {
            **metadata,
            **result,
            "bundle_sha256": hashlib.sha256(payload.encode()).hexdigest(),
        }
        (registry / (metadata["dataset_id"] + ".json")).write_text(
            json.dumps(audit, indent=2), "utf-8"
        )
        return HTMLResponse(
            document(
                "Dataset imported · NganyaFlow",
                f"""
        <main class="content" style="max-width:900px"><p class="eyebrow">DATA VALIDATION</p>
        <h1>Import complete</h1><p>{escape(metadata["dataset_id"])} · {escape(metadata["data_kind"])}</p>
        <section class="panel"><div class="panel-body"><pre>{escape(json.dumps(audit, indent=2))}</pre></div></section>
        <p><a class="button primary" href="/admin#training">Review data and train a candidate</a>
        <a class="button" href="/admin/datasets">Observation datasets</a></p></main>""",
            ),
            headers={"Cache-Control": "no-store"},
        )

    @router.post("/activate", dependencies=[Depends(authenticate), Depends(csrf)])
    def activate():
        if not app.state.training_lock.acquire(blocking=False):
            raise HTTPException(409, "Training or activation already running")
        try:
            if not app.state.candidate.exists():
                raise HTTPException(409, "Train and review a candidate first")
            bundle = joblib.load(app.state.candidate)
            # Atomic file replacement; in-flight predictions retain their current bundle.
            pending = Path(str(app.state.active_model_path) + ".pending")
            joblib.dump(bundle, pending)
            pending.replace(app.state.active_model_path)
            app.state.model = bundle
        finally:
            app.state.training_lock.release()
        return RedirectResponse("/admin", status_code=303)

    return router
