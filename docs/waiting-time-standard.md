# SemFlow / NganyaFlow: one waiting-time standard

Status: local alignment, 17 November 2026. This supersedes demand/ridership framing for the current AI deliverable, without deleting earlier experiments.

## Product contract
NganyaFlow is the mobile experience. SemFlow is its AI waiting-time workstream; SemProject_MLEngine is a repository name, not a second prediction objective.
Target: residual minutes until the next vehicle arrival for stage + route + direction at query time. It is not hourly passengers, crowding, seat availability, boarding delay or trip duration.
Model: the existing Quantile Regression Forest. Report median and 10th/90th percentiles (nominal 80% interval), freshness, evidence count and provenance. An 80% interval is not an 80% guarantee of local accuracy.

Use the existing Estimate schema unchanged. Render unavailable/stale/out-of-coverage as unavailable, never zero minutes. Display synthetic results as simulation. Crew activity counts and commuter reports remain descriptive, not demand or dispatch predictions. Android is not implemented by the research server.

## Data roles
- Synthetic Nairobi CBD arrival bundle: executable development and validation only; real place names do not make arrival timestamps real.
- Digital Matatus 2019: historical stop/route geography and network analysis only. Scheduled stop_times are not observed waits.
- MTA M15 hourly ridership: legacy demand exercise only; cannot be converted into next-vehicle labels. Do not join Nairobi weather to New York observations.
- Nairobi field arrivals: not yet verified/available for this pipeline. Collect repeated continuous sessions at supported stage/route/direction, with timezone-aware arrival times and known collection boundaries. Never bridge gaps or incomplete session tails.
- External actual-arrival benchmarks must retain their original city, provenance, coverage and license. They demonstrate a method, not Nairobi accuracy. Do not relabel sampled GPS traces as complete arrival coverage.

## Reproducible preparation
Run from the repository root:

```powershell
.\.venv\Scripts\python.exe -m nganyaflow.prepare_waiting --bundle server/data/examples/nairobi-cbd-synthetic-v1.json --catalog server/data/reference/catalog.json --output server/data/processed/waiting-time
```

The command validates the portable bundle with the backend's existing validator, builds labels with the same session-safe function as training, and exports arrivals, labelled query examples and an audit. It does not import into a running server, train, activate or publish a model. The audit names the exact source hash and data kind. The output directory must be new to preserve earlier results.

Train with the existing CLI on exported arrivals; evaluate on later whole dates, not random minute rows. Compare historical-median and last-headway baselines; report MAE in minutes, pinball loss and interval coverage/width. Never cite synthetic scores as field performance. Keep the held-out dates untouched during model selection.

## Presentation wording
"SemFlow estimates the wait until the next matatu at the selected stage, route and direction. NganyaFlow presents the estimate and its evidence. We have tested the pipeline on explicitly synthetic arrivals; real Nairobi network data supplies geography, not waiting-time ground truth. Local operational accuracy remains to be validated with observed arrivals."

Existing AI report and presentation outline remain the detailed evidence record. Earlier reported metrics are historical runs, not new validation from this alignment.
