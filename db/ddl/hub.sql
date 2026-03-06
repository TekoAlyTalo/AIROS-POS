CREATE TABLE restaurant_registry (
  restaurant_key TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  restaurant_timezone TEXT NOT NULL
);

CREATE TABLE stream_cursors (
  stream_id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL,
  last_seq INTEGER NOT NULL DEFAULT 0,
  blocked BOOLEAN NOT NULL DEFAULT FALSE,
  blocked_reason TEXT,
  blocked_at TIMESTAMPTZ
);

CREATE TABLE applied_events (
  event_id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL,
  stream_id TEXT NOT NULL,
  seq INTEGER NOT NULL,
  event_type TEXT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  event_json JSONB NOT NULL,
  applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_hub_stream_seq UNIQUE (stream_id, seq)
);

CREATE TABLE quarantine_events (
  id BIGSERIAL PRIMARY KEY,
  event_id TEXT,
  restaurant_key TEXT,
  stream_id TEXT NOT NULL,
  seq INTEGER,
  reason TEXT NOT NULL,
  details_json JSONB NOT NULL,
  event_json JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  resolved_at TIMESTAMPTZ
);

CREATE TABLE table_group_state (
  table_group_id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL,
  primary_table_id TEXT NOT NULL,
  current_party_id TEXT,
  status TEXT NOT NULL,
  open_total_cents INTEGER NOT NULL DEFAULT 0,
  open_checks_count INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ,
  closed_at TIMESTAMPTZ
);

CREATE TABLE table_group_members (
  id BIGSERIAL PRIMARY KEY,
  table_group_id TEXT NOT NULL REFERENCES table_group_state(table_group_id),
  table_id TEXT NOT NULL,
  CONSTRAINT uq_hub_group_table UNIQUE (table_group_id, table_id)
);

CREATE TABLE table_state (
  table_id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL,
  current_table_group_id TEXT,
  current_party_id TEXT,
  status TEXT NOT NULL,
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE party_state (
  party_id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL,
  table_group_id TEXT NOT NULL,
  primary_table_id TEXT NOT NULL,
  guest_count INTEGER NOT NULL,
  note TEXT,
  status TEXT NOT NULL,
  opened_at TIMESTAMPTZ NOT NULL,
  closed_at TIMESTAMPTZ
);

CREATE TABLE check_state (
  check_id TEXT PRIMARY KEY,
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
  session_id TEXT,
  opened_at TIMESTAMPTZ NOT NULL,
  finalized_at TIMESTAMPTZ
);

CREATE TABLE item_fact (
  item_id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL,
  party_id TEXT NOT NULL,
  name_snapshot TEXT NOT NULL,
  qty INTEGER NOT NULL,
  pricing_model TEXT NOT NULL,
  note TEXT,
  added_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE item_component_fact (
  component_id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL,
  item_id TEXT NOT NULL,
  name_snapshot TEXT NOT NULL,
  vat_rate TEXT NOT NULL,
  unit_gross_cents_snapshot INTEGER NOT NULL,
  qty INTEGER NOT NULL,
  gross_cents INTEGER NOT NULL,
  net_cents INTEGER NOT NULL,
  tax_cents INTEGER NOT NULL
);

CREATE TABLE item_assignment (
  item_id TEXT PRIMARY KEY,
  current_check_id TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE item_void (
  item_id TEXT PRIMARY KEY,
  reason TEXT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE payment_fact (
  payment_id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL,
  check_id TEXT NOT NULL,
  session_id TEXT,
  method TEXT NOT NULL,
  amount_cents INTEGER NOT NULL,
  external_ref TEXT,
  occurred_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE receipt_fact (
  receipt_id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL,
  check_id TEXT NOT NULL,
  receipt_no INTEGER NOT NULL,
  issued_at TIMESTAMPTZ NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_hub_receipt_no UNIQUE (restaurant_key, receipt_no)
);

CREATE TABLE session_state (
  session_id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL,
  status TEXT NOT NULL,
  opening_cash_cents INTEGER NOT NULL,
  counted_cash_cents INTEGER,
  expected_cash_cents INTEGER,
  diff_cash_cents INTEGER,
  note TEXT,
  opened_at TIMESTAMPTZ NOT NULL,
  closed_at TIMESTAMPTZ
);

CREATE TABLE daily_sales_agg (
  restaurant_key TEXT NOT NULL,
  bucket_day DATE NOT NULL,
  gross_cents INTEGER NOT NULL DEFAULT 0,
  net_cents INTEGER NOT NULL DEFAULT 0,
  tax_cents INTEGER NOT NULL DEFAULT 0,
  rounding_cents INTEGER NOT NULL DEFAULT 0,
  amount_due_cents INTEGER NOT NULL DEFAULT 0,
  cash_cents INTEGER NOT NULL DEFAULT 0,
  card_external_cents INTEGER NOT NULL DEFAULT 0,
  paid_checks_count INTEGER NOT NULL DEFAULT 0,
  voided_checks_count INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (restaurant_key, bucket_day)
);

CREATE TABLE daily_vat_agg (
  restaurant_key TEXT NOT NULL,
  bucket_day DATE NOT NULL,
  vat_rate TEXT NOT NULL,
  gross_cents INTEGER NOT NULL DEFAULT 0,
  net_cents INTEGER NOT NULL DEFAULT 0,
  tax_cents INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (restaurant_key, bucket_day, vat_rate)
);

CREATE TABLE session_sales_agg (
  session_id TEXT PRIMARY KEY,
  restaurant_key TEXT NOT NULL,
  gross_cents INTEGER NOT NULL DEFAULT 0,
  net_cents INTEGER NOT NULL DEFAULT 0,
  tax_cents INTEGER NOT NULL DEFAULT 0,
  cash_cents INTEGER NOT NULL DEFAULT 0,
  card_external_cents INTEGER NOT NULL DEFAULT 0,
  paid_checks_count INTEGER NOT NULL DEFAULT 0
);
