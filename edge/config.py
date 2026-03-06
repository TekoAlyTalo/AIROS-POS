from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache

from shared.ids import stable_uuid


@dataclass(frozen=True)
class EdgeSettings:
    database_url: str
    restaurant_key: str
    restaurant_timezone: str
    owner_id: str
    edge_id: str
    stream_id: str
    allowed_vat_rates: tuple[float, ...]


@lru_cache(maxsize=1)
def get_settings() -> EdgeSettings:
    restaurant_key = os.getenv("AIROS_EDGE_RESTAURANT_KEY", "demo-restaurant")
    edge_id = os.getenv("AIROS_EDGE_ID", stable_uuid(f"edge:{restaurant_key}"))
    return EdgeSettings(
        database_url=os.getenv("AIROS_EDGE_DATABASE_URL", "sqlite:///./data/edge.db"),
        restaurant_key=restaurant_key,
        restaurant_timezone=os.getenv("AIROS_EDGE_TIMEZONE", "Europe/Helsinki"),
        owner_id=os.getenv("AIROS_OWNER_ID", stable_uuid("owner:demo")),
        edge_id=edge_id,
        stream_id=os.getenv("AIROS_EDGE_STREAM_ID", f"{restaurant_key}:{edge_id}:staff.pos"),
        allowed_vat_rates=(0.14, 0.255),
    )

