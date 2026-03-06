from __future__ import annotations

from pathlib import Path

from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from hub.config import HubSettings, get_settings
from hub.models import Base


def _engine_kwargs(database_url: str) -> dict:
    if database_url.startswith("sqlite"):
        if ":memory:" in database_url:
            return {
                "connect_args": {"check_same_thread": False},
                "poolclass": StaticPool,
            }
        return {"connect_args": {"check_same_thread": False}}
    return {}


def create_session_factory(settings: HubSettings | None = None) -> sessionmaker[Session]:
    settings = settings or get_settings()
    if settings.database_url.startswith("sqlite:///./"):
        Path("data").mkdir(parents=True, exist_ok=True)
    engine = create_engine(settings.database_url, future=True, **_engine_kwargs(settings.database_url))
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine, autoflush=True, autocommit=False, future=True)
