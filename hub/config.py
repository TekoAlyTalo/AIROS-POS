from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache


@dataclass(frozen=True)
class HubSettings:
    database_url: str
    default_restaurant_timezone: str


@lru_cache(maxsize=1)
def get_settings() -> HubSettings:
    return HubSettings(
        database_url=os.getenv(
            "AIROS_HUB_DATABASE_URL",
            "postgresql+psycopg://postgres:postgres@localhost/airos_pos_hub",
        ),
        default_restaurant_timezone=os.getenv("AIROS_HUB_DEFAULT_TIMEZONE", "Europe/Helsinki"),
    )

