# NganyaFlow

## Backend development

For the Artificial Intelligence study, read the [AI foundation and validation report](docs/ai-report.md)
and [presentation outline](docs/presentation-outline.md). The included Nairobi CBD dataset uses
real place names with explicitly simulated arrival times and a simulated study service.

For executable NumPy/pandas analysis, open the [data cleaning and model evaluation notebook](server/notebooks/01_data_cleaning_and_model_evaluation.ipynb).
Its [setup guide](server/notebooks/README.md) explains how to launch Jupyter and rerun the analysis.
The [dataset inventory](server/data/README.md) separates the real Digital Matatus
Nairobi network from the generated arrival sequences used to evaluate waiting times.
For a separate study of the downloaded network, open the
[real Nairobi data notebook](server/notebooks/03_real_nairobi_network_analysis.ipynb).

The Python pilot backend, observation collection, model training and admin panel
now live under `server/`. Start with [development and Docker instructions](docs/development.md).
The root `Dockerfile` runs the server with supervisord; build it with
`bash build-docker.sh`. The Android client is reserved under `apps/android/`.

```text
apps/android/                 Future mobile application
server/src/nganyaflow/        API, storage, admin, training and inference
server/tests/                Backend and model-pipeline checks
server/notebooks/            Executed data cleaning and model evaluation study
server/data/reference/       Verified stage/route catalog
server/data/raw/             Local observations (not committed)
server/data/processed/       Training exports (not committed)
server/artifacts/            Trained model files (not committed)
server/runtime/              Admin credentials and selected models (not committed)
deploy/                      Supervisord configuration
docs/                        Setup, API workflow and research decisions
```

The sections below describe the wider product vision. The implemented slice is
enumerator collection, waiting-time training/inference and local administration.

NganyaFlow will be an Android application that will help commuters access information about matatu routes, stages, and expected waiting times. The project will aim to reduce uncertainty at boarding stages and support better travel decisions through collected transport observations and commuter reports.

## What the Project Will Entail

The project will involve designing, developing, and testing a mobile application with workflows for commuters, transport enumerators, and matatu crew.

### Commuter Features

Commuters will be able to:

- Browse routes and boarding stages.
- Find a nearby stage using optional location access or select a stage manually.
- View estimated waiting times, data confidence, and available crowding information.
- Confirm boarding and review their activity.
- Manage privacy, language, and notification preferences.

Waiting-time estimates will depend on the availability and quality of collected observations. The application will indicate when information is limited or unavailable.

### Enumerator Features

Enumerators will be able to set up observation sessions for selected stages, routes, and travel directions. They will record vehicle arrivals, departures, and occupancy observations, then review a summary of their completed session.

These observations will support the development and evaluation of transport information shown to commuters.

### Crew Features

Matatu crew will be able to view available demand information for supported stages and routes. This feature will aim to help crew understand commuter demand where sufficient data will be available.

### Offline Support

The application will support selected activities during connectivity interruptions. Supported records will be saved locally and queued for synchronization when a connection becomes available.

Users will be able to view synchronization status and identify records that will require another synchronization attempt.

### Privacy and Accessibility

The project will include consent controls, optional location access, and data deletion options. Data retention rules will be agreed before deployment.

The interface will aim to provide readable text, accessible controls, clear feedback, and support for English and reviewed Kiswahili translations.

## Development Approach

Development will progress from interface designs to a working Android application, data storage and synchronization, and testing with representative transport observations.

The team will evaluate the main user workflows, offline behavior, data quality, accessibility, and the reliability of waiting-time estimates before deployment.

## Team Collaboration

This repository will serve as the shared workspace for project code and documentation. Team members will be able to fork the repository, develop their contributions, and submit pull requests for review.

Implementation details and setup instructions will be added as development progresses.

## Intended Outcome

NganyaFlow will aim to make matatu travel more predictable by giving commuters clearer stage and route information while supporting structured observation collection and visibility into commuter demand.
