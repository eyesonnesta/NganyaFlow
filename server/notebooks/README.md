# Analysis notebooks

Choose a version:

- [Original notebook](01_data_cleaning_and_model_evaluation.ipynb): the original text without the added beginner explanations.
- [Explained notebook](02_data_cleaning_and_model_evaluation_explained.ipynb): the same analysis with a plain-language introduction and notes before every code cell.
- [Real Nairobi network notebook](03_real_nairobi_network_analysis.ipynb): a separate study of the Digital Matatus source archive, with cleaning, table-link checks, CBD analysis, exploratory K-means grouping, and nearby-stop search.

The first two notebooks include the same code, executed tables and charts. Their analysis uses NumPy and pandas,
then retrains the backend's quantile regression forest on the earlier 11 dates and
evaluates the later 3 dates. Both versions also inspect the real Digital Matatus
Nairobi network and its CBD subset. Docker and the admin server are not required.

Start with the [plain-language guide to each code cell](cell-by-cell-guide.md) if
the terminology is new. The same explanations appear immediately before each code
cell in the explained notebook, covering its purpose, its connection to passenger waiting
times, and how to read its output.

From the repository root in PowerShell, use the existing project environment:

```powershell
uv pip install --python .venv/Scripts/python.exe -r server/requirements-notebooks.lock
uv pip install --python .venv/Scripts/python.exe --no-deps -e ./server
.\.venv\Scripts\python.exe server/scripts/prepare_real_dataset.py
.\.venv\Scripts\python.exe -m ipykernel install --sys-prefix --name nganyaflow --display-name "Python 3 (NganyaFlow)"
.\.venv\Scripts\python.exe -m notebook server/notebooks/01_data_cleaning_and_model_evaluation.ipynb
```

To open the explained version, replace the notebook filename in the last command
with `02_data_cleaning_and_model_evaluation_explained.ipynb`.
For the independent real-data study, use `03_real_nairobi_network_analysis.ipynb`.

The real-data notebook has plain-language notes before all 13 code cells. It reads
the original downloaded ZIP and saves its own outputs under
`results/digital-matatus-2019/`. Its K-means experiment groups nearby boarding
points and reports silhouette scores; it does not deploy a server feature or
claim waiting-time accuracy from network records.

For a new environment, run `uv venv --python 3.12 .venv` first. Without uv, create a
Python 3.12 virtual environment, then use its `python -m pip` with the same requirements
file and editable install. The optional dependency group is `server[notebooks]`;
the lock file records the versions used for this run, including backend dependencies.
The preparation command downloads the official real network data once. It can use
an existing ZIP with `--archive`; see the [real dataset guide](../data/external/digital-matatus-2019/README.md).
After preparation, the notebook runs offline.

Select the project's Python kernel, then **Kernel > Restart Kernel and Run All Cells**.
The setup cell displays the interpreter and package versions. Each run writes local
outputs under `server/notebooks/results/`, which is excluded from Git. It neither
connects to the running server nor changes the active model or original dataset.

The notebook covers:

1. Dataset provenance, normalization, missing values, timestamps, session boundaries,
   duplicate handling, supported stage/route/direction checks, and a cleaning audit.
2. A separate, deliberately corrupted copy with seven added rows to demonstrate
   rejection and deduplication. These rows never enter the training dataset.
3. Session-safe waiting-time labels, chronological splitting, and actual backend training.
4. MAE, RMSE, R², percentage within ±1/±2 minutes, quantile loss, interval coverage,
   baseline comparisons, per-stage/per-day metrics, and plots.
5. Comparison against the saved admin-panel evaluation and learning diagnostics.
6. Real Nairobi network records, CBD stop coordinates, source route links, and a
   comparison of the two datasets' roles.

This is **synthetic validation**, using real Nairobi CBD place names and simulated
arrival times/service. The clean source currently has no rejected arrivals; the
notebook reports that rather than inventing source-data problems. R² and a tolerance
success rate are different measures; neither should be presented as a generic
classification “accuracy”. Read the [AI report](../../docs/ai-report.md) for the theory
and field-validation limitations.

References: [Jupyter installation](https://jupyter.org/install),
[pandas datetime parsing](https://pandas.pydata.org/docs/reference/api/pandas.to_datetime.html),
[quantile forest API](https://zillow.github.io/quantile-forest/generated/quantile_forest.RandomForestQuantileRegressor.html).
