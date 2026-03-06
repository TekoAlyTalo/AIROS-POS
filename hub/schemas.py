from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class PushEventsRequest(StrictModel):
    events: list[dict[str, Any]] = Field(min_length=1)


class ResolveQuarantineRequest(StrictModel):
    note: str | None = None
