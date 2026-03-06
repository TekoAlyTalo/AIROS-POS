# AIROS POS Master Specification (v1)

## Goal
Build a modular, Edge-first restaurant POS system that integrates with AIROS via an event-driven sync pipeline.
POS is not AI-driven; it must be deterministic, auditable, and safe for accounting.

## Defaults (locked)
- Auth/Roles: `staff`, `manager`, `owner` (dev-mode static token allowed, structure must be production-ready).
- Cash rounding: CASH rounds to nearest 5 cents (0–2 down, 3–4 up); CARD_EXTERNAL has no rounding.
- Receipt numbering: continuous per `restaurant_key`.
- Allowed VAT rates (default): `[0.14, 0.255]` (configurable per restaurant).
- Reporting time: `occurred_at` is authoritative; day bucketing uses `restaurant_timezone` (default `Europe/Helsinki`).
- UI scope: minimal staff UI optional; APIs + correctness are primary.

## Non-goals (v1)
- No bank card integration (only recording external terminal payments).
- No AI decision-making in write paths.
- No heavy backoffice UI (owner is read-only reports).

## Architecture
### Edge (Staff Ops)
- FastAPI service with endpoints under `/api/staff/pos/*`
- SQLite storage (migration-ready for Postgres later)
- Event outbox emits POS Sync Events to Hub

### Hub (Read-only + materializer)
- Ingest endpoint: `/api/sync/pos/events:push`
- Exactly-once semantics with per-stream cursor
- Materialized read models + aggregates
- Owner endpoints: `/api/dashboard/pos/*` (read-only, multi-restaurant)

## Tenancy & access
- All data and events are scoped by `restaurant_key`.
- Owner can access multiple restaurant keys; staff usually has a subset.
- Restaurant timezone default: `Europe/Helsinki` (used for day bucketing).

## Core Concepts
### Floorplan & tables
- Floorplans contain tables with geometry: x,y,w,h,rotation
- Tables have status: FREE, OCCUPIED, DIRTY, RESERVED

### TableGroup (mandatory)
- Party always sits in a TableGroup.
- TableGroup can be 1 table.
- A NEW TableGroup is created every time a party is seated.

### Parties, checks, items
- Party represents a dining group; checks are per party.
- Checks can be split/merged by moving item assignments.

### Payments
- CASH and CARD_EXTERNAL only.
- CARD_EXTERNAL is recorded after an external terminal payment.
- Payments do not affect totals calculation; they affect paid_total and finalization gate.

## Pricing & VAT (critical)
- All snapshot unit prices are GROSS (VAT included).
- Composite VAT items are supported with explicit VAT components.
- Totals are derived in Hub using Strategy 1 (facts + assignment + void).
- Rounding occurs only at payment/finalization stage and never affects VAT reporting.

## POS Sync Events
- Envelope includes: tenant, source, refs, occurred_at, payload
- Exactly-once apply in Hub (event_id dedupe, stream_id+seq order)
- Quarantine invalid money events; stop stream until resolved
- Use occurred_at as reporting truth

## Edge API (summary)
- Floorplans: list/get
- Tables: list status, update status (optional)
- Parties: open, seat, move, close
- Checks: open, get, add items, remove items, split, merge, void
- Payments: record cash/card_external
- Sessions: open/close shift with cash control

## Hub read models & reports
- Live table map per restaurant
- Party and check state
- Daily sales report:
  - gross, net, tax
  - payment method totals
  - counts (paid/voided)
- VAT report by day and vat_rate
- Session report with cash control

## UI (AIROS-aligned)
- Minimal staff UI optional: table map + check view + payment modal
- Visual language: clean, card-based layout, consistent spacing, AIROS-like palette/theme
- Avoid POS clutter; prefer progressive disclosure:
  - table map -> party -> check -> items -> finalize
- Deterministic UI components; no AI-generated UI.

## Acceptance tests
- Composite cocktail VAT math matches golden vector
- Split/merge updates assignments and recomputes totals correctly
- Void removes item from totals but preserves history
- Finalize locks-in aggs by occurred_at day (restaurant timezone)
- Hub dedup works and stream gap handling is correct
- Quarantine stops stream on money/schema violations
