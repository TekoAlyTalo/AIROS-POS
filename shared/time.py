from __future__ import annotations

from datetime import datetime
from zoneinfo import ZoneInfo


def parse_utc_datetime(value: str) -> datetime:
    normalized = value.replace("Z", "+00:00")
    parsed = datetime.fromisoformat(normalized)
    if parsed.tzinfo is None:
        raise ValueError("datetime value must include timezone information")
    return parsed


def bucket_day(value: str | datetime, timezone_name: str = "Europe/Helsinki") -> str:
    dt = value if isinstance(value, datetime) else parse_utc_datetime(value)
    localized = dt.astimezone(ZoneInfo(timezone_name))
    return localized.date().isoformat()

