from __future__ import annotations

from collections import defaultdict
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import and_, func, select
from sqlalchemy.orm import Session, selectinload

from edge.config import EdgeSettings
from edge.models import (
    Check,
    CheckItem,
    CheckItemComponent,
    Floorplan,
    ItemVoid,
    OutboxEvent,
    Party,
    Payment,
    RestaurantConfig,
    RestaurantTable,
    SessionState,
    StreamSequence,
    TableGroup,
    TableGroupMember,
)
from shared.events import clean_nones, validate_sync_event
from shared.ids import new_uuid
from shared.money import amount_due_with_rounding
from shared.totals import calculate_check_totals


class EdgeError(ValueError):
    """Domain error for the edge service."""


class EdgeService:
    def __init__(self, db: Session, settings: EdgeSettings):
        self.db = db
        self.settings = settings

    def list_floorplans(self) -> list[dict[str, Any]]:
        floorplans = self.db.scalars(
            select(Floorplan).where(Floorplan.restaurant_key == self.settings.restaurant_key)
        ).all()
        return [self._serialize_floorplan(item) for item in floorplans]

    def get_floorplan(self, floorplan_id: str) -> dict[str, Any]:
        floorplan = self._require_floorplan(floorplan_id)
        return self._serialize_floorplan(floorplan)

    def list_tables(self, floorplan_id: str | None = None) -> list[dict[str, Any]]:
        statement = select(RestaurantTable).where(RestaurantTable.restaurant_key == self.settings.restaurant_key)
        if floorplan_id:
            statement = statement.where(RestaurantTable.floorplan_id == floorplan_id)
        tables = self.db.scalars(statement.order_by(RestaurantTable.label)).all()
        return [self._serialize_table(table) for table in tables]

    def list_tables_overview(self) -> list[dict[str, Any]]:
        table_rows = self.db.execute(
            select(
                RestaurantTable.id.label("table_id"),
                RestaurantTable.label,
                RestaurantTable.status,
                RestaurantTable.current_party_id,
                RestaurantTable.current_table_group_id,
                Party.created_at.label("opened_at"),
            )
            .outerjoin(
                Party,
                and_(
                    Party.id == RestaurantTable.current_party_id,
                    Party.restaurant_key == RestaurantTable.restaurant_key,
                ),
            )
            .where(RestaurantTable.restaurant_key == self.settings.restaurant_key)
            .order_by(RestaurantTable.label)
        ).all()

        current_party_ids = {row.current_party_id for row in table_rows if row.current_party_id}
        checks_count_by_party: dict[str, int] = defaultdict(int)
        open_total_by_party: dict[str, int] = defaultdict(int)

        if current_party_ids:
            checks = self.db.scalars(
                select(Check)
                .where(
                    Check.restaurant_key == self.settings.restaurant_key,
                    Check.party_id.in_(current_party_ids),
                )
                .order_by(Check.opened_at, Check.id)
            ).all()
            open_check_ids = [check.id for check in checks if check.status == "OPEN"]
            items_by_check: dict[str, list[CheckItem]] = defaultdict(list)

            for check in checks:
                checks_count_by_party[check.party_id] += 1

            if open_check_ids:
                items = self.db.scalars(
                    select(CheckItem)
                    .where(
                        CheckItem.restaurant_key == self.settings.restaurant_key,
                        CheckItem.current_check_id.in_(open_check_ids),
                    )
                    .options(selectinload(CheckItem.components))
                    .order_by(CheckItem.current_check_id, CheckItem.added_at, CheckItem.id)
                ).all()
                for item in items:
                    items_by_check[item.current_check_id].append(item)

            for check in checks:
                if check.status != "OPEN":
                    continue
                totals = calculate_check_totals(
                    [self._serialize_item(item) for item in items_by_check.get(check.id, [])]
                )
                open_total_by_party[check.party_id] += totals.total_cents

        return [
            {
                "table_id": row.table_id,
                "label": row.label,
                "status": row.status,
                "current_party_id": row.current_party_id,
                "current_table_group_id": row.current_table_group_id,
                "known_checks_count": checks_count_by_party.get(row.current_party_id, 0) if row.current_party_id else 0,
                "known_open_total_gross_cents": (
                    open_total_by_party.get(row.current_party_id, 0) if row.current_party_id else None
                ),
                "opened_at": row.opened_at.isoformat() if row.opened_at else None,
            }
            for row in table_rows
        ]

    def get_party(self, party_id: str) -> dict[str, Any]:
        party = self._require_party(party_id)
        return self._serialize_party(party)

    def list_party_checks(self, party_id: str) -> list[dict[str, Any]]:
        party = self._require_party(party_id)
        checks = self.db.scalars(
            select(Check)
            .where(Check.restaurant_key == self.settings.restaurant_key, Check.party_id == party.id)
            .order_by(Check.opened_at, Check.id)
        ).all()
        return [self._serialize_check(check) for check in checks]

    def update_table_status(self, table_id: str, status: str) -> dict[str, Any]:
        if status not in {"FREE", "OCCUPIED", "DIRTY", "RESERVED"}:
            raise EdgeError("invalid table status")
        table = self._require_table(table_id)
        table.status = status
        self.db.flush()
        return self._serialize_table(table)

    def open_party(
        self,
        *,
        guest_count: int,
        table_ids: list[str],
        primary_table_id: str,
        note: str | None,
        actor_user_id: str,
    ) -> dict[str, Any]:
        self._validate_primary(table_ids, primary_table_id)
        self._ensure_tables_available(table_ids)
        occurred_at = self._now()
        party_id = new_uuid()
        table_group_id = new_uuid()

        group = TableGroup(
            id=table_group_id,
            restaurant_key=self.settings.restaurant_key,
            primary_table_id=primary_table_id,
            current_party_id=party_id,
            status="OCCUPIED",
        )
        party = Party(
            id=party_id,
            restaurant_key=self.settings.restaurant_key,
            table_group_id=table_group_id,
            primary_table_id=primary_table_id,
            guest_count=guest_count,
            note=note,
            status="OPEN",
            created_at=occurred_at,
        )
        self.db.add(group)
        self.db.add(party)
        for table_id in table_ids:
            self.db.add(TableGroupMember(table_group_id=table_group_id, table_id=table_id))
            table = self._require_table(table_id)
            table.status = "OCCUPIED"
            table.current_table_group_id = table_group_id
            table.current_party_id = party_id

        refs = {"table_id": primary_table_id, "table_group_id": table_group_id, "party_id": party_id}
        self._emit_event(
            payload={"event_type": "pos.party.opened", "guest_count": guest_count, "note": note},
            refs=refs,
            actor_user_id=actor_user_id,
            occurred_at=occurred_at,
        )
        self._emit_event(
            payload={
                "event_type": "pos.table_group.created",
                "table_group_id": table_group_id,
                "table_ids": table_ids,
                "primary_table_id": primary_table_id,
                "reason": "party_seating",
            },
            refs=refs,
            actor_user_id=actor_user_id,
            occurred_at=occurred_at,
        )
        self._emit_event(
            payload={
                "event_type": "pos.party.seated",
                "table_group_id": table_group_id,
                "primary_table_id": primary_table_id,
            },
            refs=refs,
            actor_user_id=actor_user_id,
            occurred_at=occurred_at,
        )
        self.db.flush()
        return self._serialize_party(party)

    def move_party(
        self,
        party_id: str,
        *,
        table_ids: list[str],
        primary_table_id: str,
        reason: str,
        actor_user_id: str,
    ) -> dict[str, Any]:
        self._validate_primary(table_ids, primary_table_id)
        party = self._require_party(party_id)
        if party.status != "OPEN":
            raise EdgeError("party is not open")
        self._ensure_tables_available(table_ids, allow_party_id=party.id)

        occurred_at = self._now()
        from_group = self._require_table_group(party.table_group_id)
        new_group_id = new_uuid()
        new_group = TableGroup(
            id=new_group_id,
            restaurant_key=self.settings.restaurant_key,
            primary_table_id=primary_table_id,
            current_party_id=party.id,
            status="OCCUPIED",
        )
        self.db.add(new_group)
        for table_id in table_ids:
            self.db.add(TableGroupMember(table_group_id=new_group_id, table_id=table_id))
            table = self._require_table(table_id)
            table.status = "OCCUPIED"
            table.current_table_group_id = new_group_id
            table.current_party_id = party.id

        old_table_ids = [member.table_id for member in from_group.members]
        for table_id in old_table_ids:
            if table_id in table_ids:
                continue
            table = self._require_table(table_id)
            table.status = "FREE"
            table.current_table_group_id = None
            table.current_party_id = None

        from_group.current_party_id = None
        from_group.status = "FREE"
        party.table_group_id = new_group_id
        party.primary_table_id = primary_table_id

        checks = self.db.scalars(select(Check).where(Check.party_id == party.id)).all()
        for check in checks:
            check.table_group_id = new_group_id

        self._emit_event(
            payload={
                "event_type": "pos.table_group.created",
                "table_group_id": new_group_id,
                "table_ids": table_ids,
                "primary_table_id": primary_table_id,
                "reason": "party_seating",
            },
            refs={"table_group_id": new_group_id, "table_id": primary_table_id, "party_id": party.id},
            actor_user_id=actor_user_id,
            occurred_at=occurred_at,
        )
        self._emit_event(
            payload={
                "event_type": "pos.party.moved",
                "from_table_group_id": from_group.id,
                "to_table_group_id": new_group_id,
                "from_primary_table_id": from_group.primary_table_id,
                "to_primary_table_id": primary_table_id,
                "reason": reason,
            },
            refs={"table_group_id": new_group_id, "table_id": primary_table_id, "party_id": party.id},
            actor_user_id=actor_user_id,
            occurred_at=occurred_at,
        )
        self._recompute_group(from_group.id)
        self._recompute_group(new_group_id)
        self.db.flush()
        return self._serialize_party(party)

    def close_party(
        self,
        party_id: str,
        *,
        reason: str,
        final_status: str,
        actor_user_id: str,
    ) -> dict[str, Any]:
        party = self._require_party(party_id)
        open_checks = self.db.scalar(
            select(func.count(Check.id)).where(Check.party_id == party.id, Check.status == "OPEN")
        )
        if open_checks:
            raise EdgeError("cannot close party with open checks")

        occurred_at = self._now()
        party.status = "CLOSED"
        party.closed_at = occurred_at
        group = self._require_table_group(party.table_group_id)
        group.current_party_id = None
        group.status = final_status
        group.closed_at = occurred_at
        for member in group.members:
            table = self._require_table(member.table_id)
            table.status = final_status
            table.current_table_group_id = None
            table.current_party_id = None

        refs = {"table_group_id": group.id, "table_id": party.primary_table_id, "party_id": party.id}
        self._emit_event(
            payload={"event_type": "pos.party.closed", "reason": reason},
            refs=refs,
            actor_user_id=actor_user_id,
            occurred_at=occurred_at,
        )
        self._emit_event(
            payload={
                "event_type": "pos.table_group.closed",
                "table_group_id": group.id,
                "final_status": final_status,
                "reason": "party_closed",
            },
            refs=refs,
            actor_user_id=actor_user_id,
            occurred_at=occurred_at,
        )
        self._recompute_group(group.id)
        self.db.flush()
        return self._serialize_party(party)

    def open_check(self, party_id: str, *, label: str | None, actor_user_id: str) -> dict[str, Any]:
        party = self._require_party(party_id)
        if party.status != "OPEN":
            raise EdgeError("party is not open")
        occurred_at = self._now()
        check = Check(
            id=new_uuid(),
            restaurant_key=self.settings.restaurant_key,
            party_id=party.id,
            table_group_id=party.table_group_id,
            currency="EUR",
            label=label,
            status="OPEN",
            opened_at=occurred_at,
        )
        self.db.add(check)
        self._emit_event(
            payload={"event_type": "pos.check.opened", "currency": "EUR", "label": label},
            refs={"party_id": party.id, "check_id": check.id, "table_group_id": party.table_group_id},
            actor_user_id=actor_user_id,
            occurred_at=occurred_at,
        )
        self.db.flush()
        self._recompute_group(party.table_group_id)
        return self._serialize_check(check)

    def get_check(self, check_id: str) -> dict[str, Any]:
        check = self._require_check(check_id)
        return self._serialize_check(check)

    def add_items(self, check_id: str, *, items: list[dict[str, Any]], actor_user_id: str) -> dict[str, Any]:
        check = self._require_check(check_id)
        if check.status != "OPEN":
            raise EdgeError("check is not open")
        config = self._require_config()
        occurred_at = self._now()
        payload_items: list[dict[str, Any]] = []
        for item_data in items:
            self._validate_item_rates(item_data, config.allowed_vat_rates_json)
            item_id = item_data.get("item_id") or new_uuid()
            item = CheckItem(
                id=item_id,
                restaurant_key=self.settings.restaurant_key,
                party_id=check.party_id,
                current_check_id=check.id,
                product_id=item_data.get("product_id"),
                name_snapshot=item_data["name_snapshot"],
                qty=item_data["qty"],
                note=item_data.get("note"),
                pricing_model=item_data["pricing_model"],
                added_at=occurred_at,
            )
            self.db.add(item)
            payload_components: list[dict[str, Any]] = []
            for component_data in item_data["components"]:
                component_id = component_data.get("component_id") or new_uuid()
                payload_component = {
                    "component_id": component_id,
                    "name_snapshot": component_data["name_snapshot"],
                    "vat_rate_snapshot": component_data["vat_rate_snapshot"],
                    "unit_gross_cents_snapshot": component_data["unit_gross_cents_snapshot"],
                    "qty": component_data["qty"],
                }
                payload_components.append(payload_component)
                self.db.add(
                    CheckItemComponent(
                        id=component_id,
                        restaurant_key=self.settings.restaurant_key,
                        item_id=item_id,
                        name_snapshot=component_data["name_snapshot"],
                        vat_rate_snapshot=component_data["vat_rate_snapshot"],
                        unit_gross_cents_snapshot=component_data["unit_gross_cents_snapshot"],
                        qty=component_data["qty"],
                    )
                )
            payload_items.append(
                {
                    "item_id": item_id,
                    "product_id": item_data.get("product_id"),
                    "name_snapshot": item_data["name_snapshot"],
                    "qty": item_data["qty"],
                    "note": item_data.get("note"),
                    "pricing_model": item_data["pricing_model"],
                    "components": payload_components,
                }
            )

        self._emit_event(
            payload={"event_type": "pos.check.items_added", "items": payload_items},
            refs={"check_id": check.id, "party_id": check.party_id, "table_group_id": check.table_group_id},
            actor_user_id=actor_user_id,
            occurred_at=occurred_at,
        )
        self.db.flush()
        self._recompute_check(check.id)
        self._recompute_group(check.table_group_id)
        return self._serialize_check(check)

    def remove_items(self, check_id: str, *, removed: list[dict[str, Any]], actor_user_id: str) -> dict[str, Any]:
        check = self._require_check(check_id)
        if check.status != "OPEN":
            raise EdgeError("check is not open")
        occurred_at = self._now()
        payload_removed: list[dict[str, Any]] = []
        for item_data in removed:
            item = self._require_item(item_data["item_id"])
            if item.current_check_id != check.id:
                raise EdgeError("item is not assigned to this check")
            if item.voided_at is not None:
                raise EdgeError("item already voided")
            item.voided_at = occurred_at
            self.db.add(ItemVoid(item_id=item.id, reason=item_data["reason"], occurred_at=occurred_at))
            payload_removed.append({"item_id": item.id, "reason": item_data["reason"]})

        self._emit_event(
            payload={"event_type": "pos.check.items_removed", "removed": payload_removed},
            refs={"check_id": check.id, "party_id": check.party_id, "table_group_id": check.table_group_id},
            actor_user_id=actor_user_id,
            occurred_at=occurred_at,
        )
        self.db.flush()
        self._recompute_check(check.id)
        self._recompute_group(check.table_group_id)
        return self._serialize_check(check)

    def split_check(
        self,
        check_id: str,
        *,
        moved_item_ids: list[str],
        target_check_id: str | None,
        label: str | None,
        actor_user_id: str,
    ) -> dict[str, Any]:
        source = self._require_check(check_id)
        if source.status != "OPEN":
            raise EdgeError("source check is not open")
        occurred_at = self._now()
        mode = "EXISTING_CHECK"
        if target_check_id is None:
            mode = "NEW_CHECK"
            target = Check(
                id=new_uuid(),
                restaurant_key=self.settings.restaurant_key,
                party_id=source.party_id,
                table_group_id=source.table_group_id,
                currency="EUR",
                label=label,
                status="OPEN",
                opened_at=occurred_at,
            )
            self.db.add(target)
            self._emit_event(
                payload={"event_type": "pos.check.opened", "currency": "EUR", "label": label},
                refs={"party_id": source.party_id, "check_id": target.id, "table_group_id": source.table_group_id},
                actor_user_id=actor_user_id,
                occurred_at=occurred_at,
            )
        else:
            target = self._require_check(target_check_id)
            if target.status != "OPEN":
                raise EdgeError("target check is not open")

        for item_id in moved_item_ids:
            item = self._require_item(item_id)
            if item.current_check_id != source.id:
                raise EdgeError("all moved items must belong to source check")
            item.current_check_id = target.id

        self._emit_event(
            payload={
                "event_type": "pos.check.split",
                "source_check_id": source.id,
                "target_check_id": target.id,
                "moved_item_ids": moved_item_ids,
                "mode": mode,
            },
            refs={"party_id": source.party_id, "check_id": target.id, "table_group_id": source.table_group_id},
            actor_user_id=actor_user_id,
            occurred_at=occurred_at,
        )
        self.db.flush()
        self._recompute_check(source.id)
        self._recompute_check(target.id)
        self._recompute_group(source.table_group_id)
        return self._serialize_check(target)

    def merge_checks(
        self,
        *,
        target_check_id: str,
        source_check_ids: list[str],
        actor_user_id: str,
    ) -> dict[str, Any]:
        target = self._require_check(target_check_id)
        if target.status != "OPEN":
            raise EdgeError("target check is not open")
        occurred_at = self._now()
        for source_id in source_check_ids:
            if source_id == target.id:
                raise EdgeError("source checks must not include target check")
            source = self._require_check(source_id)
            if source.party_id != target.party_id:
                raise EdgeError("checks must belong to the same party")
            items = self.db.scalars(select(CheckItem).where(CheckItem.current_check_id == source.id)).all()
            for item in items:
                item.current_check_id = target.id

        self._emit_event(
            payload={
                "event_type": "pos.check.merged",
                "target_check_id": target.id,
                "source_check_ids": source_check_ids,
            },
            refs={"party_id": target.party_id, "check_id": target.id, "table_group_id": target.table_group_id},
            actor_user_id=actor_user_id,
            occurred_at=occurred_at,
        )
        self.db.flush()
        self._recompute_check(target.id)
        for source_id in source_check_ids:
            self._recompute_check(source_id)
        self._recompute_group(target.table_group_id)
        return self._serialize_check(target)

    def void_check(self, check_id: str, *, reason_code: str, reason_note: str | None, actor_user_id: str) -> dict[str, Any]:
        check = self._require_check(check_id)
        if check.status != "OPEN":
            raise EdgeError("check is not open")
        occurred_at = self._now()
        check.status = "VOID"
        self._emit_event(
            payload={"event_type": "pos.check.voided", "reason_code": reason_code, "reason_note": reason_note},
            refs={"party_id": check.party_id, "check_id": check.id, "table_group_id": check.table_group_id},
            actor_user_id=actor_user_id,
            occurred_at=occurred_at,
        )
        self.db.flush()
        self._recompute_group(check.table_group_id)
        return self._serialize_check(check)

    def record_payment(
        self,
        check_id: str,
        *,
        method: str,
        amount_cents: int,
        external_ref: str | None,
        actor_user_id: str,
    ) -> dict[str, Any]:
        check = self._require_check(check_id)
        if check.status != "OPEN":
            raise EdgeError("payments can only be recorded on open checks")
        occurred_at = self._now()
        session_id = self._active_session_id()
        payment = Payment(
            id=new_uuid(),
            restaurant_key=self.settings.restaurant_key,
            check_id=check.id,
            session_id=session_id,
            method=method,
            amount_cents=amount_cents,
            external_ref=external_ref,
            occurred_at=occurred_at,
        )
        self.db.add(payment)
        check.paid_total_cents += amount_cents
        self._emit_event(
            payload={
                "event_type": "pos.payment.recorded",
                "payment_id": payment.id,
                "method": method,
                "amount_cents": amount_cents,
                "external_ref": external_ref,
            },
            refs={"party_id": check.party_id, "check_id": check.id, "table_group_id": check.table_group_id},
            actor_user_id=actor_user_id,
            occurred_at=occurred_at,
            session_id=session_id,
        )
        self.db.flush()
        return self._serialize_check(check)

    def finalize_check(
        self,
        check_id: str,
        *,
        payment_method: str,
        issue_receipt: bool,
        actor_user_id: str,
    ) -> dict[str, Any]:
        check = self._require_check(check_id)
        if check.status != "OPEN":
            raise EdgeError("check is not open")
        occurred_at = self._now()
        rounding_cents, amount_due_cents = amount_due_with_rounding(check.total_cents, payment_method)
        if check.paid_total_cents < amount_due_cents:
            raise EdgeError("paid_total_cents is below amount_due_cents")

        check.status = "PAID"
        check.finalized_payment_method = payment_method
        check.rounding_cents = rounding_cents
        check.amount_due_cents = amount_due_cents
        check.finalized_at = occurred_at
        session_id = self._active_session_id()
        self._emit_event(
            payload={
                "event_type": "pos.check.finalized",
                "final_status": "PAID",
                "rounding_cents": rounding_cents,
                "amount_due_cents": amount_due_cents,
            },
            refs={"party_id": check.party_id, "check_id": check.id, "table_group_id": check.table_group_id},
            actor_user_id=actor_user_id,
            occurred_at=occurred_at,
            session_id=session_id,
        )

        if issue_receipt:
            config = self._require_config()
            receipt_no = config.next_receipt_no
            config.next_receipt_no += 1
            check.receipt_no = receipt_no
            receipt_id = new_uuid()
            self._emit_event(
                payload={
                    "event_type": "pos.receipt.issued",
                    "receipt_id": receipt_id,
                    "receipt_no": receipt_no,
                    "issued_at": occurred_at.isoformat(),
                },
                refs={
                    "party_id": check.party_id,
                    "check_id": check.id,
                    "table_group_id": check.table_group_id,
                    "receipt_id": receipt_id,
                },
                actor_user_id=actor_user_id,
                occurred_at=occurred_at,
                session_id=session_id,
            )

        self.db.flush()
        self._recompute_group(check.table_group_id)
        return self._serialize_check(check)

    def open_session(self, *, opening_cash_cents: int, actor_user_id: str) -> dict[str, Any]:
        if self._active_session_id() is not None:
            raise EdgeError("a session is already open")
        occurred_at = self._now()
        session_state = SessionState(
            id=new_uuid(),
            restaurant_key=self.settings.restaurant_key,
            status="OPEN",
            opening_cash_cents=opening_cash_cents,
            opened_at=occurred_at,
        )
        self.db.add(session_state)
        self._emit_event(
            payload={"event_type": "pos.session.opened", "opening_cash_cents": opening_cash_cents},
            refs=None,
            actor_user_id=actor_user_id,
            occurred_at=occurred_at,
            session_id=session_state.id,
        )
        self.db.flush()
        return self._serialize_session(session_state)

    def close_session(self, *, counted_cash_cents: int, note: str | None, actor_user_id: str) -> dict[str, Any]:
        session_state = self._require_active_session()
        occurred_at = self._now()
        cash_total = self.db.scalar(
            select(func.coalesce(func.sum(Payment.amount_cents), 0)).where(
                Payment.session_id == session_state.id,
                Payment.method == "CASH",
            )
        )
        expected_cash_cents = session_state.opening_cash_cents + int(cash_total or 0)
        diff_cash_cents = counted_cash_cents - expected_cash_cents
        session_state.status = "CLOSED"
        session_state.counted_cash_cents = counted_cash_cents
        session_state.expected_cash_cents = expected_cash_cents
        session_state.diff_cash_cents = diff_cash_cents
        session_state.note = note
        session_state.closed_at = occurred_at
        self._emit_event(
            payload={
                "event_type": "pos.session.closed",
                "counted_cash_cents": counted_cash_cents,
                "expected_cash_cents": expected_cash_cents,
                "diff_cash_cents": diff_cash_cents,
                "note": note,
            },
            refs=None,
            actor_user_id=actor_user_id,
            occurred_at=occurred_at,
            session_id=session_state.id,
        )
        self.db.flush()
        return self._serialize_session(session_state)

    def get_current_session(self) -> dict[str, Any] | None:
        session_state = self.db.scalar(
            select(SessionState).where(SessionState.status == "OPEN").order_by(SessionState.opened_at.desc())
        )
        if session_state is None:
            return None
        return self._serialize_session(session_state)

    def list_pending_outbox(self, limit: int = 100) -> list[dict[str, Any]]:
        events = self.db.scalars(
            select(OutboxEvent)
            .where(OutboxEvent.delivered_at.is_(None))
            .order_by(OutboxEvent.stream_id, OutboxEvent.seq)
            .limit(limit)
        ).all()
        return [event.event_json for event in events]

    def mark_outbox_delivered(self, event_ids: list[str]) -> dict[str, int]:
        occurred_at = self._now()
        events = self.db.scalars(select(OutboxEvent).where(OutboxEvent.event_id.in_(event_ids))).all()
        for event in events:
            event.delivered_at = occurred_at
        self.db.flush()
        return {"marked": len(events)}

    def _emit_event(
        self,
        *,
        payload: dict[str, Any],
        refs: dict[str, Any] | None,
        actor_user_id: str,
        occurred_at: datetime,
        session_id: str | None = None,
    ) -> dict[str, Any]:
        seq = self._next_seq(self.settings.stream_id)
        source = {
            "edge_id": self.settings.edge_id,
            "stream_id": self.settings.stream_id,
            "seq": seq,
            "actor_user_id": actor_user_id,
            "channel": "staff.pos",
        }
        if session_id is not None:
            source["session_id"] = session_id
        event = clean_nones(
            {
            "schema": "airos.pos.sync.event.v1",
            "event_id": new_uuid(),
            "event_type": payload["event_type"],
            "occurred_at": occurred_at.isoformat(),
            "tenant": {
                "owner_id": self.settings.owner_id,
                "restaurant_key": self.settings.restaurant_key,
            },
            "source": source,
            "refs": refs or {},
            "payload": payload,
            }
        )
        validate_sync_event(event)
        self.db.add(
            OutboxEvent(
                event_id=event["event_id"],
                restaurant_key=self.settings.restaurant_key,
                stream_id=self.settings.stream_id,
                seq=seq,
                event_type=event["event_type"],
                occurred_at=occurred_at,
                event_json=event,
            )
        )
        return event

    def _next_seq(self, stream_id: str) -> int:
        stream = self.db.get(StreamSequence, stream_id)
        if stream is None:
            stream = StreamSequence(stream_id=stream_id, next_seq=1)
            self.db.add(stream)
            self.db.flush()
        current_seq = stream.next_seq
        stream.next_seq += 1
        return current_seq

    def _recompute_check(self, check_id: str) -> None:
        check = self._require_check(check_id)
        items = self.db.scalars(
            select(CheckItem)
            .where(CheckItem.current_check_id == check.id)
            .options(selectinload(CheckItem.components))
            .order_by(CheckItem.added_at)
        ).all()
        totals = calculate_check_totals([self._serialize_item(item) for item in items])
        check.total_cents = totals.total_cents
        check.subtotal_cents = totals.subtotal_cents
        check.tax_cents = totals.tax_cents

    def _recompute_group(self, table_group_id: str) -> None:
        group = self._require_table_group(table_group_id)
        open_checks = self.db.scalars(
            select(Check).where(Check.table_group_id == table_group_id, Check.status == "OPEN")
        ).all()
        group.open_checks_count = len(open_checks)
        group.open_total_cents = sum(check.total_cents for check in open_checks)
        if group.current_party_id is None and group.closed_at is None:
            group.status = "FREE"
        elif group.current_party_id is not None:
            group.status = "OCCUPIED"

    def _validate_item_rates(self, item_data: dict[str, Any], allowed_rates: list[float]) -> None:
        allowed = {round(rate, 3) for rate in allowed_rates}
        for component in item_data["components"]:
            if round(component["vat_rate_snapshot"], 3) not in allowed:
                raise EdgeError("component VAT rate is not allowed for this restaurant")

    def _ensure_tables_available(self, table_ids: list[str], allow_party_id: str | None = None) -> None:
        for table_id in table_ids:
            table = self._require_table(table_id)
            if table.current_party_id and table.current_party_id != allow_party_id:
                raise EdgeError(f"table {table.label} is already occupied")

    @staticmethod
    def _validate_primary(table_ids: list[str], primary_table_id: str) -> None:
        if primary_table_id not in table_ids:
            raise EdgeError("primary_table_id must be included in table_ids")

    def _active_session_id(self) -> str | None:
        session_state = self.db.scalar(
            select(SessionState).where(SessionState.status == "OPEN").order_by(SessionState.opened_at.desc())
        )
        if session_state is None:
            return None
        return session_state.id

    def _require_active_session(self) -> SessionState:
        session_state = self.db.scalar(
            select(SessionState).where(SessionState.status == "OPEN").order_by(SessionState.opened_at.desc())
        )
        if session_state is None:
            raise EdgeError("no open session")
        return session_state

    def _require_floorplan(self, floorplan_id: str) -> Floorplan:
        floorplan = self.db.get(Floorplan, floorplan_id)
        if floorplan is None or floorplan.restaurant_key != self.settings.restaurant_key:
            raise EdgeError("floorplan not found")
        return floorplan

    def _require_table(self, table_id: str) -> RestaurantTable:
        table = self.db.get(RestaurantTable, table_id)
        if table is None or table.restaurant_key != self.settings.restaurant_key:
            raise EdgeError("table not found")
        return table

    def _require_table_group(self, table_group_id: str) -> TableGroup:
        group = self.db.scalar(
            select(TableGroup)
            .where(TableGroup.id == table_group_id, TableGroup.restaurant_key == self.settings.restaurant_key)
            .options(selectinload(TableGroup.members))
        )
        if group is None:
            raise EdgeError("table group not found")
        return group

    def _require_party(self, party_id: str) -> Party:
        party = self.db.get(Party, party_id)
        if party is None or party.restaurant_key != self.settings.restaurant_key:
            raise EdgeError("party not found")
        return party

    def _require_check(self, check_id: str) -> Check:
        check = self.db.get(Check, check_id)
        if check is None or check.restaurant_key != self.settings.restaurant_key:
            raise EdgeError("check not found")
        return check

    def _require_item(self, item_id: str) -> CheckItem:
        item = self.db.scalar(
            select(CheckItem)
            .where(CheckItem.id == item_id, CheckItem.restaurant_key == self.settings.restaurant_key)
            .options(selectinload(CheckItem.components))
        )
        if item is None:
            raise EdgeError("item not found")
        return item

    def _require_config(self) -> RestaurantConfig:
        config = self.db.get(RestaurantConfig, self.settings.restaurant_key)
        if config is None:
            raise EdgeError("restaurant configuration missing")
        return config

    def _serialize_floorplan(self, floorplan: Floorplan) -> dict[str, Any]:
        tables = self.db.scalars(
            select(RestaurantTable).where(RestaurantTable.floorplan_id == floorplan.id).order_by(RestaurantTable.label)
        ).all()
        return {
            "id": floorplan.id,
            "restaurant_key": floorplan.restaurant_key,
            "name": floorplan.name,
            "tables": [self._serialize_table(table) for table in tables],
        }

    @staticmethod
    def _serialize_table(table: RestaurantTable) -> dict[str, Any]:
        return {
            "id": table.id,
            "floorplan_id": table.floorplan_id,
            "label": table.label,
            "status": table.status,
            "geometry": {
                "x": table.x,
                "y": table.y,
                "w": table.w,
                "h": table.h,
                "rotation": table.rotation,
            },
            "current_table_group_id": table.current_table_group_id,
            "current_party_id": table.current_party_id,
        }

    def _serialize_party(self, party: Party) -> dict[str, Any]:
        return {
            "id": party.id,
            "restaurant_key": party.restaurant_key,
            "table_group_id": party.table_group_id,
            "primary_table_id": party.primary_table_id,
            "guest_count": party.guest_count,
            "note": party.note,
            "status": party.status,
            "created_at": party.created_at.isoformat(),
            "closed_at": party.closed_at.isoformat() if party.closed_at else None,
        }

    def _serialize_check(self, check: Check) -> dict[str, Any]:
        items = self.db.scalars(
            select(CheckItem)
            .where(CheckItem.current_check_id == check.id)
            .options(selectinload(CheckItem.components))
            .order_by(CheckItem.added_at)
        ).all()
        return {
            "id": check.id,
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
            "receipt_no": check.receipt_no,
            "items": [self._serialize_item(item) for item in items],
        }

    @staticmethod
    def _serialize_item(item: CheckItem) -> dict[str, Any]:
        return {
            "item_id": item.id,
            "product_id": item.product_id,
            "name_snapshot": item.name_snapshot,
            "qty": item.qty,
            "note": item.note,
            "pricing_model": item.pricing_model,
            "voided": item.voided_at is not None,
            "components": [
                {
                    "component_id": component.id,
                    "name_snapshot": component.name_snapshot,
                    "vat_rate_snapshot": component.vat_rate_snapshot,
                    "unit_gross_cents_snapshot": component.unit_gross_cents_snapshot,
                    "qty": component.qty,
                }
                for component in item.components
            ],
        }

    @staticmethod
    def _serialize_session(session_state: SessionState) -> dict[str, Any]:
        return {
            "id": session_state.id,
            "status": session_state.status,
            "opening_cash_cents": session_state.opening_cash_cents,
            "counted_cash_cents": session_state.counted_cash_cents,
            "expected_cash_cents": session_state.expected_cash_cents,
            "diff_cash_cents": session_state.diff_cash_cents,
            "note": session_state.note,
            "opened_at": session_state.opened_at.isoformat(),
            "closed_at": session_state.closed_at.isoformat() if session_state.closed_at else None,
        }

    @staticmethod
    def _now() -> datetime:
        return datetime.now(UTC)
