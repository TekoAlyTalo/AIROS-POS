from __future__ import annotations

from collections.abc import Generator
from typing import Any

import pytest
from fastapi.testclient import TestClient

from edge.config import EdgeSettings
from edge.db import create_session_factory as create_edge_session_factory
from edge.service import EdgeService
from hub.app import create_app as create_hub_app
from hub.config import HubSettings
from shared.ids import stable_uuid


def make_event(
    *,
    event_type: str,
    payload: dict[str, Any],
    seq: int,
    occurred_at: str = "2026-03-05T12:00:00+00:00",
    stream_id: str = "stream-1",
    restaurant_key: str = "demo-restaurant",
    refs: dict[str, Any] | None = None,
    session_id: str | None = None,
) -> dict[str, Any]:
    source: dict[str, Any] = {
        "edge_id": stable_uuid("edge:test"),
        "stream_id": stream_id,
        "seq": seq,
        "actor_user_id": stable_uuid("user:test"),
        "channel": "staff.pos",
    }
    if session_id:
        source["session_id"] = session_id
    return {
        "schema": "airos.pos.sync.event.v1",
        "event_id": stable_uuid(f"{stream_id}:{seq}:{event_type}"),
        "event_type": event_type,
        "occurred_at": occurred_at,
        "tenant": {
            "owner_id": stable_uuid("owner:test"),
            "restaurant_key": restaurant_key,
        },
        "source": source,
        "refs": refs or {},
        "payload": {"event_type": event_type, **payload},
    }


@pytest.fixture()
def hub_client() -> Generator[TestClient, None, None]:
    settings = HubSettings(
        database_url="sqlite:///:memory:",
        default_restaurant_timezone="Europe/Helsinki",
    )
    client = TestClient(create_hub_app(settings))
    try:
        yield client
    finally:
        client.close()


@pytest.fixture()
def edge_service() -> Generator[tuple[EdgeService, Any], None, None]:
    settings = EdgeSettings(
        database_url="sqlite:///:memory:",
        restaurant_key="demo-restaurant",
        restaurant_timezone="Europe/Helsinki",
        owner_id=stable_uuid("owner:test"),
        edge_id=stable_uuid("edge:test"),
        stream_id="demo-edge-stream",
        allowed_vat_rates=(0.14, 0.255),
    )
    session_factory = create_edge_session_factory(settings)
    db = session_factory()
    try:
        yield EdgeService(db, settings), db
    finally:
        db.close()
