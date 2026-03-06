from __future__ import annotations

from collections import defaultdict
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session

from hub.config import HubSettings
from hub.models import (
    AppliedEvent,
    CheckState,
    DailySalesAgg,
    DailyVatAgg,
    ItemAssignment,
    ItemComponentFact,
    ItemFact,
    ItemVoid,
    PartyState,
    PaymentFact,
    QuarantineEvent,
    ReceiptFact,
    RestaurantRegistry,
    SessionSalesAgg,
    SessionState,
    StreamCursor,
    TableGroupMember,
    TableGroupState,
    TableState,
)
from shared.events import EventValidationError, validate_sync_event
from shared.money import round_half_away_from_zero
from shared.time import bucket_day, parse_utc_datetime


class MaterializerError(ValueError):
    """Integrity or materialization error."""


class HubService:
    def __init__(self, db: Session, settings: HubSettings):
        self.db = db
        self.settings = settings

    def push_events(self, events: list[dict[str, Any]]) -> dict[str, Any]:
        result = {
            "received": len(events),
            "applied_event_ids": [],
            "duplicate_event_ids": [],
            "quarantined_event_ids": [],
            "gap_streams": [],
            "blocked_streams": [],
            "stream_cursors": [],
        }

        streams: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for event in events:
            streams[self._extract_stream_id(event)].append(event)

        for stream_id in sorted(streams):
            stream_events = sorted(streams[stream_id], key=self._extract_seq)
            cursor = self._get_or_create_cursor(stream_id, stream_events[0])
            if cursor.blocked:
                result["blocked_streams"].append(
                    {
                        "stream_id": stream_id,
                        "reason": cursor.blocked_reason,
                        "last_seq": cursor.last_seq,
                    }
                )
                result["stream_cursors"].append({"stream_id": stream_id, "last_seq": cursor.last_seq})
                continue

            for event in stream_events:
                event_id = self._extract_event_id(event)
                seq = self._extract_seq(event)
                if event_id and self.db.get(AppliedEvent, event_id):
                    result["duplicate_event_ids"].append(event_id)
                    continue
                if seq <= cursor.last_seq:
                    quarantine_id = self._quarantine(
                        event,
                        cursor,
                        reason="seq_conflict",
                        message=f"seq {seq} is not greater than cursor {cursor.last_seq}",
                    )
                    if event_id:
                        result["quarantined_event_ids"].append(event_id)
                    break

                expected_seq = cursor.last_seq + 1
                if seq > expected_seq:
                    result["gap_streams"].append(
                        {
                            "stream_id": stream_id,
                            "expected_seq": expected_seq,
                            "received_seq": seq,
                        }
                    )
                    break

                try:
                    validate_sync_event(event)
                    occurred_at = parse_utc_datetime(event["occurred_at"])
                    self._ensure_restaurant_registry(event)
                    self._apply_event(event, occurred_at)
                except (EventValidationError, MaterializerError, ValueError) as exc:
                    self._quarantine(event, cursor, reason="validation_error", message=str(exc))
                    if event_id:
                        result["quarantined_event_ids"].append(event_id)
                    break

                self.db.add(
                    AppliedEvent(
                        event_id=event["event_id"],
                        restaurant_key=event["tenant"]["restaurant_key"],
                        stream_id=stream_id,
                        seq=seq,
                        event_type=event["event_type"],
                        occurred_at=occurred_at,
                        event_json=event,
                    )
                )
                cursor.last_seq = seq
                result["applied_event_ids"].append(event["event_id"])

            result["stream_cursors"].append({"stream_id": stream_id, "last_seq": cursor.last_seq})

        self.db.flush()
        return result

    def resolve_quarantine(self, quarantine_id: int) -> dict[str, Any]:
        quarantine = self.db.get(QuarantineEvent, quarantine_id)
        if quarantine is None:
            raise MaterializerError("quarantine row not found")
        quarantine.resolved_at = self._now()
        remaining = self.db.scalar(
            select(func.count(QuarantineEvent.id)).where(
                QuarantineEvent.stream_id == quarantine.stream_id,
                QuarantineEvent.resolved_at.is_(None),
            )
        )
        if not remaining:
            cursor = self.db.get(StreamCursor, quarantine.stream_id)
            if cursor is not None:
                cursor.blocked = False
                cursor.blocked_reason = None
                cursor.blocked_at = None
        self.db.flush()
        return {
            "id": quarantine.id,
            "stream_id": quarantine.stream_id,
            "resolved_at": quarantine.resolved_at.isoformat(),
        }

    def get_table_map(self, restaurant_key: str) -> dict[str, Any]:
        tables = self.db.scalars(
            select(TableState).where(TableState.restaurant_key == restaurant_key).order_by(TableState.table_id)
        ).all()
        groups = self.db.scalars(
            select(TableGroupState)
            .where(TableGroupState.restaurant_key == restaurant_key)
            .order_by(TableGroupState.created_at, TableGroupState.table_group_id)
        ).all()
        return {
            "restaurant_key": restaurant_key,
            "tables": [
                {
                    "table_id": table.table_id,
                    "label": table.table_id[:8],
                    "status": table.status,
                    "current_table_group_id": table.current_table_group_id,
                    "current_party_id": table.current_party_id,
                }
                for table in tables
            ],
            "table_groups": [
                {
                    "table_group_id": group.table_group_id,
                    "primary_table_id": group.primary_table_id,
                    "status": group.status,
                    "current_party_id": group.current_party_id,
                    "open_total_cents": group.open_total_cents,
                    "open_checks_count": group.open_checks_count,
                    "table_ids": [member.table_id for member in group.members],
                }
                for group in groups
            ],
        }

    def get_check(self, restaurant_key: str, check_id: str) -> dict[str, Any]:
        check = self.db.get(CheckState, check_id)
        if check is None or check.restaurant_key != restaurant_key:
            raise MaterializerError("check not found")
        assignments = self.db.scalars(select(ItemAssignment).where(ItemAssignment.current_check_id == check_id)).all()
        item_ids = [assignment.item_id for assignment in assignments]
        items = self.db.scalars(select(ItemFact).where(ItemFact.item_id.in_(item_ids))).all() if item_ids else []
        components = self.db.scalars(
            select(ItemComponentFact).where(ItemComponentFact.item_id.in_(item_ids))
        ).all() if item_ids else []
        voided_item_ids = {
            item_id for item_id in self.db.scalars(select(ItemVoid.item_id).where(ItemVoid.item_id.in_(item_ids))).all()
        } if item_ids else set()

        components_by_item: dict[str, list[ItemComponentFact]] = defaultdict(list)
        for component in components:
            components_by_item[component.item_id].append(component)
        return {
            "check_id": check.check_id,
            "party_id": check.party_id,
            "table_group_id": check.table_group_id,
            "status": check.status,
            "currency": check.currency,
            "label": check.label,
            "total_cents": check.total_cents,
            "subtotal_cents": check.subtotal_cents,
            "tax_cents": check.tax_cents,
            "paid_total_cents": check.paid_total_cents,
            "rounding_cents": check.rounding_cents,
            "amount_due_cents": check.amount_due_cents,
            "items": [
                {
                    "item_id": item.item_id,
                    "name_snapshot": item.name_snapshot,
                    "qty": item.qty,
                    "pricing_model": item.pricing_model,
                    "note": item.note,
                    "voided": item.item_id in voided_item_ids,
                    "components": [
                        {
                            "component_id": component.component_id,
                            "name_snapshot": component.name_snapshot,
                            "vat_rate_snapshot": component.vat_rate,
                            "unit_gross_cents_snapshot": component.unit_gross_cents_snapshot,
                            "qty": component.qty,
                        }
                        for component in components_by_item[item.item_id]
                    ],
                }
                for item in items
            ],
        }

    def get_daily_report(self, restaurant_keys: list[str], day: str) -> dict[str, Any]:
        rows = self.db.scalars(
            select(DailySalesAgg)
            .where(DailySalesAgg.bucket_day == day, DailySalesAgg.restaurant_key.in_(restaurant_keys))
            .order_by(DailySalesAgg.restaurant_key)
        ).all()
        totals = {
            "gross_cents": sum(row.gross_cents for row in rows),
            "net_cents": sum(row.net_cents for row in rows),
            "tax_cents": sum(row.tax_cents for row in rows),
            "rounding_cents": sum(row.rounding_cents for row in rows),
            "amount_due_cents": sum(row.amount_due_cents for row in rows),
            "cash_cents": sum(row.cash_cents for row in rows),
            "card_external_cents": sum(row.card_external_cents for row in rows),
            "paid_checks_count": sum(row.paid_checks_count for row in rows),
            "voided_checks_count": sum(row.voided_checks_count for row in rows),
        }
        return {
            "day": day,
            "restaurant_keys": restaurant_keys,
            "totals": totals,
            "by_restaurant": [
                {
                    "restaurant_key": row.restaurant_key,
                    "gross_cents": row.gross_cents,
                    "net_cents": row.net_cents,
                    "tax_cents": row.tax_cents,
                    "rounding_cents": row.rounding_cents,
                    "amount_due_cents": row.amount_due_cents,
                    "cash_cents": row.cash_cents,
                    "card_external_cents": row.card_external_cents,
                    "paid_checks_count": row.paid_checks_count,
                    "voided_checks_count": row.voided_checks_count,
                }
                for row in rows
            ],
        }

    def get_vat_report(self, restaurant_key: str, day: str) -> dict[str, Any]:
        rows = self.db.scalars(
            select(DailyVatAgg)
            .where(DailyVatAgg.restaurant_key == restaurant_key, DailyVatAgg.bucket_day == day)
            .order_by(DailyVatAgg.vat_rate)
        ).all()
        return {
            "restaurant_key": restaurant_key,
            "day": day,
            "vat_breakdown": [
                {
                    "vat_rate": row.vat_rate,
                    "gross_cents": row.gross_cents,
                    "net_cents": row.net_cents,
                    "tax_cents": row.tax_cents,
                }
                for row in rows
            ],
        }

    def get_session_report(self, restaurant_key: str, session_id: str) -> dict[str, Any]:
        session_state = self.db.get(SessionState, session_id)
        if session_state is None or session_state.restaurant_key != restaurant_key:
            raise MaterializerError("session not found")
        sales = self.db.get(SessionSalesAgg, session_id)
        return {
            "session_id": session_id,
            "restaurant_key": restaurant_key,
            "status": session_state.status,
            "opening_cash_cents": session_state.opening_cash_cents,
            "counted_cash_cents": session_state.counted_cash_cents,
            "expected_cash_cents": session_state.expected_cash_cents,
            "diff_cash_cents": session_state.diff_cash_cents,
            "note": session_state.note,
            "opened_at": session_state.opened_at.isoformat(),
            "closed_at": session_state.closed_at.isoformat() if session_state.closed_at else None,
            "sales": {
                "gross_cents": sales.gross_cents if sales else 0,
                "net_cents": sales.net_cents if sales else 0,
                "tax_cents": sales.tax_cents if sales else 0,
                "cash_cents": sales.cash_cents if sales else 0,
                "card_external_cents": sales.card_external_cents if sales else 0,
                "paid_checks_count": sales.paid_checks_count if sales else 0,
            },
        }

    def _apply_event(self, event: dict[str, Any], occurred_at: datetime) -> None:
        event_type = event["event_type"]
        handlers = {
            "pos.table_group.created": self._apply_table_group_created,
            "pos.table_group.tables_added": self._apply_table_group_tables_added,
            "pos.table_group.tables_removed": self._apply_table_group_tables_removed,
            "pos.table_group.primary_table_set": self._apply_table_group_primary_set,
            "pos.table_group.closed": self._apply_table_group_closed,
            "pos.party.opened": self._apply_party_opened,
            "pos.party.seated": self._apply_party_seated,
            "pos.party.moved": self._apply_party_moved,
            "pos.party.closed": self._apply_party_closed,
            "pos.check.opened": self._apply_check_opened,
            "pos.check.items_added": self._apply_check_items_added,
            "pos.check.items_removed": self._apply_check_items_removed,
            "pos.check.split": self._apply_check_split,
            "pos.check.merged": self._apply_check_merged,
            "pos.check.voided": self._apply_check_voided,
            "pos.payment.recorded": self._apply_payment_recorded,
            "pos.check.finalized": self._apply_check_finalized,
            "pos.receipt.issued": self._apply_receipt_issued,
            "pos.session.opened": self._apply_session_opened,
            "pos.session.closed": self._apply_session_closed,
        }
        try:
            handler = handlers[event_type]
        except KeyError as exc:
            raise MaterializerError(f"unsupported event_type: {event_type}") from exc
        handler(event, occurred_at)

    def _apply_table_group_created(self, event: dict[str, Any], occurred_at: datetime) -> None:
        payload = event["payload"]
        restaurant_key = event["tenant"]["restaurant_key"]
        group = self.db.get(TableGroupState, payload["table_group_id"])
        if group is None:
            group = TableGroupState(
                table_group_id=payload["table_group_id"],
                restaurant_key=restaurant_key,
                primary_table_id=payload["primary_table_id"],
                created_at=occurred_at,
                status="FREE",
            )
            self.db.add(group)
            self.db.flush()
        group.primary_table_id = payload["primary_table_id"]
        desired = set(payload["table_ids"])
        existing = {member.table_id: member for member in group.members}
        for table_id, member in existing.items():
            if table_id not in desired:
                self.db.delete(member)
                table_state = self._get_or_create_table_state(table_id, restaurant_key, occurred_at)
                table_state.current_table_group_id = None
                table_state.current_party_id = None
                table_state.status = "FREE"
        for table_id in payload["table_ids"]:
            if table_id not in existing:
                self.db.add(TableGroupMember(table_group_id=group.table_group_id, table_id=table_id))
            table_state = self._get_or_create_table_state(table_id, restaurant_key, occurred_at)
            table_state.current_table_group_id = group.table_group_id
            table_state.current_party_id = group.current_party_id
            table_state.status = "OCCUPIED" if group.current_party_id else "FREE"

    def _apply_table_group_tables_added(self, event: dict[str, Any], occurred_at: datetime) -> None:
        payload = event["payload"]
        group = self._require_group(payload["table_group_id"])
        for table_id in payload["added_table_ids"]:
            existing = self.db.scalar(
                select(TableGroupMember).where(
                    TableGroupMember.table_group_id == group.table_group_id,
                    TableGroupMember.table_id == table_id,
                )
            )
            if existing is None:
                self.db.add(TableGroupMember(table_group_id=group.table_group_id, table_id=table_id))
            table_state = self._get_or_create_table_state(table_id, group.restaurant_key, occurred_at)
            table_state.current_table_group_id = group.table_group_id
            table_state.current_party_id = group.current_party_id
            table_state.status = "OCCUPIED" if group.current_party_id else "FREE"

    def _apply_table_group_tables_removed(self, event: dict[str, Any], occurred_at: datetime) -> None:
        payload = event["payload"]
        group = self._require_group(payload["table_group_id"])
        removed = set(payload["removed_table_ids"])
        remaining = [member.table_id for member in group.members if member.table_id not in removed]
        if not remaining:
            raise MaterializerError("table group cannot remove all members")
        for member in list(group.members):
            if member.table_id in removed:
                self.db.delete(member)
                table_state = self._get_or_create_table_state(member.table_id, group.restaurant_key, occurred_at)
                table_state.current_table_group_id = None
                table_state.current_party_id = None
                table_state.status = "FREE"
        if group.primary_table_id in removed:
            group.primary_table_id = sorted(remaining)[0]

    def _apply_table_group_primary_set(self, event: dict[str, Any], occurred_at: datetime) -> None:
        payload = event["payload"]
        group = self._require_group(payload["table_group_id"])
        members = {member.table_id for member in group.members}
        if payload["primary_table_id"] not in members:
            raise MaterializerError("primary table must remain a group member")
        group.primary_table_id = payload["primary_table_id"]

    def _apply_table_group_closed(self, event: dict[str, Any], occurred_at: datetime) -> None:
        payload = event["payload"]
        group = self._require_group(payload["table_group_id"])
        group.current_party_id = None
        group.status = payload["final_status"]
        group.closed_at = occurred_at
        for member in group.members:
            table_state = self._get_or_create_table_state(member.table_id, group.restaurant_key, occurred_at)
            table_state.current_table_group_id = None
            table_state.current_party_id = None
            table_state.status = payload["final_status"]
        self._recompute_group(group.table_group_id)

    def _apply_party_opened(self, event: dict[str, Any], occurred_at: datetime) -> None:
        refs = event.get("refs", {})
        party_id = refs.get("party_id")
        table_group_id = refs.get("table_group_id")
        primary_table_id = refs.get("table_id")
        if not party_id or not table_group_id or not primary_table_id:
            raise MaterializerError("party.opened must include party, table_group, and table refs")
        payload = event["payload"]
        party = self.db.get(PartyState, party_id)
        if party is None:
            party = PartyState(
                party_id=party_id,
                restaurant_key=event["tenant"]["restaurant_key"],
                table_group_id=table_group_id,
                primary_table_id=primary_table_id,
                guest_count=payload["guest_count"],
                note=payload.get("note"),
                status="OPEN",
                opened_at=occurred_at,
            )
            self.db.add(party)
        else:
            party.table_group_id = table_group_id
            party.primary_table_id = primary_table_id
            party.guest_count = payload["guest_count"]
            party.note = payload.get("note")
            party.status = "OPEN"

    def _apply_party_seated(self, event: dict[str, Any], occurred_at: datetime) -> None:
        refs = event.get("refs", {})
        party = self._require_party(refs.get("party_id"))
        payload = event["payload"]
        group = self._require_group(payload["table_group_id"])
        party.table_group_id = group.table_group_id
        party.primary_table_id = payload["primary_table_id"]
        group.current_party_id = party.party_id
        group.status = "OCCUPIED"
        for member in group.members:
            table_state = self._get_or_create_table_state(member.table_id, group.restaurant_key, occurred_at)
            table_state.current_table_group_id = group.table_group_id
            table_state.current_party_id = party.party_id
            table_state.status = "OCCUPIED"
        self._recompute_group(group.table_group_id)

    def _apply_party_moved(self, event: dict[str, Any], occurred_at: datetime) -> None:
        refs = event.get("refs", {})
        party = self._require_party(refs.get("party_id"))
        payload = event["payload"]
        from_group = self._require_group(payload["from_table_group_id"])
        to_group = self._require_group(payload["to_table_group_id"])
        from_group.current_party_id = None
        from_group.status = "FREE"
        for member in from_group.members:
            table_state = self._get_or_create_table_state(member.table_id, from_group.restaurant_key, occurred_at)
            table_state.current_table_group_id = None
            table_state.current_party_id = None
            table_state.status = "FREE"

        to_group.current_party_id = party.party_id
        to_group.status = "OCCUPIED"
        party.table_group_id = to_group.table_group_id
        party.primary_table_id = payload["to_primary_table_id"]
        for member in to_group.members:
            table_state = self._get_or_create_table_state(member.table_id, to_group.restaurant_key, occurred_at)
            table_state.current_table_group_id = to_group.table_group_id
            table_state.current_party_id = party.party_id
            table_state.status = "OCCUPIED"

        checks = self.db.scalars(select(CheckState).where(CheckState.party_id == party.party_id)).all()
        for check in checks:
            check.table_group_id = to_group.table_group_id
        self._recompute_group(from_group.table_group_id)
        self._recompute_group(to_group.table_group_id)

    def _apply_party_closed(self, event: dict[str, Any], occurred_at: datetime) -> None:
        refs = event.get("refs", {})
        party = self._require_party(refs.get("party_id"))
        party.status = "CLOSED"
        party.closed_at = occurred_at
        group = self._require_group(party.table_group_id)
        group.current_party_id = None
        group.status = "DIRTY"
        for member in group.members:
            table_state = self._get_or_create_table_state(member.table_id, group.restaurant_key, occurred_at)
            table_state.current_table_group_id = None
            table_state.current_party_id = None
            table_state.status = "DIRTY"
        self._recompute_group(group.table_group_id)

    def _apply_check_opened(self, event: dict[str, Any], occurred_at: datetime) -> None:
        refs = event.get("refs", {})
        check_id = refs.get("check_id")
        party = self._require_party(refs.get("party_id"))
        payload = event["payload"]
        check = self.db.get(CheckState, check_id)
        if check is None:
            check = CheckState(
                check_id=check_id,
                restaurant_key=event["tenant"]["restaurant_key"],
                party_id=party.party_id,
                table_group_id=party.table_group_id,
                currency=payload["currency"],
                label=payload.get("label"),
                status="OPEN",
                opened_at=occurred_at,
            )
            self.db.add(check)
        else:
            check.party_id = party.party_id
            check.table_group_id = party.table_group_id
            check.currency = payload["currency"]
            check.label = payload.get("label")
            check.status = "OPEN"
        self._recompute_group(party.table_group_id)

    def _apply_check_items_added(self, event: dict[str, Any], occurred_at: datetime) -> None:
        refs = event.get("refs", {})
        check = self._require_check(refs.get("check_id"))
        payload = event["payload"]
        for item in payload["items"]:
            if self.db.get(ItemFact, item["item_id"]) is not None:
                raise MaterializerError("item already exists")
            self.db.add(
                ItemFact(
                    item_id=item["item_id"],
                    restaurant_key=event["tenant"]["restaurant_key"],
                    party_id=check.party_id,
                    name_snapshot=item["name_snapshot"],
                    qty=item["qty"],
                    pricing_model=item["pricing_model"],
                    note=item.get("note"),
                    added_at=occurred_at,
                )
            )
            self.db.add(ItemAssignment(item_id=item["item_id"], current_check_id=check.check_id, updated_at=occurred_at))
            for component in item["components"]:
                vat_rate = str(Decimal(str(component["vat_rate_snapshot"])))
                gross_cents = int(component["unit_gross_cents_snapshot"]) * int(component["qty"])
                net_cents = round_half_away_from_zero(
                    Decimal(gross_cents) / (Decimal("1") + Decimal(vat_rate))
                )
                self.db.add(
                    ItemComponentFact(
                        component_id=component["component_id"],
                        restaurant_key=event["tenant"]["restaurant_key"],
                        item_id=item["item_id"],
                        name_snapshot=component["name_snapshot"],
                        vat_rate=vat_rate,
                        unit_gross_cents_snapshot=component["unit_gross_cents_snapshot"],
                        qty=component["qty"],
                        gross_cents=gross_cents,
                        net_cents=net_cents,
                        tax_cents=gross_cents - net_cents,
                    )
                )
        self._recompute_check(check.check_id)
        self._recompute_group(check.table_group_id)

    def _apply_check_items_removed(self, event: dict[str, Any], occurred_at: datetime) -> None:
        refs = event.get("refs", {})
        check = self._require_check(refs.get("check_id"))
        payload = event["payload"]
        for removed in payload["removed"]:
            assignment = self.db.get(ItemAssignment, removed["item_id"])
            if assignment is None or assignment.current_check_id != check.check_id:
                raise MaterializerError("removed item is not assigned to this check")
            if self.db.get(ItemVoid, removed["item_id"]) is not None:
                raise MaterializerError("item already voided")
            self.db.add(ItemVoid(item_id=removed["item_id"], reason=removed["reason"], occurred_at=occurred_at))
        self._recompute_check(check.check_id)
        self._recompute_group(check.table_group_id)

    def _apply_check_split(self, event: dict[str, Any], occurred_at: datetime) -> None:
        payload = event["payload"]
        source = self._require_check(payload["source_check_id"])
        target = self._require_check(payload["target_check_id"])
        for item_id in payload["moved_item_ids"]:
            assignment = self.db.get(ItemAssignment, item_id)
            if assignment is None or assignment.current_check_id != source.check_id:
                raise MaterializerError("split source item mismatch")
            assignment.current_check_id = target.check_id
            assignment.updated_at = occurred_at
        self._recompute_check(source.check_id)
        self._recompute_check(target.check_id)
        self._recompute_group(source.table_group_id)

    def _apply_check_merged(self, event: dict[str, Any], occurred_at: datetime) -> None:
        payload = event["payload"]
        target = self._require_check(payload["target_check_id"])
        for source_check_id in payload["source_check_ids"]:
            source = self._require_check(source_check_id)
            assignments = self.db.scalars(
                select(ItemAssignment).where(ItemAssignment.current_check_id == source.check_id)
            ).all()
            for assignment in assignments:
                assignment.current_check_id = target.check_id
                assignment.updated_at = occurred_at
            self._recompute_check(source.check_id)
        self._recompute_check(target.check_id)
        self._recompute_group(target.table_group_id)

    def _apply_check_voided(self, event: dict[str, Any], occurred_at: datetime) -> None:
        refs = event.get("refs", {})
        check = self._require_check(refs.get("check_id"))
        check.status = "VOID"
        daily = self._get_or_create_daily_sales_agg(check.restaurant_key, self._bucket_day(check.restaurant_key, occurred_at))
        daily.voided_checks_count += 1
        self._recompute_group(check.table_group_id)

    def _apply_payment_recorded(self, event: dict[str, Any], occurred_at: datetime) -> None:
        refs = event.get("refs", {})
        check = self._require_check(refs.get("check_id"))
        if check.status != "OPEN":
            raise MaterializerError("payments can only be applied to open checks")
        payload = event["payload"]
        payment = PaymentFact(
            payment_id=payload["payment_id"],
            restaurant_key=event["tenant"]["restaurant_key"],
            check_id=check.check_id,
            session_id=event["source"].get("session_id"),
            method=payload["method"],
            amount_cents=payload["amount_cents"],
            external_ref=payload.get("external_ref"),
            occurred_at=occurred_at,
        )
        self.db.add(payment)
        check.paid_total_cents += payload["amount_cents"]

    def _apply_check_finalized(self, event: dict[str, Any], occurred_at: datetime) -> None:
        refs = event.get("refs", {})
        check = self._require_check(refs.get("check_id"))
        payload = event["payload"]
        if check.status == "VOID":
            raise MaterializerError("cannot finalize a voided check")
        if check.total_cents + payload["rounding_cents"] != payload["amount_due_cents"]:
            raise MaterializerError("amount_due_cents does not match total_cents + rounding_cents")
        if check.paid_total_cents < payload["amount_due_cents"]:
            raise MaterializerError("paid_total_cents is below amount_due_cents")

        check.status = "PAID"
        check.rounding_cents = payload["rounding_cents"]
        check.amount_due_cents = payload["amount_due_cents"]
        check.finalized_at = occurred_at
        check.session_id = event["source"].get("session_id")

        bucket = self._bucket_day(check.restaurant_key, occurred_at)
        daily = self._get_or_create_daily_sales_agg(check.restaurant_key, bucket)
        daily.gross_cents += check.total_cents
        daily.net_cents += check.subtotal_cents
        daily.tax_cents += check.tax_cents
        daily.rounding_cents += check.rounding_cents
        daily.amount_due_cents += check.amount_due_cents
        daily.paid_checks_count += 1

        payment_breakdown = self._payment_breakdown(check.check_id)
        daily.cash_cents += payment_breakdown["CASH"]
        daily.card_external_cents += payment_breakdown["CARD_EXTERNAL"]

        for vat_rate, amounts in self._vat_breakdown_for_check(check.check_id).items():
            vat_row = self._get_or_create_daily_vat_agg(check.restaurant_key, bucket, vat_rate)
            vat_row.gross_cents += amounts["gross_cents"]
            vat_row.net_cents += amounts["net_cents"]
            vat_row.tax_cents += amounts["tax_cents"]

        if check.session_id:
            session_sales = self._get_or_create_session_sales_agg(check.session_id, check.restaurant_key)
            session_sales.gross_cents += check.total_cents
            session_sales.net_cents += check.subtotal_cents
            session_sales.tax_cents += check.tax_cents
            session_sales.cash_cents += payment_breakdown["CASH"]
            session_sales.card_external_cents += payment_breakdown["CARD_EXTERNAL"]
            session_sales.paid_checks_count += 1

        self._recompute_group(check.table_group_id)

    def _apply_receipt_issued(self, event: dict[str, Any], occurred_at: datetime) -> None:
        refs = event.get("refs", {})
        check = self._require_check(refs.get("check_id"))
        payload = event["payload"]
        self.db.add(
            ReceiptFact(
                receipt_id=payload["receipt_id"],
                restaurant_key=event["tenant"]["restaurant_key"],
                check_id=check.check_id,
                receipt_no=payload["receipt_no"],
                issued_at=parse_utc_datetime(payload["issued_at"]),
                occurred_at=occurred_at,
            )
        )

    def _apply_session_opened(self, event: dict[str, Any], occurred_at: datetime) -> None:
        session_id = event["source"].get("session_id")
        if not session_id:
            raise MaterializerError("session.opened requires source.session_id")
        payload = event["payload"]
        session_state = self.db.get(SessionState, session_id)
        if session_state is None:
            session_state = SessionState(
                session_id=session_id,
                restaurant_key=event["tenant"]["restaurant_key"],
                status="OPEN",
                opening_cash_cents=payload["opening_cash_cents"],
                opened_at=occurred_at,
            )
            self.db.add(session_state)
        else:
            session_state.status = "OPEN"
            session_state.opening_cash_cents = payload["opening_cash_cents"]
            session_state.opened_at = occurred_at

    def _apply_session_closed(self, event: dict[str, Any], occurred_at: datetime) -> None:
        session_id = event["source"].get("session_id")
        if not session_id:
            raise MaterializerError("session.closed requires source.session_id")
        payload = event["payload"]
        session_state = self.db.get(SessionState, session_id)
        if session_state is None:
            raise MaterializerError("session must exist before close")
        session_state.status = "CLOSED"
        session_state.counted_cash_cents = payload["counted_cash_cents"]
        session_state.expected_cash_cents = payload["expected_cash_cents"]
        session_state.diff_cash_cents = payload["diff_cash_cents"]
        session_state.note = payload.get("note")
        session_state.closed_at = occurred_at

    def _ensure_restaurant_registry(self, event: dict[str, Any]) -> None:
        restaurant_key = event["tenant"]["restaurant_key"]
        registry = self.db.get(RestaurantRegistry, restaurant_key)
        if registry is None:
            self.db.add(
                RestaurantRegistry(
                    restaurant_key=restaurant_key,
                    owner_id=event["tenant"]["owner_id"],
                    restaurant_timezone=self.settings.default_restaurant_timezone,
                )
            )

    def _get_or_create_cursor(self, stream_id: str, event: dict[str, Any]) -> StreamCursor:
        cursor = self.db.get(StreamCursor, stream_id)
        if cursor is None:
            cursor = StreamCursor(
                stream_id=stream_id,
                restaurant_key=self._extract_restaurant_key(event),
                last_seq=0,
                blocked=False,
            )
            self.db.add(cursor)
            self.db.flush()
        return cursor

    def _quarantine(self, event: dict[str, Any], cursor: StreamCursor, *, reason: str, message: str) -> int:
        quarantine = QuarantineEvent(
            event_id=self._extract_event_id(event),
            restaurant_key=self._extract_restaurant_key(event),
            stream_id=cursor.stream_id,
            seq=self._extract_seq(event),
            reason=reason,
            details_json={"message": message},
            event_json=event,
        )
        self.db.add(quarantine)
        cursor.blocked = True
        cursor.blocked_reason = message
        cursor.blocked_at = self._now()
        self.db.flush()
        return quarantine.id

    def _get_or_create_table_state(self, table_id: str, restaurant_key: str, occurred_at: datetime) -> TableState:
        table_state = self.db.get(TableState, table_id)
        if table_state is None:
            table_state = TableState(table_id=table_id, restaurant_key=restaurant_key, last_seen_at=occurred_at)
            self.db.add(table_state)
        table_state.last_seen_at = occurred_at
        return table_state

    def _get_or_create_daily_sales_agg(self, restaurant_key: str, day: str) -> DailySalesAgg:
        row = self.db.get(DailySalesAgg, {"restaurant_key": restaurant_key, "bucket_day": day})
        if row is None:
            row = DailySalesAgg(
                restaurant_key=restaurant_key,
                bucket_day=day,
                gross_cents=0,
                net_cents=0,
                tax_cents=0,
                rounding_cents=0,
                amount_due_cents=0,
                cash_cents=0,
                card_external_cents=0,
                paid_checks_count=0,
                voided_checks_count=0,
            )
            self.db.add(row)
        return row

    def _get_or_create_daily_vat_agg(self, restaurant_key: str, day: str, vat_rate: str) -> DailyVatAgg:
        row = self.db.get(
            DailyVatAgg,
            {"restaurant_key": restaurant_key, "bucket_day": day, "vat_rate": vat_rate},
        )
        if row is None:
            row = DailyVatAgg(
                restaurant_key=restaurant_key,
                bucket_day=day,
                vat_rate=vat_rate,
                gross_cents=0,
                net_cents=0,
                tax_cents=0,
            )
            self.db.add(row)
        return row

    def _get_or_create_session_sales_agg(self, session_id: str, restaurant_key: str) -> SessionSalesAgg:
        row = self.db.get(SessionSalesAgg, session_id)
        if row is None:
            row = SessionSalesAgg(
                session_id=session_id,
                restaurant_key=restaurant_key,
                gross_cents=0,
                net_cents=0,
                tax_cents=0,
                cash_cents=0,
                card_external_cents=0,
                paid_checks_count=0,
            )
            self.db.add(row)
        return row

    def _payment_breakdown(self, check_id: str) -> dict[str, int]:
        rows = self.db.scalars(select(PaymentFact).where(PaymentFact.check_id == check_id)).all()
        totals = {"CASH": 0, "CARD_EXTERNAL": 0}
        for payment in rows:
            totals[payment.method] += payment.amount_cents
        return totals

    def _vat_breakdown_for_check(self, check_id: str) -> dict[str, dict[str, int]]:
        item_ids = self.db.scalars(select(ItemAssignment.item_id).where(ItemAssignment.current_check_id == check_id)).all()
        if not item_ids:
            return {}
        voided = {
            item_id for item_id in self.db.scalars(select(ItemVoid.item_id).where(ItemVoid.item_id.in_(item_ids))).all()
        }
        active_item_ids = [item_id for item_id in item_ids if item_id not in voided]
        if not active_item_ids:
            return {}
        breakdown: dict[str, dict[str, int]] = defaultdict(lambda: {"gross_cents": 0, "net_cents": 0, "tax_cents": 0})
        components = self.db.scalars(
            select(ItemComponentFact).where(ItemComponentFact.item_id.in_(active_item_ids))
        ).all()
        for component in components:
            row = breakdown[component.vat_rate]
            row["gross_cents"] += component.gross_cents
            row["net_cents"] += component.net_cents
            row["tax_cents"] += component.tax_cents
        return breakdown

    def _recompute_check(self, check_id: str) -> None:
        check = self._require_check(check_id)
        item_ids = self.db.scalars(select(ItemAssignment.item_id).where(ItemAssignment.current_check_id == check_id)).all()
        if not item_ids:
            check.total_cents = 0
            check.subtotal_cents = 0
            check.tax_cents = 0
            return
        voided = {
            item_id for item_id in self.db.scalars(select(ItemVoid.item_id).where(ItemVoid.item_id.in_(item_ids))).all()
        }
        active_item_ids = [item_id for item_id in item_ids if item_id not in voided]
        if not active_item_ids:
            check.total_cents = 0
            check.subtotal_cents = 0
            check.tax_cents = 0
            return
        components = self.db.scalars(
            select(ItemComponentFact).where(ItemComponentFact.item_id.in_(active_item_ids))
        ).all()
        check.total_cents = sum(component.gross_cents for component in components)
        check.subtotal_cents = sum(component.net_cents for component in components)
        check.tax_cents = check.total_cents - check.subtotal_cents

    def _recompute_group(self, table_group_id: str) -> None:
        group = self._require_group(table_group_id)
        open_checks = self.db.scalars(
            select(CheckState).where(CheckState.table_group_id == table_group_id, CheckState.status == "OPEN")
        ).all()
        group.open_checks_count = len(open_checks)
        group.open_total_cents = sum(check.total_cents for check in open_checks)
        if group.current_party_id is None and group.closed_at is None:
            group.status = "FREE"
        elif group.current_party_id is not None:
            group.status = "OCCUPIED"

    def _bucket_day(self, restaurant_key: str, occurred_at: datetime) -> str:
        registry = self.db.get(RestaurantRegistry, restaurant_key)
        timezone_name = registry.restaurant_timezone if registry else self.settings.default_restaurant_timezone
        return bucket_day(occurred_at, timezone_name)

    def _require_group(self, table_group_id: str | None) -> TableGroupState:
        if not table_group_id:
            raise MaterializerError("table group id is required")
        group = self.db.get(TableGroupState, table_group_id)
        if group is None:
            raise MaterializerError("table group not found")
        return group

    def _require_party(self, party_id: str | None) -> PartyState:
        if not party_id:
            raise MaterializerError("party id is required")
        party = self.db.get(PartyState, party_id)
        if party is None:
            raise MaterializerError("party not found")
        return party

    def _require_check(self, check_id: str | None) -> CheckState:
        if not check_id:
            raise MaterializerError("check id is required")
        check = self.db.get(CheckState, check_id)
        if check is None:
            raise MaterializerError("check not found")
        return check

    @staticmethod
    def _extract_stream_id(event: dict[str, Any]) -> str:
        return str(event.get("source", {}).get("stream_id", "__unknown__"))

    @staticmethod
    def _extract_seq(event: dict[str, Any]) -> int:
        try:
            return int(event.get("source", {}).get("seq", -1))
        except (TypeError, ValueError):
            return -1

    @staticmethod
    def _extract_event_id(event: dict[str, Any]) -> str | None:
        event_id = event.get("event_id")
        return str(event_id) if event_id else None

    @staticmethod
    def _extract_restaurant_key(event: dict[str, Any]) -> str:
        return str(event.get("tenant", {}).get("restaurant_key", "__unknown__"))

    @staticmethod
    def _now() -> datetime:
        return datetime.now(UTC)
