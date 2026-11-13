# Digital Matatus: real Nairobi network data

The [official Digital Matatus download page](https://www.digitalmatatus.com/map.html)
provides a GTFS feed dated 30 May 2019. This is our real network dataset, kept
separate from the generated arrival dataset in `server/data/examples/`.

The [dedicated real-data notebook](../../../notebooks/03_real_nairobi_network_analysis.ipynb)
reads the original archive, demonstrates cleaning, checks table relationships,
and explores the CBD network and geographic groups with NumPy and pandas.

GTFS means General Transit Feed Specification, a format for stops, routes, trips
and service information. The [collection methodology](https://www.digitalmatatus.com/about.html)
describes University of Nairobi researchers riding matatus and recording routes
and stops with mobile devices, followed by conversion to GTFS.

This release is historical. Its calendar runs from 2019-01-01 to 2020-12-31;
its `feed_info.txt` identifies the publisher as `TRAINING`. The official publication
and the ZIP's own metadata are both recorded in [source.json](source.json).
The feed helps us study the mapped network and identify stage/route relationships.
It does not establish which services operate today.

The time fields and frequencies are static feed information. They are not a dated
sequence of independently observed arrivals at a stage. Expanding a frequency row
into invented daily arrivals would create another generated dataset. We therefore
do not feed this archive into the arrival model or report its timetable as field
accuracy evidence.

## Files and preparation

From the repository root, after installing the notebook dependencies:

```powershell
.\.venv\Scripts\python.exe server/scripts/prepare_real_dataset.py
```

Alternatively, use the file downloaded through Chrome:

```powershell
.\.venv\Scripts\python.exe server/scripts/prepare_real_dataset.py --archive C:/Users/manot/Downloads/GTFS_FEED_2019.zip
```

The script saves the original archive and ten source tables under `source/`, then
writes cleaned network tables under `processed/`. `source.json` records checksums,
counts, source links and the cleaning result. No network request is needed when
using `--archive`.

- `stops.csv`: stop identifiers, names and coordinates after quality checks.
- `routes.csv`: source route records.
- `cbd_stops.csv`: stops in the study rectangle, latitude −1.295 to −1.275 and
  longitude 36.815 to 36.835. This is a study filter, not an official CBD boundary.
- `cbd_routes.csv`: routes linked to at least one stop in that rectangle.
- `cbd_route_stops.csv`: distinct route/direction/stop links from the feed.
- `rejected_stops.csv`: rejected rows and their reasons, if any.

Coordinates are converted to numbers and checked for valid ranges. Missing stop
IDs/names, conflicting IDs and exact duplicates are recorded in the audit.
Trip, route and stop references are checked before deriving the CBD subset.
Identifiers remain text so leading zeros are preserved.

The site says the data is free and public; an explicit redistribution license was
not found inside the archive. Original and processed files are kept locally and
excluded from Git. Keep the source attribution with any derived work and check
reuse terms before distributing the archive.
