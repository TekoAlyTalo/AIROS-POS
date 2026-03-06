# AIROS POS

Edge-first restaurant POS built to the contracts in `AIROS_POS_MASTER.md`, `invariants.md`, `totals.md`, `materializer.md`, and `shared/schemas/airos.pos.sync.event.v1.schema.json`.

## Repository layout

- `edge/`: FastAPI Edge POS write service backed by SQLite
- `hub/`: FastAPI Hub ingest and dashboard service backed by Postgres by default, SQLite for local dev
- `shared/`: JSON Schema validation, totals/VAT calculator, money and UUID helpers
- `tests/`: totals, split/merge, and hub ingestion contract tests
- `db/ddl/`: schema snapshots for Edge and Hub

## Install

```bash
py -m pip install -e .[test]
```

For Hub Postgres support:

```bash
py -m pip install -e .[test,postgres]
```

## Run the services

Edge defaults to SQLite at `./data/edge.db`.

```bash
py -m uvicorn edge.app:create_app --factory --reload --port 18000
```

Hub defaults to Postgres. For local SQLite fallback:

```bash
set AIROS_HUB_DATABASE_URL=sqlite:///./data/hub.db
py -m uvicorn hub.app:create_app --factory --reload --port 8010
```

Dev bearer tokens:

- `dev-staff-token`
- `dev-manager-token`
- `dev-owner-token`

## Run the Staff UI

The React Staff POS UI lives under `ui/` and talks directly to the Edge API.

```bash
cd ui
npm install
npm run dev
```

Default frontend settings:

- `VITE_EDGE_BASE_URL=http://localhost:18000`
- dev auth header: `Authorization: Bearer dev-staff-token`
- `VITE_BASE_PATH=/`
- UI dev server: `http://localhost:5174`
- table map auto-refresh: every 5 seconds via `/api/staff/pos/tables/overview`

## Product catalog and grid

- The selling flow is fixed:
  - main category row
  - subcategory row
  - fixed product grid
  - page buttons on the right side
- Product pages do not scroll internally. If a subcategory has more products than fit, the Edge service auto-creates additional pages and the UI shows page buttons `1`, `2`, `3`, and so on.
- If no manual slot layout exists, products are still visible. The Edge service auto-paginates by `sort_order` and then `name`.
- Admin edit mode supports:
  - category create/edit/delete
  - subcategory create/edit/delete
  - product create/edit
  - product image upload
  - allergen editing
  - grid size changes from `2x2` up to `8x8`
  - slot assignment and grid reset

## Product import

- Default CSV path for the CLI importer:

```text
C:\AIROS code clean\AIROS POS\data\products.csv
```

- CLI import:

```bash
py scripts/import_products_solmio.py
```

- API import endpoint:

```text
POST /api/staff/pos/products:import
```

- Import diagnostics always return:
  - `created`
  - `updated`
  - `skipped`
  - `failed`
  - `failed_rows`

- Rows are not silently dropped. Missing taxonomy falls back to editable category and subcategory records, while unsupported VAT rows are reported in `failed_rows`.

## Product images and allergens

- Product images upload to `POST /api/staff/pos/products/{product_id}/image`
- Images are stored under `data/static/products/{product_id}.png` and served from `/static/products`
- Allergen metadata is stored through `PUT /api/staff/pos/products/{product_id}/allergens`
- Supported allergen codes:
  - `gluten`
  - `milk`
  - `egg`
  - `nuts`
  - `peanuts`
  - `fish`
  - `shellfish`
  - `soy`
  - `celery`
  - `mustard`
  - `sesame`
  - `lupin`
  - `sulphites`
  - `molluscs`

## Edge curl flow

List seeded floorplan and tables:

```bash
curl -H "Authorization: Bearer dev-staff-token" http://localhost:18000/api/staff/pos/floorplans
curl -H "Authorization: Bearer dev-staff-token" http://localhost:18000/api/staff/pos/tables
curl -H "Authorization: Bearer dev-staff-token" http://localhost:18000/api/staff/pos/tables/overview
```

`/api/staff/pos/tables/overview` returns:

- `table_id`
- `label`
- `status`
- `current_party_id`
- `current_table_group_id`
- `known_checks_count`
- `known_open_total_gross_cents`
- `opened_at`

Open a session:

```bash
curl -X POST http://localhost:18000/api/staff/pos/sessions/open ^
  -H "Authorization: Bearer dev-staff-token" ^
  -H "Content-Type: application/json" ^
  -d "{\"opening_cash_cents\":5000}"
```

Open and seat a party. Replace table IDs with values from `/api/staff/pos/tables`.

```bash
curl -X POST http://localhost:18000/api/staff/pos/parties/open ^
  -H "Authorization: Bearer dev-staff-token" ^
  -H "Content-Type: application/json" ^
  -d "{\"guest_count\":2,\"table_ids\":[\"TABLE_UUID\"],\"primary_table_id\":\"TABLE_UUID\",\"note\":\"Window seat\"}"
```

Open a check for the party:

```bash
curl -X POST http://localhost:18000/api/staff/pos/parties/PARTY_UUID/checks/open ^
  -H "Authorization: Bearer dev-staff-token" ^
  -H "Content-Type: application/json" ^
  -d "{\"label\":\"Main\"}"
```

Add a composite VAT cocktail:

```bash
curl -X POST http://localhost:18000/api/staff/pos/checks/CHECK_UUID/items ^
  -H "Authorization: Bearer dev-staff-token" ^
  -H "Content-Type: application/json" ^
  -d "{\"items\":[{\"name_snapshot\":\"House Cocktail\",\"qty\":1,\"pricing_model\":\"COMPOSITE_VAT\",\"components\":[{\"name_snapshot\":\"Spirits\",\"vat_rate_snapshot\":0.255,\"unit_gross_cents_snapshot\":900,\"qty\":1},{\"name_snapshot\":\"Mixer\",\"vat_rate_snapshot\":0.14,\"unit_gross_cents_snapshot\":300,\"qty\":1}]}]}"
```

Record payment and finalize:

```bash
curl -X POST http://localhost:18000/api/staff/pos/checks/CHECK_UUID/payments ^
  -H "Authorization: Bearer dev-staff-token" ^
  -H "Content-Type: application/json" ^
  -d "{\"method\":\"CARD_EXTERNAL\",\"amount_cents\":1200}"

curl -X POST http://localhost:18000/api/staff/pos/checks/CHECK_UUID/finalize ^
  -H "Authorization: Bearer dev-staff-token" ^
  -H "Content-Type: application/json" ^
  -d "{\"payment_method\":\"CARD_EXTERNAL\",\"issue_receipt\":true}"
```

Read pending outbox events:

```bash
curl -H "Authorization: Bearer dev-staff-token" http://localhost:18000/api/staff/pos/outbox/pending
```

Mark pushed events delivered:

```bash
curl -X POST http://localhost:18000/api/staff/pos/outbox/mark-delivered ^
  -H "Authorization: Bearer dev-staff-token" ^
  -H "Content-Type: application/json" ^
  -d "{\"event_ids\":[\"EVENT_UUID\"]}"
```

## Hub curl flow

Push Edge outbox events:

```bash
curl -X POST http://localhost:8010/api/sync/pos/events:push ^
  -H "Authorization: Bearer dev-owner-token" ^
  -H "Content-Type: application/json" ^
  -d "{\"events\":[PASTE_OUTBOX_EVENT_OBJECTS_HERE]}"
```

Fetch the live table map:

```bash
curl -H "Authorization: Bearer dev-owner-token" http://localhost:8010/api/dashboard/pos/restaurants/demo-restaurant/table-map
```

Fetch daily sales and VAT reports:

```bash
curl -H "Authorization: Bearer dev-owner-token" "http://localhost:8010/api/dashboard/pos/restaurants/demo-restaurant/reports/daily?day=2026-03-05"
curl -H "Authorization: Bearer dev-owner-token" "http://localhost:8010/api/dashboard/pos/restaurants/demo-restaurant/reports/vat?day=2026-03-05"
```

Fetch a multi-restaurant daily report:

```bash
curl -H "Authorization: Bearer dev-owner-token" "http://localhost:8010/api/dashboard/pos/reports/daily?day=2026-03-05&restaurant_key=demo-restaurant&restaurant_key=second-restaurant"
```

Resolve a quarantined stream row after manual correction:

```bash
curl -X POST -H "Authorization: Bearer dev-owner-token" http://localhost:8010/api/sync/pos/quarantine/1:resolve
```

## Notes

- All money is integer cents.
- Composite VAT component pricing is explicit only; the services never infer component pricing.
- `occurred_at` is authoritative for reporting, and day bucketing uses `Europe/Helsinki` unless the restaurant registry is configured differently.
- Hub applies events exactly once by `event_id`, enforces ordered `stream_id` plus `seq`, and blocks a stream on schema or money integrity violations.

## Test command

```bash
py -m pytest -q
```
