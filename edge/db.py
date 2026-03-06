from __future__ import annotations

from pathlib import Path

from sqlalchemy import create_engine, text
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from edge.config import EdgeSettings, get_settings
from edge.models import Base, Floorplan, RestaurantConfig, RestaurantTable
from shared.ids import stable_uuid


def _engine_kwargs(database_url: str) -> dict:
    if database_url.startswith("sqlite"):
        if ":memory:" in database_url:
            return {
                "connect_args": {"check_same_thread": False},
                "poolclass": StaticPool,
            }
        return {"connect_args": {"check_same_thread": False}}
    return {}


def create_session_factory(settings: EdgeSettings | None = None) -> sessionmaker[Session]:
    settings = settings or get_settings()
    if settings.database_url.startswith("sqlite:///./"):
        Path("data").mkdir(parents=True, exist_ok=True)
    engine = create_engine(settings.database_url, future=True, **_engine_kwargs(settings.database_url))
    Base.metadata.create_all(engine)
    _apply_sqlite_catalog_migrations(engine, settings)
    session_factory = sessionmaker(bind=engine, autoflush=True, autocommit=False, future=True)
    with session_factory() as session:
        seed_defaults(session, settings)
        session.commit()
    return session_factory


def seed_defaults(session: Session, settings: EdgeSettings) -> None:
    config = session.get(RestaurantConfig, settings.restaurant_key)
    if config is None:
        session.add(
            RestaurantConfig(
                restaurant_key=settings.restaurant_key,
                owner_id=settings.owner_id,
                restaurant_timezone=settings.restaurant_timezone,
                allowed_vat_rates_json=list(settings.allowed_vat_rates),
                next_receipt_no=1,
            )
        )

    floorplan_id = stable_uuid(f"floorplan:{settings.restaurant_key}:main")
    floorplan = session.get(Floorplan, floorplan_id)
    if floorplan is None:
        session.add(
            Floorplan(
                id=floorplan_id,
                restaurant_key=settings.restaurant_key,
                name="Main floor",
            )
        )
        coordinates = [
            (40, 40),
            (180, 40),
            (320, 40),
            (460, 40),
            (40, 180),
            (180, 180),
            (320, 180),
            (460, 180),
        ]
        for index, (x, y) in enumerate(coordinates, start=1):
            table_id = stable_uuid(f"table:{settings.restaurant_key}:T{index}")
            session.add(
                RestaurantTable(
                    id=table_id,
                    restaurant_key=settings.restaurant_key,
                    floorplan_id=floorplan_id,
                    label=f"T{index}",
                    x=x,
                    y=y,
                    w=110,
                    h=90,
                    rotation=0,
                    status="FREE",
                )
            )

    from edge.catalog_service import CatalogService

    CatalogService(session, settings).ensure_catalog_bootstrap()


def _apply_sqlite_catalog_migrations(engine, settings: EdgeSettings) -> None:
    if not settings.database_url.startswith("sqlite"):
        return
    with engine.begin() as connection:
        products_columns = _sqlite_columns(connection, "products")
        if "category_id" not in products_columns:
            connection.execute(text("ALTER TABLE products ADD COLUMN category_id TEXT"))
        if "subcategory_id" not in products_columns:
            connection.execute(text("ALTER TABLE products ADD COLUMN subcategory_id TEXT"))
        if "sort_order" not in products_columns:
            connection.execute(text("ALTER TABLE products ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0"))

        grid_columns = _sqlite_columns(connection, "product_grid_pages")
        if "category_id" not in grid_columns:
            connection.execute(text("ALTER TABLE product_grid_pages ADD COLUMN category_id TEXT"))
        if "subcategory_id" not in grid_columns:
            connection.execute(text("ALTER TABLE product_grid_pages ADD COLUMN subcategory_id TEXT"))
        if "page_number" not in grid_columns:
            connection.execute(text("ALTER TABLE product_grid_pages ADD COLUMN page_number INTEGER NOT NULL DEFAULT 1"))

        connection.execute(
            text(
                "CREATE UNIQUE INDEX IF NOT EXISTS uq_product_grid_pages_restaurant_subcategory_page "
                "ON product_grid_pages (restaurant_key, subcategory_id, page_number)"
            )
        )
        connection.execute(
            text(
                "CREATE INDEX IF NOT EXISTS ix_products_category_id "
                "ON products (category_id)"
            )
        )
        connection.execute(
            text(
                "CREATE INDEX IF NOT EXISTS ix_products_subcategory_id "
                "ON products (subcategory_id)"
            )
        )
        connection.execute(
            text(
                "CREATE INDEX IF NOT EXISTS ix_grid_pages_subcategory_id "
                "ON product_grid_pages (subcategory_id)"
            )
        )


def _sqlite_columns(connection, table_name: str) -> set[str]:
    rows = connection.execute(text(f"PRAGMA table_info({table_name})")).mappings().all()
    return {row["name"] for row in rows}
