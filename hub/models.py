from __future__ import annotations

from datetime import datetime
from typing import Any

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, JSON, String, Text, UniqueConstraint, func
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


class Base(DeclarativeBase):
    pass


class RestaurantRegistry(Base):
    __tablename__ = "restaurant_registry"

    restaurant_key: Mapped[str] = mapped_column(String(128), primary_key=True)
    owner_id: Mapped[str] = mapped_column(String(36), nullable=False)
    restaurant_timezone: Mapped[str] = mapped_column(String(64), nullable=False)


class StreamCursor(Base):
    __tablename__ = "stream_cursors"

    stream_id: Mapped[str] = mapped_column(String(255), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    last_seq: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    blocked: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    blocked_reason: Mapped[str | None] = mapped_column(Text, nullable=True)
    blocked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class AppliedEvent(Base):
    __tablename__ = "applied_events"
    __table_args__ = (UniqueConstraint("stream_id", "seq", name="uq_hub_stream_seq"),)

    event_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    stream_id: Mapped[str] = mapped_column(String(255), index=True, nullable=False)
    seq: Mapped[int] = mapped_column(Integer, nullable=False)
    event_type: Mapped[str] = mapped_column(String(120), nullable=False)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    event_json: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)
    applied_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())


class QuarantineEvent(Base):
    __tablename__ = "quarantine_events"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    event_id: Mapped[str | None] = mapped_column(String(36), nullable=True, index=True)
    restaurant_key: Mapped[str | None] = mapped_column(String(128), nullable=True, index=True)
    stream_id: Mapped[str] = mapped_column(String(255), index=True, nullable=False)
    seq: Mapped[int | None] = mapped_column(Integer, nullable=True)
    reason: Mapped[str] = mapped_column(Text, nullable=False)
    details_json: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)
    event_json: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class TableGroupState(Base):
    __tablename__ = "table_group_state"

    table_group_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    primary_table_id: Mapped[str] = mapped_column(String(36), nullable=False)
    current_party_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="FREE")
    open_total_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    open_checks_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    created_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    closed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    members: Mapped[list["TableGroupMember"]] = relationship(back_populates="group", cascade="all, delete-orphan")


class TableGroupMember(Base):
    __tablename__ = "table_group_members"
    __table_args__ = (UniqueConstraint("table_group_id", "table_id", name="uq_hub_group_table"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    table_group_id: Mapped[str] = mapped_column(ForeignKey("table_group_state.table_group_id"), nullable=False)
    table_id: Mapped[str] = mapped_column(String(36), nullable=False)

    group: Mapped[TableGroupState] = relationship(back_populates="members")


class TableState(Base):
    __tablename__ = "table_state"

    table_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    current_table_group_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    current_party_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="FREE")
    last_seen_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())


class PartyState(Base):
    __tablename__ = "party_state"

    party_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    table_group_id: Mapped[str] = mapped_column(String(36), nullable=False)
    primary_table_id: Mapped[str] = mapped_column(String(36), nullable=False)
    guest_count: Mapped[int] = mapped_column(Integer, nullable=False)
    note: Mapped[str | None] = mapped_column(Text, nullable=True)
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="OPEN")
    opened_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    closed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class CheckState(Base):
    __tablename__ = "check_state"

    check_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    party_id: Mapped[str] = mapped_column(String(36), nullable=False)
    table_group_id: Mapped[str] = mapped_column(String(36), nullable=False)
    currency: Mapped[str] = mapped_column(String(8), nullable=False)
    label: Mapped[str | None] = mapped_column(String(64), nullable=True)
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="OPEN")
    total_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    subtotal_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    tax_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    paid_total_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    rounding_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    amount_due_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    session_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    opened_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    finalized_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class ItemFact(Base):
    __tablename__ = "item_fact"

    item_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    party_id: Mapped[str] = mapped_column(String(36), nullable=False)
    name_snapshot: Mapped[str] = mapped_column(String(200), nullable=False)
    qty: Mapped[int] = mapped_column(Integer, nullable=False)
    pricing_model: Mapped[str] = mapped_column(String(32), nullable=False)
    note: Mapped[str | None] = mapped_column(Text, nullable=True)
    added_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class ItemComponentFact(Base):
    __tablename__ = "item_component_fact"

    component_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    item_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)
    name_snapshot: Mapped[str] = mapped_column(String(200), nullable=False)
    vat_rate: Mapped[str] = mapped_column(String(16), nullable=False)
    unit_gross_cents_snapshot: Mapped[int] = mapped_column(Integer, nullable=False)
    qty: Mapped[int] = mapped_column(Integer, nullable=False)
    gross_cents: Mapped[int] = mapped_column(Integer, nullable=False)
    net_cents: Mapped[int] = mapped_column(Integer, nullable=False)
    tax_cents: Mapped[int] = mapped_column(Integer, nullable=False)


class ItemAssignment(Base):
    __tablename__ = "item_assignment"

    item_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    current_check_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class ItemVoid(Base):
    __tablename__ = "item_void"

    item_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    reason: Mapped[str] = mapped_column(String(64), nullable=False)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class PaymentFact(Base):
    __tablename__ = "payment_fact"

    payment_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    check_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)
    session_id: Mapped[str | None] = mapped_column(String(36), nullable=True, index=True)
    method: Mapped[str] = mapped_column(String(32), nullable=False)
    amount_cents: Mapped[int] = mapped_column(Integer, nullable=False)
    external_ref: Mapped[str | None] = mapped_column(String(128), nullable=True)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class ReceiptFact(Base):
    __tablename__ = "receipt_fact"
    __table_args__ = (UniqueConstraint("restaurant_key", "receipt_no", name="uq_hub_receipt_no"),)

    receipt_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    check_id: Mapped[str] = mapped_column(String(36), nullable=False)
    receipt_no: Mapped[int] = mapped_column(Integer, nullable=False)
    issued_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class SessionState(Base):
    __tablename__ = "session_state"

    session_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="OPEN")
    opening_cash_cents: Mapped[int] = mapped_column(Integer, nullable=False)
    counted_cash_cents: Mapped[int | None] = mapped_column(Integer, nullable=True)
    expected_cash_cents: Mapped[int | None] = mapped_column(Integer, nullable=True)
    diff_cash_cents: Mapped[int | None] = mapped_column(Integer, nullable=True)
    note: Mapped[str | None] = mapped_column(Text, nullable=True)
    opened_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    closed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class DailySalesAgg(Base):
    __tablename__ = "daily_sales_agg"

    restaurant_key: Mapped[str] = mapped_column(String(128), primary_key=True)
    bucket_day: Mapped[str] = mapped_column(String(10), primary_key=True)
    gross_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    net_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    tax_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    rounding_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    amount_due_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    cash_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    card_external_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    paid_checks_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    voided_checks_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)


class DailyVatAgg(Base):
    __tablename__ = "daily_vat_agg"

    restaurant_key: Mapped[str] = mapped_column(String(128), primary_key=True)
    bucket_day: Mapped[str] = mapped_column(String(10), primary_key=True)
    vat_rate: Mapped[str] = mapped_column(String(16), primary_key=True)
    gross_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    net_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    tax_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)


class SessionSalesAgg(Base):
    __tablename__ = "session_sales_agg"

    session_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    restaurant_key: Mapped[str] = mapped_column(String(128), nullable=False, index=True)
    gross_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    net_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    tax_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    cash_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    card_external_cents: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    paid_checks_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
