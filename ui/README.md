# AIROS POS UI

Staff POS web UI for the AIROS Edge service, optimized for Android touch usage.

## Requirements

- Node.js 20+
- Running Edge API at `http://localhost:18000`

## Install

```bash
cd ui
npm install
```

## Dev server

```bash
cd ui
npm run dev
```

The app runs on `http://localhost:5174`.

## Environment

Create a `.env` file in `ui/` if you need to override runtime defaults.

```bash
VITE_EDGE_BASE_URL=http://localhost:18000
VITE_BASE_PATH=/
```

The dev auth token is centralized in `src/lib/api.ts` and defaults to:

```text
Authorization: Bearer dev-staff-token
```

## Backend expectations

- Edge API base URL default: `http://localhost:18000`
- UI uses only existing `/api/staff/pos/*` endpoints
- No Hub endpoints are used by the Staff UI

## Table map behavior

- Geometry comes from `GET /api/staff/pos/floorplans`.
- Live per-table metrics come from `GET /api/staff/pos/tables/overview`.
- Each tile shows:
  - checks count
  - open gross total
  - age since the current party opened
- The map auto-refreshes every 5 seconds and still supports manual refresh.
- Tile coloring is deterministic:
  - `FREE`: neutral
  - `OCCUPIED`: severity by `known_open_total_gross_cents`
    - `0`: neutral
    - `1..3000`: green
    - `3001..10000`: yellow
    - `>10000`: red
  - If `opened_at` is older than 90 minutes, severity bumps one level.
  - `DIRTY`: warning
  - `RESERVED`: danger

## Products workflow

- Sidebar route: `Products`
- Locked selling layout:
  - main category row
  - subcategory row directly below
  - fixed product grid below
  - large page buttons on the right side
- The product page does not scroll internally.
- If a subcategory does not fit on one grid, the UI shows page buttons `1`, `2`, `3`, and so on on the right side.
- If there is no manual slot layout, the Edge service still returns visible paginated products by `sort_order` and then `name`.
- One tap on a product button posts to `POST /api/staff/pos/checks/{check_id}/items` using the product's stored `receipt_name`, `vat_rate`, and `unit_gross_cents`.
- If no active check is selected, the UI shows `Select table first`.

## Product editor and admin mode

- Admin edit mode supports:
  - category create, edit, delete
  - subcategory create, edit, delete
  - product create and edit
  - product image upload
  - allergen editing
  - grid size changes from `2x2` to `8x8`
  - slot assignment for the current subcategory page
  - grid reset back to automatic pagination

- Product editor fields:
  - `external_plu`
  - `name`
  - `receipt_name`
  - `barcode`
  - `category_id`
  - `subcategory_id`
  - `sort_order`
  - `unit_gross_cents`
  - `vat_rate`
  - `color_code`
  - `is_active`
  - `allergens[]`

## Product import

- CSV import endpoint: `POST /api/staff/pos/products:import`
- Multipart field name: `file`
- Import diagnostics returned to the UI:
  - `created`
  - `updated`
  - `skipped`
  - `failed`
  - `failed_rows`
- Local CSV path used by the CLI importer:

```text
C:\AIROS code clean\AIROS POS\data\products.csv
```

- CLI command from the repo root:

```bash
py scripts/import_products_solmio.py
```

## Product images

- Upload endpoint: `POST /api/staff/pos/products/{product_id}/image`
- Images are stored by the Edge service under:

```text
data/static/products/{product_id}.png
```

- The UI reads images from the mounted static route:

```text
/static/products
```

## Allergen support

Supported allergen codes:

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
