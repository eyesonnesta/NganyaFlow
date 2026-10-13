# AI methodology and evaluation plan

## What the model will predict

At query time `t`, predict minutes until the next observed vehicle arrival for a stage, route and direction. This is **next-vehicle wait**, not travel time, guaranteed boarding time or seat availability. The selected candidate is a **Quantile Regression Forest (QRF)**: supervised tree-ensemble regression estimating the conditional distribution of wait [Meinshausen, 2006](https://www.jmlr.org/papers/v7/meinshausen06a.html), extending [Random Forests](https://doi.org/10.1023/A:1010933404324).

Proposed outputs: p10, p50 and p90. The p10-p90 range has nominal 80% coverage; actual coverage must be measured on held-out data. Do not display “80% confident” as a guarantee, derive bounds from variance between tree means, or call freshness a confidence probability. A standard `RandomForestRegressor` is a point-estimate baseline, not a QRF implementation. Select and pin a tested QRF implementation when implementing the pipeline; record its licence and version.

## Dataset and labels

Reference data comes from complete enumerator observation windows. Build queries at a prespecified cadence inside each window and label with the next observed arrival. Do not create dense near-identical examples and claim they are independent. Mark queries without a subsequent observed arrival as right-censored. Exclude them from ordinary QRF training and disclose the resulting selection bias; a survival model is a later alternative if censoring is substantial.

Use timestamps for arrival, departure, observation pause/resume, correction and server receipt. Use session-local vehicle IDs, not licence plates. Distinct passengers on one vehicle must not become distinct vehicle arrivals. Matching reports to reference events needs a prespecified time tolerance, route/direction agreement and treatment of ambiguous matches. Report both matched and unmatched proportions.

Forty to sixty enumerator-hours are a feasibility target, not 40-60 independent samples or proof of sufficient coverage. Confirm stage-days and events per stratum before fitting. Do not invent weather, passenger counts or route schedules when missing. Static Digital Matatus topology is a candidate registry source, not current frequency ground truth.

## Features available at inference time

| Feature group | Allowed examples | Exclusions |
| --- | --- | --- |
| Time | Hour encoded cyclically, day type | Knowledge of the next vehicle |
| Geography/service | Stage ID, route ID, direction, registry version | Unverified route/stage labels |
| Recent evidence | Counts in past windows, time since last eligible report, missingness flags | Later uploads presented as known at prediction time |
| Historical summaries | Training-only stage/route/time-band wait/headway summaries | Summaries computed from validation/test outcomes |
| Occupancy | Latest observed band and its age, if available | Inferred seat guarantees |

Replay data by information availability, using `received_at` for features and `event_at` for labels. Ground-truth events unavailable to the deployed system must not be secretly supplied to its feature pipeline. Compare rider-only and enumerator-assisted feature sets explicitly.

## Training and validation

1. Freeze registry and data dictionary; validate IDs, time zones, order, duplicates and coverage gaps.
2. Partition **whole days/sessions chronologically** into training, validation and final test blocks. Place a temporal gap at split boundaries if feature windows overlap. Keep all queries from a headway/session together.
3. Fit preprocessing on training only. Encode categories consistently; reserve unknown-category behavior. Do not use targets to encode categories across the entire dataset.
4. Tune tree count, depth, minimum leaf size and feature subsampling on validation blocks. Small data calls for restrained complexity. Report seed, package versions and search space.
5. Select evidence thresholds, expiry, interval adjustment and baseline fallback on validation data. Freeze them before opening the test block.
6. Evaluate once on held-out days, then additionally hold out a stage to assess transfer. With three stages, report each leave-one-stage-out result rather than a broad claim about Nairobi.

TimeSeriesSplit describes ordered folds, but it does not automatically enforce this project's day/session grouping or censoring rules. Implement explicit group-aware chronological partitions. Out-of-bag scoring is insufficient for temporal deployment evaluation.

## Baselines and decision gate

- Historical median wait for stage/route/direction/time-band, trained only on earlier data. Sparse strata back off to route/direction, then corridor; document the hierarchy.
- Last-known complete headway, with a defined residual-wait conversion and fallback when no valid headway exists. Do not silently assume every rider arrives uniformly between regular vehicles.
- Ordinary Random Forest median/mean point prediction as an optional ablation, evaluated with the same features and splits.

Report MAE in minutes for p50, pinball loss at each quantile, empirical interval coverage, mean/median interval width, rate of unavailable predictions and performance by stage/route/peak period. Use session- or day-level bootstrap intervals; row-wise resampling would overstate precision. Report censored and excluded records and compare performance at matched availability.

Before final testing, preregister a numeric tolerated undercoverage margin and operational error budget with the supervisor. Require held-out improvement over the strongest baseline and acceptable coverage/availability. No numerical performance target is asserted as achieved. If the model fails, ship the verified baseline or no estimate.

## Frequency inference and reporting bias

Headway is the interval between **vehicles**, not boardings or app openings. App reports alone generally cannot identify both true service rate and unknown reporting probability. Test reporting-rate degradation using thinning of reference-linked events, and nonrandom removal by stage/time/crowding. Preserve the fixed ground-truth stream. Evaluate headway MAE and distribution distance only where event matching supports the target. Do not report a universal minimum viable participation rate from three stages.

A future bias-corrected point-process/HMM model requires an explicit observation model, identifiability assumptions and calibration data. The current pilot does not claim this is already solved. Demand GNNs, contextual bandit dispatch and code-switched language interfaces from the source proposal are deferred research extensions. A threshold-based display/abstention policy is ordinary application logic, not an additional learned AI model.

## Deployment and monitoring

Train offline; serve a versioned artifact from a Python service. Store training period, dataset hash, feature schema, split manifest, metrics and limitations with each artifact. Monitor interval coverage where delayed ground truth exists, feature missingness, report volume and unavailable rate. Never retrain automatically on unreviewed user reports. Cold starts, unseen services and expired evidence fall back explicitly. A model update requires the same evaluation gates and a rollback path.

No trained model, dataset, benchmark or inference endpoint is included in this documentation change.
