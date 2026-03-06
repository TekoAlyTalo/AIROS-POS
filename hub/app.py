from __future__ import annotations

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from sqlalchemy.orm import Session

from hub.config import HubSettings, get_settings
from hub.db import create_session_factory
from hub.schemas import PushEventsRequest
from hub.service import HubService, MaterializerError
from shared.ids import stable_uuid


DEV_TOKENS = {
    "dev-staff-token": {"role": "staff", "user_id": stable_uuid("user:staff")},
    "dev-manager-token": {"role": "manager", "user_id": stable_uuid("user:manager")},
    "dev-owner-token": {"role": "owner", "user_id": stable_uuid("user:owner")},
}


def get_auth_context(authorization: str = Header(default="Bearer dev-owner-token")) -> dict[str, str]:
    parts = authorization.split(" ", 1)
    if len(parts) != 2 or parts[0].lower() != "bearer":
        raise HTTPException(status_code=401, detail="invalid authorization header")
    token = DEV_TOKENS.get(parts[1])
    if token is None:
        raise HTTPException(status_code=401, detail="unknown token")
    return token


def _handle_hub_call(fn):
    try:
        return fn()
    except MaterializerError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


def _require_dashboard_role(auth: dict[str, str]) -> None:
    if auth["role"] not in {"manager", "owner"}:
        raise HTTPException(status_code=403, detail="dashboard access requires manager or owner role")


def create_app(settings: HubSettings | None = None) -> FastAPI:
    settings = settings or get_settings()
    session_factory = create_session_factory(settings)
    app = FastAPI(title="AIROS POS Hub", version="0.1.0")

    def get_db():
        db = session_factory()
        try:
            yield db
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    @app.get("/healthz")
    def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/api/sync/pos/events:push")
    def push_events(
        request: PushEventsRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_hub_call(lambda: HubService(db, settings).push_events(request.events))

    @app.post("/api/sync/pos/quarantine/{quarantine_id}:resolve")
    def resolve_quarantine(
        quarantine_id: int,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_hub_call(lambda: HubService(db, settings).resolve_quarantine(quarantine_id))

    @app.get("/api/dashboard/pos/restaurants/{restaurant_key}/table-map")
    def get_table_map(
        restaurant_key: str,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        _require_dashboard_role(auth)
        return _handle_hub_call(lambda: HubService(db, settings).get_table_map(restaurant_key))

    @app.get("/api/dashboard/pos/restaurants/{restaurant_key}/checks/{check_id}")
    def get_check(
        restaurant_key: str,
        check_id: str,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        _require_dashboard_role(auth)
        return _handle_hub_call(lambda: HubService(db, settings).get_check(restaurant_key, check_id))

    @app.get("/api/dashboard/pos/restaurants/{restaurant_key}/reports/daily")
    def get_restaurant_daily_report(
        restaurant_key: str,
        day: str = Query(...),
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        _require_dashboard_role(auth)
        return _handle_hub_call(lambda: HubService(db, settings).get_daily_report([restaurant_key], day))

    @app.get("/api/dashboard/pos/reports/daily")
    def get_multi_restaurant_daily_report(
        day: str = Query(...),
        restaurant_key: list[str] = Query(...),
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        _require_dashboard_role(auth)
        return _handle_hub_call(lambda: HubService(db, settings).get_daily_report(restaurant_key, day))

    @app.get("/api/dashboard/pos/restaurants/{restaurant_key}/reports/vat")
    def get_vat_report(
        restaurant_key: str,
        day: str = Query(...),
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        _require_dashboard_role(auth)
        return _handle_hub_call(lambda: HubService(db, settings).get_vat_report(restaurant_key, day))

    @app.get("/api/dashboard/pos/restaurants/{restaurant_key}/reports/sessions/{session_id}")
    def get_session_report(
        restaurant_key: str,
        session_id: str,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        _require_dashboard_role(auth)
        return _handle_hub_call(lambda: HubService(db, settings).get_session_report(restaurant_key, session_id))

    return app
