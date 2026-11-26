"""Local, read-only NganyaFlow presentation demo. Run with the project's Python."""

import json
from contextlib import asynccontextmanager
from datetime import datetime, timedelta
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Annotated, Literal

import joblib
import uvicorn
from fastapi import FastAPI, Query
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from nganyaflow.datasets import validate_bundle
from nganyaflow.ml import NAIROBI, features, train
from nganyaflow.schemas import Catalog
from nganyaflow.synthetic import CATALOG

HERE = Path(__file__).resolve().parent
BUNDLE = HERE.parents[1] / "server/data/examples/nairobi-cbd-synthetic-v1.json"


@asynccontextmanager
async def lifespan(app: FastAPI):
    bundle = json.loads(BUNDLE.read_text(encoding="utf-8"))
    metadata, sessions, _, events = validate_bundle(bundle, Catalog.model_validate(CATALOG))
    if metadata["data_kind"] != "synthetic":
        raise ValueError("The presentation requires synthetic training observations.")
    arrivals = [
        {**sessions[str(event.session_id)].model_dump(mode="json"), **event.model_dump(mode="json")}
        for event in events if event.event_type == "arrival"
    ]
    with TemporaryDirectory() as workspace:
        path = Path(workspace) / "forest.joblib"
        train(arrivals, path, test_days=3, diagnostics=False)
        app.state.forest = joblib.load(path)
    yield


app = FastAPI(title="NganyaFlow wait demo", lifespan=lifespan)
app.mount("/assets", StaticFiles(directory=HERE / "assets"), name="assets")


@app.get("/")
def home():
    return FileResponse(HERE / "index.html")


@app.get("/estimate")
def estimate(
    stage: Literal["kencom", "odeon", "railways"] = "kencom",
    day: Annotated[int, Query(ge=0, le=6)] = 0,
    hour: Literal["morning", "midday"] = "morning",
    gap: Annotated[int, Query(ge=2, le=18)] = 6,
    elapsed: Annotated[int, Query(ge=0, le=35)] = 3,
):
    query = datetime(2026, 10, 5 + day, 7 if hour == "morning" else 12, 29, tzinfo=NAIROBI)
    # Three hypothetical arrivals. Hold the oldest gap fixed so the last gap
    # can be changed independently of elapsed time.
    ages = [elapsed + gap + 4, elapsed + gap, elapsed]
    result = {
        "data_kind": "synthetic",
        "query_time": query.strftime("%H:%M"),
        "arrivals": [(query - timedelta(minutes=age)).strftime("%H:%M") for age in ages],
        "recent": sum(age <= 30 for age in ages),
        "status": "unavailable",
    }
    if elapsed > 30:
        return {**result, "reason": "The latest arrival is over 30 minutes old."}
    if result["recent"] < 3:
        return {**result, "reason": "This estimate needs three arrivals within the last 30 minutes."}

    forest = app.state.forest
    group = (stage, "cbd-study-simulated", "townbound")
    support = forest["groups"][group]
    row = features(group, query, elapsed, gap)
    if not (
        support["min_elapsed"] - 1 <= elapsed <= support["max_elapsed"]
        and support["min_headway"] <= gap <= support["max_headway"]
        and int(row["hour"]) in support["hours"]
        and row["weekday"] in support["weekdays"]
    ):
        return {**result, "reason": "These timings fall outside the training observations for this stage."}
    inputs = forest["vectorizer"].transform([row])
    low, median, high = forest["model"].predict(inputs, quantiles=[0.1, 0.5, 0.9])[0]
    return {**result, "status": "available", "low": float(low), "median": float(median),
            "high": float(high)}


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=9005)
