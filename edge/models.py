from __future__ import annotations

from datetime import datetime
from typing import Any

from sqlalchemy import Boolean, DateTime, Float, ForeignKey, Integer, JSON, String, Text, UniqueConstraint, func
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


class Base(DeclarativeBase):
    pass


class RestaurantConfig(Base):
    __tablename__ = "restaurant_config"

    restaurant_key: Mapped[str] = mapped_column(String(128), primary_key=True)
    owner_id: Mapped[str] = mapped_column(String(36), nullable=False)
    restaurant_timezone: Mapped[str] = mapped_column(String(64), nullable=False)
    allowed_vat_rates_json: Mapped[list[float]] = mapped_column(JSON, nullable=False)
    next_receipt_no: Mapped[int] = mapped_column(Integer, nullable=False, default=1)


class StreamSequence(Base):
    __tablename__ = "stream_sequences"

    stream_id: Mapped[str] = mapped_column(String(255), primary_key=True)
    next_seq: Mapped[int] = mapped_column(Integer, nullable=False, default=1)


class Floorplan(Base):
    __tablename__ = "floorplans"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())

    tables: Mapped[list["RestaurantTable"]] = relationship(back_populates="floorplan")


class RestaurantTable(Base):
    __tablename__ = "restaurant_tables"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    floorplan_id: Mapped[str] = mapped_column(ForeignKey("floorplans.id"), nullable=False)
    label: Mapped[str] = mapped_column(String(32), nullable=False)
    x: Mapped[int] = mapped_column(Integer, nullable=False)
    y: Mapped[int] = mapped_column(Integer, nullable=False)
    w: Mapped[int] = mapped_column(Integer, nullable=False)
    h: Mapped[int] = mapped_column(Integer, nullable=False)
    rotation: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    status: Mapped[str] = mapped_column(String(16), nullable=False)
    current_table_group_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    current_party_id: Mapped[str | None] = mapped_column(String(36), nullable=True)

    floorplan: Mapped[Floorplan] = relationship(back_populates="tables")


class TableGroup(Base):
    __tablename__ = "table_groups"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    primary_table_id: Mapped[str] = mapped_column(String(36), nullable=False)
    current_party_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="FREE")
    open_total_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    open_checks_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    closed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    members: Mapped[list["TableGroupMember"]] = relationship(back_populates="group", cascade="all, delete-orphan")


class TableGroupMember(Base):
    __tablename__ = "table_group_members"
    __table_args__ = (UniqueConstraint("table_group_id", "table_id", name="uq_edge_group_table"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    table_group_id: Mapped[str] = mapped_column(ForeignKey("table_groups.id"), nullable=False)
    table_id: Mapped[str] = mapped_column(String(36), nullable=False)

    group: Mapped[TableGroup] = relationship(back_populates="members")


class Party(Base):
    __tablename__ = "parties"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    table_group_id: Mapped[str] = mapped_column(String(36), nullable=False)
    primary_table_id: Mapped[str] = mapped_column(String(36), nullable=False)
    guest_count: Mapped[int] = mapped_column(Integer, nullable=False)
    note: Mapped[str | None] = mapped_column(Text, nullable=True)
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="OPEN")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    closed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class Check(Base):
    __tablename__ = "checks"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    party_id: Mapped[str] = mapped_column(String(36), nullable=False)
    table_group_id: Mapped[str] = mapped_column(String(36), nullable=False)
    currency: Mapped[str] = mapped_column(String(8), nullable=False, default="EUR")
    label: Mapped[str | None] = mapped_column(String(64), nullable=True)
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="OPEN")
    total_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    subtotal_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    tax_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    paid_total_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    rounding_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    amount_due_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    finalized_payment_method: Mapped[str | None] = mapped_column(String(32), nullable=True)
    receipt_no: Mapped[int | None] = mapped_column(Integer, nullable=True)
    opened_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    finalized_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class CheckItem(Base):
    __tablename__ = "check_items"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    party_id: Mapped[str] = mapped_column(String(36), nullable=False)
    current_check_id: Mapped[str] = mapped_column(String(36), nullable=False)
    product_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    name_snapshot: Mapped[str] = mapped_column(String(200), nullable=False)
    qty: Mapped[int] = mapped_column(Integer, nullable=False)
    note: Mapped[str | None] = mapped_column(Text, nullable=True)
    pricing_model: Mapped[str] = mapped_column(String(32), nullable=False)
    added_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    voided_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    components: Mapped[list["CheckItemComponent"]] = relationship(back_populates="item", cascade="all, delete-orphan")


class ProductCategory(Base):
    __tablename__ = "product_categories"
    __table_args__ = (UniqueConstraint("restaurant_key", "name", name="uq_product_categories_restaurant_name"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    color_code: Mapped[str | None] = mapped_column(String(32), nullable=True)
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True, server_default="1")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
        onupdate=func.now(),
    )

    subcategories: Mapped[list["ProductSubcategory"]] = relationship(
        back_populates="category",
        cascade="all, delete-orphan",
        order_by="ProductSubcategory.sort_order",
    )
    products: Mapped[list["Product"]] = relationship(back_populates="category_ref")
    grid_pages: Mapped[list["ProductGridPage"]] = relationship(back_populates="category_ref")


class ProductSubcategory(Base):
    __tablename__ = "product_subcategories"
    __table_args__ = (
        UniqueConstraint("restaurant_key", "category_id", "name", name="uq_product_subcategories_restaurant_category_name"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    category_id: Mapped[str] = mapped_column(ForeignKey("product_categories.id"), nullable=False)
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True, server_default="1")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
        onupdate=func.now(),
    )

    category: Mapped[ProductCategory] = relationship(back_populates="subcategories")
    products: Mapped[list["Product"]] = relationship(back_populates="subcategory_ref")
    grid_pages: Mapped[list["ProductGridPage"]] = relationship(back_populates="subcategory_ref")


class Product(Base):
    __tablename__ = "products"
    __table_args__ = (UniqueConstraint("restaurant_key", "external_plu", name="uq_products_restaurant_plu"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    external_plu: Mapped[str] = mapped_column(String(64), nullable=False)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    receipt_name: Mapped[str] = mapped_column(String(200), nullable=False)
    barcode: Mapped[str | None] = mapped_column(String(64), nullable=True)
    category: Mapped[str] = mapped_column(String(120), index=True, nullable=False)
    category_id: Mapped[str | None] = mapped_column(ForeignKey("product_categories.id"), nullable=True)
    subcategory_id: Mapped[str | None] = mapped_column(ForeignKey("product_subcategories.id"), nullable=True)
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False, default=0, server_default="0")
    color_code: Mapped[str | None] = mapped_column(String(32), nullable=True)
    unit_gross_cents: Mapped[int] = mapped_column(Integer, nullable=False)
    vat_rate: Mapped[float] = mapped_column(Float, nullable=False)
    image_path: Mapped[str | None] = mapped_column(String(255), nullable=True)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True, server_default="1")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
        onupdate=func.now(),
    )

    category_ref: Mapped[ProductCategory | None] = relationship(back_populates="products")
    subcategory_ref: Mapped[ProductSubcategory | None] = relationship(back_populates="products")
    grid_slots: Mapped[list["ProductGridSlot"]] = relationship(back_populates="product")
    allergens: Mapped[list["ProductAllergenLink"]] = relationship(
        back_populates="product",
        cascade="all, delete-orphan",
        order_by="ProductAllergenLink.allergen_code",
    )


class ProductGridPage(Base):
    __tablename__ = "product_grid_pages"
    __table_args__ = (
        UniqueConstraint(
            "restaurant_key",
            "subcategory_id",
            "page_number",
            name="uq_product_grid_pages_restaurant_subcategory_page",
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    category: Mapped[str] = mapped_column(String(120), index=True, nullable=False)
    category_id: Mapped[str | None] = mapped_column(ForeignKey("product_categories.id"), nullable=True)
    subcategory_id: Mapped[str | None] = mapped_column(ForeignKey("product_subcategories.id"), nullable=True)
    page_number: Mapped[int] = mapped_column(Integer, nullable=False, default=1, server_default="1")
    title: Mapped[str] = mapped_column(String(120), nullable=False)
    rows: Mapped[int] = mapped_column(Integer, nullable=False, default=4)
    cols: Mapped[int] = mapped_column(Integer, nullable=False, default=4)
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
        onupdate=func.now(),
    )

    category_ref: Mapped[ProductCategory | None] = relationship(back_populates="grid_pages")
    subcategory_ref: Mapped[ProductSubcategory | None] = relationship(back_populates="grid_pages")
    slots: Mapped[list["ProductGridSlot"]] = relationship(
        back_populates="page",
        cascade="all, delete-orphan",
        order_by="ProductGridSlot.position",
    )


class ProductGridSlot(Base):
    __tablename__ = "product_grid_slots"

    page_id: Mapped[str] = mapped_column(ForeignKey("product_grid_pages.id"), primary_key=True)
    position: Mapped[int] = mapped_column(Integer, primary_key=True)
    product_id: Mapped[str | None] = mapped_column(ForeignKey("products.id"), nullable=True)
    label_override: Mapped[str | None] = mapped_column(String(120), nullable=True)
    image_override_path: Mapped[str | None] = mapped_column(String(255), nullable=True)

    page: Mapped[ProductGridPage] = relationship(back_populates="slots")
    product: Mapped[Product | None] = relationship(back_populates="grid_slots")


class ProductAllergenLink(Base):
    __tablename__ = "product_allergen_links"

    product_id: Mapped[str] = mapped_column(ForeignKey("products.id"), primary_key=True)
    allergen_code: Mapped[str] = mapped_column(String(32), primary_key=True)

    product: Mapped[Product] = relationship(back_populates="allergens")


class CheckItemComponent(Base):
    __tablename__ = "check_item_components"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    item_id: Mapped[str] = mapped_column(ForeignKey("check_items.id"), nullable=False)
    name_snapshot: Mapped[str] = mapped_column(String(200), nullable=False)
    vat_rate_snapshot: Mapped[float] = mapped_column(Float, nullable=False)
    unit_gross_cents_snapshot: Mapped[int] = mapped_column(Integer, nullable=False)
    qty: Mapped[int] = mapped_column(Integer, nullable=False)

    item: Mapped[CheckItem] = relationship(back_populates="components")


class ItemVoid(Base):
    __tablename__ = "item_voids"
    __table_args__ = (UniqueConstraint("item_id", name="uq_edge_item_void"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    item_id: Mapped[str] = mapped_column(String(36), nullable=False)
    reason: Mapped[str] = mapped_column(String(64), nullable=False)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())


class Payment(Base):
    __tablename__ = "payments"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    check_id: Mapped[str] = mapped_column(String(36), nullable=False)
    session_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    method: Mapped[str] = mapped_column(String(32), nullable=False)
    amount_cents: Mapped[int] = mapped_column(Integer, nullable=False)
    external_ref: Mapped[str | None] = mapped_column(String(128), nullable=True)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())


class SessionState(Base):
    __tablename__ = "sessions"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="OPEN")
    opening_cash_cents: Mapped[int] = mapped_column(Integer, nullable=False)
    counted_cash_cents: Mapped[int | None] = mapped_column(Integer, nullable=True)
    expected_cash_cents: Mapped[int | None] = mapped_column(Integer, nullable=True)
    diff_cash_cents: Mapped[int | None] = mapped_column(Integer, nullable=True)
    note: Mapped[str | None] = mapped_column(Text, nullable=True)
    opened_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    closed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class OutboxEvent(Base):
    __tablename__ = "outbox_events"
    __table_args__ = (
        UniqueConstraint("stream_id", "seq", name="uq_edge_stream_seq"),
        UniqueConstraint("restaurant_key", "event_type", "event_id", name="uq_edge_restaurant_event"),
    )

    event_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    stream_id: Mapped[str] = mapped_column(String(255), index=True, nullable=False)
    seq: Mapped[int] = mapped_column(Integer, nullable=False)
    event_type: Mapped[str] = mapped_column(String(120), nullable=False)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    event_json: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)
    delivered_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
