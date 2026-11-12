# Nganya Flow: three free datasets

Downloaded on 11 September 2026. No payment or account login was needed for these downloads. This pack contains source data and guidance, not a completed assignment notebook.

## Which file should we start with?

For a demand-prediction prototype, start with `raw/mta_ridership/MTA_M15_Hourly_Ridership_2025_Q1.csv`. It has a numerical `ridership` target. It is a New York benchmark, so explain that limitation and confirm that an overseas prototype dataset fits your lecturer's expectations.

For the Nairobi-specific network, start with the Digital Matatus files. They contain stages, routes and published frequency values, but no observed current passenger demand or waiting times. None of these downloads alone validates Nganya Flow's core research question about recovering actual headways from sparse rider reports.

## 1. Digital Matatus: Nairobi network

- Source: https://www.digitalmatatus.com/map.html
- Direct download: https://www.digitalmatatus.com/data/GTFS_FEED_2019.zip
- Original archive: `raw/digital_matatus/GTFS_FEED_2019.zip`
- Extracted files: `raw/digital_matatus/GTFS_files/`
- Verified contents: 136 route records, 4,284 stop records, 272 trip records, 7,533 stop-time records and 792 frequency records. Stop records should not be assumed to equal distinct physical stages without checking duplicates and directions.
- `stops.txt`: stop IDs, names and coordinates.
- `routes.txt`, `trips.txt`, `stop_times.txt`: route/trip/stop relationships.
- `shapes.txt`: route geometry.
- `frequencies.txt`: published `headway_secs`, by trip and time interval. This is a nominal service-frequency value, not an observed current headway label.
- GTFS `.txt` files are comma-separated tables and can be read as CSVs.
- The official page labels this release 30 May 2019. The included feed metadata says `feed_start_date=20120302`, `feed_end_date=20201231`, and publisher name `TRAINING`. Retain those provenance details and verify current routes in the field.
- Attribute Digital Matatus. The archive has no standalone licence file; public download availability alone should not be described as a specific unrestricted licence.
- Format reference: https://gtfs.org/documentation/schedule/reference/

## 2. Nairobi hourly weather: 2025

- Source/documentation: https://open-meteo.com/en/docs/historical-weather-api
- File: `raw/nairobi_weather/Nairobi_Hourly_Weather_2025.csv`
- Verified size: 8,760 hourly records, 7 columns including time.
- Coverage: 1 January through 31 December 2025, Africa/Nairobi local time.
- Variables: temperature (Celsius), relative humidity (%), precipitation (mm), rain (mm), cloud cover (%) and wind speed (km/h).
- Requested Nairobi coordinates: latitude -1.286389, longitude 36.817223. The response uses the ERA5 grid point -1.25, 36.75; these are gridded reanalysis estimates, not a sensor at a matatu stage.
- The downloaded CSV starts with location metadata. Its table header is on line 4: use `skiprows=3` when loading it with pandas.
- Role in Nganya Flow: supporting inputs for a model whose outcome comes from local arrival/boarding observations. Weather does not provide the transport target.
- Attribute Open-Meteo and the underlying ERA5 data according to the source documentation. The free endpoint was used for this academic download.
- Do not join Nairobi weather to New York ridership as though they describe the same location. Obtain New York weather separately if weather is needed for the MTA experiment. Download weather for your actual fieldwork dates later; this 2025 extract demonstrates the data format.

## 3. MTA hourly bus ridership: route M15, first quarter 2025

- Publisher: Metropolitan Transportation Authority, via New York State Open Data.
- Source: https://data.ny.gov/Transportation/MTA-Bus-Hourly-Ridership-Beginning-2025/gxb3-akrn/about_data
- API documentation: https://dev.socrata.com/foundry/data.ny.gov/gxb3-akrn
- File: `raw/mta_ridership/MTA_M15_Hourly_Ridership_2025_Q1.csv`
- Verified size: 51,840 records and 6 columns. This matches the source API count for the stated filter; it is a complete filtered extract, not the full citywide dataset.
- Filter: route M15; timestamps from 1 January 2025 inclusive to 1 April 2025 exclusive.
- Columns: `transit_timestamp`, `bus_route`, `payment_method`, `fare_class_category`, `ridership`, `transfers`.
- One record describes an hour, route, payment method and fare category. Many records share the same hour; 51,840 records does not mean 51,840 separate hours.
- To predict route-level hourly demand, sum `ridership` across payment methods and fare categories for each route/hour. Derive time-of-day and day-of-week features. Sort chronologically before a forecasting train/test split.
- `transfers` is already included in `ridership`; do not add it again. Same-hour transfers are also unsuitable as a future-demand input if unavailable at prediction time.
- The publisher describes these as ridership estimates based on OMNY/MetroCard entry transactions. Do not equate them with a census of every passenger.
- Limitations: New York's formal bus system differs from Nairobi matatus. No vehicle arrival timestamps, individual waiting times, crowding labels or hold/depart outcomes are supplied here.
- Original publisher metadata, including field definitions, is retained in `source_metadata.json`. Attribute MTA and consult the source portal's terms for reuse; the metadata does not identify a separate named dataset licence.

## What still needs collecting locally?

For actual headway inference: route, direction, stage, observation session and vehicle arrival timestamp. Calculate intervals between consecutive observed arrivals of the same route/direction at the same stage, within continuous observation sessions.

For rider wait: waiting-start timestamp and boarding timestamp. For demand: boarding counts or clearly defined rider reports. Do not substitute nominal GTFS headways or overseas ridership for these local ground-truth measurements.

## Reproducibility

`download_manifest.json` records the successful download URLs, file sizes and SHA-256 checksums. Original downloaded files are preserved. The MTA file is not sorted or aggregated, and the weather metadata is retained.

Zindi's advertised USD 12,000 is a competition prize, not a dataset purchase price. Zindi files are not included in this pack; all three included sources were downloaded without login or payment.
