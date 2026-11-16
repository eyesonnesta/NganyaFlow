from datetime import UTC, datetime, timedelta
from typing import Literal
from uuid import UUID

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, field_validator

Direction = Literal["townbound", "outbound"]


class Record(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Stage(Record):
    id: str = Field(min_length=1, max_length=80)
    name: str = Field(min_length=1, max_length=160)
    latitude: float | None = Field(default=None, ge=-90, le=90)
    longitude: float | None = Field(default=None, ge=-180, le=180)


class Route(Record):
    id: str = Field(min_length=1, max_length=80)
    name: str = Field(min_length=1, max_length=160)
    short_name: str | None = Field(default=None, max_length=20)
    corridor: str | None = Field(default=None, max_length=80)
    # Ordered from the town terminus outward.
    stage_ids: list[str]
    directions: list[Direction] = Field(min_length=1)
    source: str | None = Field(default=None, max_length=60)


class Catalog(Record):
    version: str | None = None
    attribution: str | None = None
    stages: list[Stage]
    routes: list[Route]


class Session(Record):
    id: UUID
    stage_id: str = Field(min_length=1, max_length=80)
    route_id: str = Field(min_length=1, max_length=80)
    direction: Direction
    started_at: AwareDatetime
    data_kind: Literal["field", "synthetic", "unspecified"] = "unspecified"
    dataset_id: str = Field(default="unregistered", min_length=1, max_length=100)

    @field_validator("started_at")
    @classmethod
    def past_time(cls, value: datetime) -> datetime:
        if value > datetime.now(UTC):
            raise ValueError("Future timestamps are not accepted")
        return value.astimezone(UTC)


class CloseSession(Record):
    ended_at: AwareDatetime

    _past = field_validator("ended_at")(classmethod(Session.past_time.__func__))


class Observation(Record):
    id: UUID
    session_id: UUID
    observed_at: AwareDatetime
    event_type: Literal["arrival", "departure"]
    occupancy: Literal["empty", "seats_available", "full", "unknown"] = "unknown"

    _past = field_validator("observed_at")(classmethod(Session.past_time.__func__))


class ObservationBatch(Record):
    records: list[Observation] = Field(min_length=1, max_length=500)


class Estimate(Record):
    status: Literal["available", "unavailable"]
    reason: str | None = None
    median_minutes: float | None = None
    lower_minutes: float | None = None
    upper_minutes: float | None = None
    nominal_coverage: float = 0.8
    evidence_count: int = 0
    last_observed_at: datetime | None = None
    expires_at: datetime | None = None
    model_version: str | None = None
    data_kind: str | None = None
    occupancy: Literal["empty", "seats_available", "full", "unknown"] = "unknown"
    occupancy_observed_at: datetime | None = None


class BoardingReport(Record):
    id: UUID
    install_id: UUID
    stage_id: str = Field(min_length=1, max_length=80)
    route_id: str = Field(min_length=1, max_length=80)
    direction: Direction
    boarded_at: AwareDatetime
    consent_version: str = Field(min_length=1, max_length=40)

    @field_validator("boarded_at")
    @classmethod
    def plausible_time(cls, value: datetime) -> datetime:
        now = datetime.now(UTC)
        if value > now + timedelta(minutes=2):
            raise ValueError("Future timestamps are not accepted")
        if value < now - timedelta(days=7):
            raise ValueError("Reports older than seven days are not accepted")
        return value.astimezone(UTC)


class ReportBatch(Record):
    records: list[BoardingReport] = Field(min_length=1, max_length=100)


class DeletionRequest(Record):
    id: UUID
    install_id: UUID


class RedeemCode(Record):
    code: str = Field(min_length=4, max_length=40)
    device_name: str | None = Field(default=None, max_length=60)
