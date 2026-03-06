# AIROS Boundaries Reference (POS v1)

This file anchors the POS project to AIROS architectural boundaries and integration conventions.

## Domain boundaries
- POS is **Staff Ops** (write-heavy, low latency).
- Owner dashboard is **read-only** (Hub materialized views).
- No AI/LLM calls from POS write path.

## Tenancy
- Everything is scoped by `restaurant_key`.
- Owner can access multiple restaurants; staff typically a subset.

## Edge vs Hub authority
- Edge is authoritative for staff writes.
- Hub is authoritative for read models and portfolio aggregation.

## Sync model
- Event-driven: Edge emits immutable events; Hub materializes state.
- Exactly-once apply: dedupe by event_id and stream cursor by (stream_id, seq).
- Money safety: quarantine invalid money/schema events and stop stream.

## Integrations
- Vision and other signals correlate via `table_id` (stable, globally unique).
- POS emits table_group membership so Hub can project signals to groups.

## Safety requirements
- Composite VAT component pricing must be explicit (never inferred).
- Occurred_at is the authoritative time; day bucketing uses restaurant timezone.
