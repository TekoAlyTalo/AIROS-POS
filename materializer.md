# AIROS POS Hub Materializer (v1)

## Goal
Consume POS Sync Events from Edge, apply exactly-once semantics, and materialize:
- Live table map (read-only)
- Party/check state
- Fact tables for items/components/payments/receipts
- Daily and session aggregates (locked-in on finalize)

## Exactly-once semantics
- Deduplicate by `event_id`.
- Maintain cursor per `stream_id` with monotonically increasing `seq`.
- Process events in `seq` order per stream.
- If a stream has a gap, do not ack beyond the gap.

## Quarantine policy (money safety)
If an event violates schema or money invariants (e.g. composite rules), quarantine it and STOP that stream until resolved.

## Strategy 1 totals
Totals are derived from:
- item_component_fact
- item_assignment
- item_void (exclude voided items)
Group by `current_check_id`.

## Event -> apply mapping (high level)
### TableGroup
- created: upsert group state, insert members, link tables
- tables_added/removed: update membership + links; if primary removed select deterministic new primary
- primary_table_set: update primary
- closed: clear current_party/open totals, mark FREE or DIRTY

### Party
- opened: upsert OPEN
- seated: set party.group; set group current_party; mark OCCUPIED
- moved: move party to new group; clear old current_party; set new current_party
- closed: mark party CLOSED; clear group current_party; mark DIRTY by default

### Check & items
- check.opened: create OPEN check; increment group.open_checks_count
- items_added: insert item header + components; upsert assignment; recompute totals for check
- items_removed: insert item_void; recompute totals
- split: move assignments; recompute source + target
- merged: move assignments; recompute target + sources
- voided: mark check VOID and remove from open totals

### Payments & finalize
- payment.recorded: insert payment fact; update paid_total
- check.finalized: mark PAID; store rounding/amount_due; lock-in day/session/vat aggs by occurred_at; decrement open_checks_count; recompute group open_total

### Receipt
- receipt.issued: insert receipt fact (unique receipt_no per restaurant)

## Recompute algorithm (one check)
Active items:
- item_assignment where current_check_id = check_id
- exclude item_void items

Components:
- join item_component_fact by item_id

Totals:
- total_cents = sum(component_gross)
- subtotal_cents = sum(round(component_gross/(1+vat_rate)))
- tax_cents = total - subtotal
Update check_state.

Update table_group open_total:
- sum totals for OPEN checks in that group (party->group).

## Aggregation lock-in on finalize
- Day bucket is based on occurred_at in restaurant timezone.
- VAT agg uses component-level calculation and is not affected by rounding.
