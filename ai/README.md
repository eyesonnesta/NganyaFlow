# AI data preparation

This workspace contains public datasets and notebooks for project preparation. Start with `notebooks/01_mta_ridership_exploration.ipynb`, then run `notebooks/02_data_preparation.ipynb` to rebuild and inspect the prepared tables. No trained model or local waiting-time measurements are included.

## Setup

Run these commands from this workspace (the repository root for SemProject_MLEngine, or `ai/` for NganyaFlow):

```bash
python -m venv .venv
python -m pip install -r requirements.txt
python scripts/prepare_data.py
python -m jupyterlab
```

Activate `.venv` before installing: `.venv\Scripts\Activate.ps1` in Windows PowerShell, or `source .venv/bin/activate` on macOS/Linux. Python 3.10 or later is required. Preparation was checked with pandas 2.2.3. Notebooks work when the kernel starts in this workspace or its `notebooks/` directory. For Colab, clone the repository and change into this workspace first.

## Files

| Path | Contents |
| --- | --- |
| `data/raw/mta_ridership/` | Original M15 extract and publisher metadata |
| `data/raw/nairobi_weather/` | Original weather CSV, including its location metadata |
| `data/raw/digital_matatus/` | Original 2019 GTFS archive and extracted tables |
| `data/processed/` | Reproducible cleaned tables; no manual edits |
| `data/download_manifest.json` | Download URLs and checksums of the original downloads |
| `data/SOURCE_NOTES.md` | Source details, attribution and dataset limitations |
| `notebooks/01_mta_ridership_exploration.ipynb` | Original coursework exploration, with portable paths and cleared outputs |
| `notebooks/02_data_preparation.ipynb` | Cleaning run, quality report and hourly demand inspection |
| `scripts/prepare_data.py` | Preparation and validation rules |
| `reports/data_quality.json` | Counts, duplicate checks and known limitations |

## Cleaning rules

The script verifies source checksums before reading data. It trims categorical whitespace, removes exact duplicate rows, parses timestamps and numeric measures, sorts records and checks required values. It stops on invalid values or conflicting category/hour keys. It does not fill missing values or remove outliers automatically. The supplied MTA and weather extracts have no missing values or exact duplicates.

MTA category records are retained in `mta_ridership/m15_category_hourly.csv`. `mta_ridership/m15_route_hourly.csv` sums ridership and transfers across payment methods and fare categories for each route/hour. Transfers are already part of ridership and must never be added to the target. The aggregation preserves total ridership: 1,148,966 across 2,160 hours. Same-hour transfers must not be used to forecast future demand.

The hourly table includes hour, day of week (Monday = 0), weekend status and a proposed chronological split: January and February for training (1,416 hours), March 1-15 for validation (360), and March 16-31 for testing (384). Fit preprocessing only on training data. Use past observations only when adding lag or rolling features. Keep the test period untouched while choosing models. These splits have not been used to claim accuracy.

Weather preparation skips the three-line location preamble, gives columns unit-specific names, and checks the full 2025 hourly calendar. Times remain Africa/Nairobi local clock values. MTA times retain the source New York local clock values; no timezone conversion is applied.

GTFS tables retain string identifiers, leading zeros, optional empty values and published times, including any times beyond 24:00. They are trimmed, deduplicated and sorted as strings across columns. This is table cleanup, not a full GTFS validator. Order stop sequences numerically when building routes. Blank GTFS fields are counted in the report and are not automatically errors. The 2019 feed is historical and needs field verification.

## Sources and limits

The original downloads were collected on September 11, 2026. Digital Matatus supplies Nairobi network context, Open-Meteo/ERA5 supplies Nairobi weather, and MTA supplies a New York demand benchmark. Nairobi weather must not be joined to New York ridership. These sources do not supply observed matatu arrivals, passenger waits or local demand labels.

Read [source notes](data/SOURCE_NOTES.md) for URLs, filters, attribution and reuse terms. Source data retains its publisher's terms; a repository licence does not relicense it. The Digital Matatus archive has no standalone licence file, and the MTA metadata does not identify a separate named dataset licence. No raw field observations, individual location traces or credentials belong here.
