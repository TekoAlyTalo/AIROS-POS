CREATE TABLE restaurant_config (
  restaurant_key TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  restaurant_timezone TEXT NOT NULL,
  allowed_vat_rates_json JSON NOT NULL,
  next_receipt_no INTEGER NOT NULL
);

CREATE TABLE stream_sequences (
  stream_id TEXT PRIMARY KEY,
  next_seq INTEGER NOT NULL
);

CREATE TABLE floorplans (
  id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL,
  name TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE restaurant_tables (
  id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL,
  floorplan_id TEXT NOT NULL REFERENCES floorplans(id),
  label TEXT NOT NULL,
  x INTEGER NOT NULL,
  y INTEGER NOT NULL,
  w INTEGER NOT NULL,
  h INTEGER NOT NULL,
  rotation INTEGER NOT NULL,
  status TEXT NOT NULL,
  current_table_group_id TEXT,
  current_party_id TEXT
);

CREATE TABLE table_groups (
  id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL,
  primary_table_id TEXT NOT NULL,
  current_party_id TEXT,
  status TEXT NOT NULL,
  open_total_cents INTEGER NOT NULL DEFAULT 0,
  open_checks_count INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  closed_at TEXT
);

CREATE TABLE table_group_members (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  table_group_id TEXT NOT NULL REFERENCES table_groups(id),
  table_id TEXT NOT NULL,
  CONSTRAINT uq_edge_group_table UNIQUE (table_group_id, table_id)
);

CREATE TABLE parties (
  id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL,
  table_group_id TEXT NOT NULL,
  primary_table_id TEXT NOT NULL,
  guest_count INTEGER NOT NULL,
  note TEXT,
  status TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  closed_at TEXT
);

CREATE TABLE checks (
  id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL,
  party_id TEXT NOT NULL,
  table_group_id TEXT NOT NULL,
  currency TEXT NOT NULL,
  label TEXT,
  status TEXT NOT NULL,
  total_cents INTEGER NOT NULL DEFAULT 0,
  subtotal_cents INTEGER NOT NULL DEFAULT 0,
  tax_cents INTEGER NOT NULL DEFAULT 0,
  paid_total_cents INTEGER NOT NULL DEFAULT 0,
  rounding_cents INTEGER NOT NULL DEFAULT 0,
  amount_due_cents INTEGER NOT NULL DEFAULT 0,
  finalized_payment_method TEXT,
  receipt_no INTEGER,
  opened_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finalized_at TEXT
);

CREATE TABLE products (
  id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL DEFAULT 'demo-restaurant',
  external_plu TEXT NOT NULL,
  name TEXT NOT NULL,
  receipt_name TEXT NOT NULL,
  barcode TEXT,
  category TEXT NOT NULL,
  category_id TEXT REFERENCES product_categories(id),
  subcategory_id TEXT REFERENCES product_subcategories(id),
  sort_order INTEGER NOT NULL DEFAULT 0,
  color_code TEXT,
  unit_gross_cents INTEGER NOT NULL,
  vat_rate REAL NOT NULL,
  image_path TEXT,
  is_active INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_products_restaurant_plu UNIQUE (restaurant_key, external_plu)
);

CREATE TABLE product_grid_pages (
  id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL DEFAULT 'demo-restaurant',
  category TEXT NOT NULL,
  category_id TEXT REFERENCES product_categories(id),
  subcategory_id TEXT REFERENCES product_subcategories(id),
  page_number INTEGER NOT NULL DEFAULT 1,
  title TEXT NOT NULL,
  rows INTEGER NOT NULL,
  cols INTEGER NOT NULL,
  sort_order INTEGER NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_product_grid_pages_restaurant_subcategory_page UNIQUE (restaurant_key, subcategory_id, page_number)
);

CREATE TABLE product_categories (
  id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL DEFAULT 'demo-restaurant',
  name TEXT NOT NULL,
  color_code TEXT,
  sort_order INTEGER NOT NULL DEFAULT 0,
  is_active INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_product_categories_restaurant_name UNIQUE (restaurant_key, name)
);

CREATE TABLE product_subcategories (
  id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL DEFAULT 'demo-restaurant',
  category_id TEXT NOT NULL REFERENCES product_categories(id),
  name TEXT NOT NULL,
  sort_order INTEGER NOT NULL DEFAULT 0,
  is_active INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_product_subcategories_restaurant_category_name UNIQUE (restaurant_key, category_id, name)
);

CREATE TABLE product_grid_slots (
  page_id TEXT NOT NULL REFERENCES product_grid_pages(id),
  position INTEGER NOT NULL,
  product_id TEXT REFERENCES products(id),
  label_override TEXT,
  image_override_path TEXT,
  PRIMARY KEY (page_id, position)
);

CREATE TABLE product_allergen_links (
  product_id TEXT NOT NULL REFERENCES products(id),
  allergen_code TEXT NOT NULL,
  PRIMARY KEY (product_id, allergen_code)
);

CREATE TABLE check_items (
  id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL,
  party_id TEXT NOT NULL,
  current_check_id TEXT NOT NULL,
  product_id TEXT,
  name_snapshot TEXT NOT NULL,
  qty INTEGER NOT NULL,
  note TEXT,
  pricing_model TEXT NOT NULL,
  added_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  voided_at TEXT
);

CREATE TABLE check_item_components (
  id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL,
  item_id TEXT NOT NULL REFERENCES check_items(id),
  name_snapshot TEXT NOT NULL,
  vat_rate_snapshot REAL NOT NULL,
  unit_gross_cents_snapshot INTEGER NOT NULL,
  qty INTEGER NOT NULL
);

CREATE TABLE item_voids (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  item_id TEXT NOT NULL UNIQUE,
  reason TEXT NOT NULL,
  occurred_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE payments (
  id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL,
  check_id TEXT NOT NULL,
  session_id TEXT,
  method TEXT NOT NULL,
  amount_cents INTEGER NOT NULL,
  external_ref TEXT,
  occurred_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sessions (
  id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL,
  status TEXT NOT NULL,
  opening_cash_cents INTEGER NOT NULL,
  counted_cash_cents INTEGER,
  expected_cash_cents INTEGER,
  diff_cash_cents INTEGER,
  note TEXT,
  opened_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  closed_at TEXT
);

CREATE TABLE outbox_events (
  event_id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL,
  stream_id TEXT NOT NULL,
  seq INTEGER NOT NULL,
  event_type TEXT NOT NULL,
  occurred_at TEXT NOT NULL,
  event_json JSON NOT NULL,
  delivered_at TEXT,
  CONSTRAINT uq_edge_stream_seq UNIQUE (stream_id, seq)
);
