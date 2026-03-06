# AIROS POS Invariants (v1)

## Tenancy & identity
- Every record MUST be scoped by `restaurant_key`.
- Owner can have multiple restaurants; Hub dashboard must support multi-restaurant reads.

## Table & grouping
- `table_id` is stable and globally unique.
- Party ALWAYS references a `table_group_id`.
- TableGroup is allowed with 1 table.
- A NEW `table_group_id` is created on EACH party seating (no reuse).
- TableGroup membership can change via join/unjoin (tables_added/tables_removed).
- `primary_table_id` MUST always be a member of the group.

## Occupancy constraints
- A party may be seated in exactly ONE table group at a time.
- A table group may have at most ONE current_party_id at a time.

## Checks
- A check belongs to exactly one party_id.
- An item_id belongs to exactly one current_check_id at a time (via assignment).
- Splitting checks moves item assignments; facts remain immutable.

## Money safety
- All amounts are integer cents.
- Unit prices in snapshots are GROSS (VAT included).
- Composite VAT items MUST have explicit component amounts.
- The system MUST NOT infer component pricing from totals.
- VAT calculation is deterministic per component (see totals.md).

## Rounding
- Rounding happens only at finalization/payment stage.
- Rounding MUST NOT affect VAT reporting totals.

## Sync & integrity
- Hub applies events exactly-once via event_id dedupe.
- Hub enforces ordered seq per stream_id; gaps stop ack beyond the gap.
- Invalid money/schema events go to quarantine and STOP the stream until resolved.
- Reporting uses occurred_at as authoritative time.
