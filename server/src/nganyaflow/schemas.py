from datetime import UTC, datetime
from typing import Literal
from uuid import UUID

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, field_validator

Direction = Literal["townbound", "outbound"]


class Record(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Stage(Record):
    id: str = Field(min_length=1, max_length=80)
    name: str = Field(min_length=1, max_length=160)


class Route(Stage):
    stage_ids: list[str]
    directions: list[Direction] = Field(min_length=1)


class Catalog(Record):
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
