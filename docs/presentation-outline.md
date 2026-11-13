# NganyaFlow presentation outline

**Audience:** Artificial Intelligence and Mobile Application Development assessment  
**Suggested duration:** 12–15 minutes plus questions  
**Source:** [AI foundation and validation report](ai-report.md)  
**Study status:** Synthetic validation only; no field-accuracy claim

## Slide 1 — Project purpose and scope

**Message:** NganyaFlow estimates the wait to the next vehicle at a selected stage, route, and direction.

Explain that arrival wait does not guarantee a seat or total journey time. Identify the two units: AI owns the learned estimator and evaluation; Mobile Application Development owns the commuter and enumerator experience and offline integration.

**Visual:** Admin overview, [active model screenshot](evidence/05-active-model.jpg). Label it “server admin panel”, not “Android application”.

**Speaker prompt:** “The working slice is a Python backend and research admin panel. The Android client is still to be implemented.”

## Slide 2 — What makes this machine learning

**Message:** A fitted model learns relationships from labelled examples.

Distinguish training, inference, and retraining. The labels are future arrival waits reconstructed from completed observations. Recent arrivals update inference inputs; model parameters change only after explicit training and activation.

**Visual:** The lifecycle diagram in report Section 6.

**Speaker prompt:** “I did not hard-code a wait for each place. The tree structure and leaf distributions are learned from training examples.”

## Slide 3 — AI branches actually involved

**Message:** Supervised regression, tree ensembles, and conditional uncertainty estimation are implemented.

Use a small excerpt from the branch map. Explain that validation rules are deterministic safeguards. NLP, computer vision, reinforcement learning, HMM inference, and dispatch optimisation are possible extensions, not current implemented modules.

**Speaker prompt:** “A human crew role does not mean we have a reinforcement-learning agent, and a percentile interval is not fuzzy logic.”

## Slide 4 — From arrivals to a training example

**Message:** Predict residual wait using information available at the query time.

Use the worked example: arrivals 07:00, 07:06, 07:14; query 07:09; previous headway 6, elapsed 3, target 5 minutes. Explain why the 07:14 arrival is a label, never an input feature.

Show the seven conceptual features. Note that categorical encoding produces nine numerical columns for this dataset. Mention session and midnight boundaries.

## Slide 5 — Why a Quantile Regression Forest

**Message:** One forest estimates the median and a conditional waiting range.

Describe regression-tree splitting, bootstrap ensembles, retained leaf targets, and extraction of the 10th, 50th and 90th percentiles. Record the actual settings: 100 trees, minimum leaf size 5, seed 42, and `max_features=1.0`.

**Speaker prompt:** “The splits use squared error. Pinball loss evaluates the quantiles; it is not the tree-splitting objective in this implementation.”

## Slide 6 — Data provenance

**Message:** Real CBD place names, simulated timestamps and service.

Identify Kencom, Odeon and the Railways area at Nairobi Central Station. State 1,049 arrivals, 84 completed sessions, 14 dates and a fixed generator seed. The simulated route does not establish an actual service linking these places.

**Visual:** [Dataset selection](evidence/01-dataset-selected.jpg) and a small excerpt of [the import audit](evidence/02-import-audit.jpg).

**Speaker prompt:** “The location sources verify place names. They do not verify the generated arrival times.”

## Slide 7 — Chronological evaluation

**Message:** Earlier dates train the model; later complete dates test it.

Show the split: 5–15 October trains; 16–18 October tests. Show 4,684 training queries and 1,259 held-out queries, with 66 and 18 sessions respectively. Explain that adjacent minute queries are correlated and that random row splitting would risk misleading evaluation.

**Visual:** [Three-day holdout configuration](evidence/03-training-configuration.jpg).

## Slide 8 — Baselines and measured accuracy

**Message:** The fitted forest outperformed both specified baselines in this simulation.

| Method | Held-out MAE |
|---|---:|
| Quantile forest | 0.939 min |
| Historical median | 2.211 min |
| Last headway | 1.018 min |
| Shuffled-label control | 2.409 min |

**Visual:** [Comparison figure](evidence/07-model-comparison.png).

**Speaker prompt:** “The stronger comparison is last headway. The improvement over that baseline is approximately 7.78%, not the larger improvement over the weaker historical median.”

## Slide 9 — Evidence of learning

**Message:** Performance changes with meaningful labels and broader training coverage.

Explain the shuffled-label control, then show learning-curve MAE of 1.136, 1.016 and 0.939 minutes for 3, 5 and 11 earlier training dates. The same test dates are used for all points, so these are descriptive diagnostics, not independent replications.

**Visual:** [Learning curve](evidence/08-learning-curve.png), optionally [the same results in the admin panel](evidence/06-learning-curve.jpg).

**Speaker prompt:** “The generator deliberately contains learnable relationships. This demonstrates learning in that setting, not automatic transfer to Nairobi operations.”

## Slide 10 — Uncertainty and feature reliance

**Message:** An accurate median is not enough; intervals and subgroup behaviour matter.

State nominal coverage 80%, observed pooled coverage 80.06%, and mean width 3.10 minutes. Show stage coverage: Kencom 76.99%, Odeon 78.07%, Railways 85.61%. Explain why a pooled result near 80% does not prove conditional calibration.

**Visual:** [Prediction intervals](evidence/10-prediction-intervals.png) or [permutation importance](evidence/09-feature-importance.png).

**Speaker prompt:** “Feature importance measures reliance of this fitted model. It does not show that a feature causes a change in real service.”

## Slide 11 — Mobile application integration

**Message:** The mobile experience must preserve the estimator's conditions and uncertainty.

Show the intended flow: stage selection → estimate or unavailable reason → locally queued contribution → retry-safe synchronisation. Cover UUIDs, original timestamps, evidence expiry, manual stage selection, privacy and accessible error states.

State the implementation boundary: APIs and server workflows exist; Android screens, local persistence and device/UI tests remain future work. Do not substitute admin screenshots for mobile completion evidence.

## Slide 12 — Validation status and next study

**Message:** The pipeline is validated; field effectiveness is still an open question.

Report 20 passing automated tests and independent recomputation of held-out metrics. Explain that old observations correctly return `stale_evidence`. The next study needs verified boarding sides, continuous field observation, independent later dates, and interval calibration checks.

End with the research question: “When observations are sparse or biased, at what point do estimates cease to improve commuter decisions?”

## Optional demonstration sequence

1. Open the local admin panel and show the synthetic-data notice.
2. Open **Observation datasets**, select the included bundle and explain the provenance summary.
3. If reimporting, explain that duplicate IDs do not add extra arrivals.
4. Return to **Model training**, retain **3 held-out days**, and start a candidate run.
5. Refresh after completion and compare the baseline, control, coverage and learning-curve results.
6. Explain that activation is an explicit review action. The existing active model remains unchanged while a new candidate trains.
7. Show the API's stale-evidence response rather than presenting old simulated data as a live estimate.

Keep the screenshots and evaluation JSON available as a fallback if the network, Docker startup, or browser session fails during assessment. Do not display passwords or writer tokens.

## Likely questions and concise answers

**Why not a neural network?** The initial problem is tabular regression with limited data. A quantile forest is a defensible baseline family that supports conditional percentiles without adding deep-learning complexity.

**What exactly is learned?** Tree split structures and retained distributions of target waits in the resulting leaves. The input schema and freshness rules are engineering choices.

**Why not use average headway as the wait?** Waiting from an arbitrary query time is a residual duration. It depends on elapsed time and the next interval; it is not simply the previous full headway.

**Is the 80% interval an 80% guarantee for a passenger?** No. It is a nominal central quantile interval whose empirical coverage must be assessed. Stage coverage differs, and seat availability is not predicted.

**Is the model really learning?** The fitted forest was trained from the imported records. Its shuffled-label control performed worse, and its held-out error improved across the recorded learning horizons. Those are simulation diagnostics, not proof of field effectiveness.

**Why is the live API not returning the trained estimate?** The historical observations are stale. Model availability and current evidence availability are separate requirements.

**Does a hash prove that data is genuine?** No. It identifies exact bytes or rows and helps detect differences. It does not prove an enumerator observed a vehicle.

**Where is reinforcement learning?** Not implemented. A future hold/depart policy would require an action space, rewards, interaction data or simulation, and separate evaluation.

**What is complete for the mobile unit?** The backend integration contract and server-side collection/sync support. The Android application, local queue and device tests still need implementation.
