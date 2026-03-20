export type TableStatus = "FREE" | "OCCUPIED" | "DIRTY" | "RESERVED";

export interface TableGeometry {
  x: number;
  y: number;
  w: number;
  h: number;
  rotation: number;
}

export interface TableRecord {
  id: string;
  floorplanId: string;
  label: string;
  status: TableStatus;
  geometry: TableGeometry;
  currentTableGroupId: string | null;
  currentPartyId: string | null;
  knownChecksCount: number;
  knownOpenTotalGrossCents: number | null;
  openedAt: string | null;
}

export interface FloorplanRecord {
  id: string;
  restaurantKey: string;
  name: string;
  tables: TableRecord[];
}

export interface TableOverviewRecord {
  tableId: string;
  label: string;
  status: TableStatus;
  currentPartyId: string | null;
  currentTableGroupId: string | null;
  knownChecksCount: number;
  knownOpenTotalGrossCents: number | null;
  openedAt: string | null;
}

export interface PartyRecord {
  id: string;
  restaurantKey: string;
  tableGroupId: string;
  primaryTableId: string;
  guestCount: number;
  note: string | null;
  status: string;
  createdAt: string;
  closedAt: string | null;
}

export interface CheckItemComponentRecord {
  componentId: string | null;
  nameSnapshot: string;
  vatRateSnapshot: number;
  unitGrossCentsSnapshot: number;
  qty: number;
}

export interface CheckItemRecord {
  itemId: string;
  productId: string | null;
  nameSnapshot: string;
  qty: number;
  note: string | null;
  pricingModel: "SINGLE_VAT" | "COMPOSITE_VAT";
  voided: boolean;
  components: CheckItemComponentRecord[];
}

export interface CheckRecord {
  id: string;
  partyId: string;
  tableGroupId: string;
  status: string;
  currency: string;
  label: string | null;
  totalCents: number;
  subtotalCents: number;
  taxCents: number;
  paidTotalCents: number;
  roundingCents: number;
  amountDueCents: number;
  receiptNo: number | null;
  items: CheckItemRecord[];
}

export interface SessionRecord {
  id: string;
  status: string;
  openingCashCents: number;
  countedCashCents: number | null;
  expectedCashCents: number | null;
  diffCashCents: number | null;
  note: string | null;
  openedAt: string;
  closedAt: string | null;
}

export interface ProductRecord {
  id: string;
  restaurantKey: string;
  externalPlu: string;
  name: string;
  receiptName: string;
  barcode: string | null;
  category: string;
  categoryId: string | null;
  categoryName: string;
  subcategoryId: string | null;
  subcategoryName: string | null;
  sortOrder: number;
  colorCode: string | null;
  unitGrossCents: number;
  vatRate: number;
  imagePath: string | null;
  isActive: boolean;
  allergens: string[];
  createdAt: string;
  updatedAt: string;
}

export interface ProductCategoryRecord {
  id: string;
  restaurantKey: string;
  name: string;
  colorCode: string | null;
  sortOrder: number;
  isActive: boolean;
  productCount: number;
  activeProductCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface ProductSubcategoryRecord {
  id: string;
  restaurantKey: string;
  categoryId: string;
  categoryName: string;
  name: string;
  sortOrder: number;
  isActive: boolean;
  productCount: number;
  activeProductCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface ProductGridPageRecord {
  id: string;
  restaurantKey: string;
  category: string;
  categoryId: string;
  categoryName: string;
  categorySortOrder: number;
  subcategoryId: string;
  subcategoryName: string;
  subcategorySortOrder: number;
  title: string;
  pageNumber: number;
  rows: number;
  cols: number;
  sortOrder: number;
  filledSlotsCount: number;
  totalPages: number;
  createdAt: string;
  updatedAt: string;
}

export interface ProductGridSlotRecord {
  pageId: string;
  position: number;
  productId: string | null;
  labelOverride: string | null;
  imageOverridePath: string | null;
  isManual: boolean;
  product: ProductRecord | null;
}

function normalizeTable(table: any): TableRecord {
  return {
    id: String(table.id),
    floorplanId: String(table.floorplan_id ?? ""),
    label: String(table.label ?? "Table"),
    status: (table.status ?? "FREE") as TableStatus,
    geometry: {
      x: Number(table.geometry?.x ?? 0),
      y: Number(table.geometry?.y ?? 0),
      w: Number(table.geometry?.w ?? 100),
      h: Number(table.geometry?.h ?? 80),
      rotation: Number(table.geometry?.rotation ?? 0),
    },
    currentTableGroupId: table.current_table_group_id ?? null,
    currentPartyId: table.current_party_id ?? null,
    knownChecksCount: Number(table.known_checks_count ?? 0),
    knownOpenTotalGrossCents: table.known_open_total_gross_cents ?? null,
    openedAt: table.opened_at ?? null,
  };
}

function extractFloorplanRows(payload: any): any[] {
  if (Array.isArray(payload)) {
    return payload;
  }
  if (Array.isArray(payload?.floorplans)) {
    return payload.floorplans;
  }
  if (Array.isArray(payload?.value)) {
    return payload.value;
  }
  return [];
}

function extractTableRows(payload: any): any[] {
  if (Array.isArray(payload)) {
    return payload;
  }
  if (Array.isArray(payload?.tables)) {
    return payload.tables;
  }
  if (Array.isArray(payload?.value)) {
    return payload.value;
  }
  return [];
}

export function normalizeFloorplans(payload: any): FloorplanRecord[] {
  return extractFloorplanRows(payload).map((floorplan) => ({
    id: String(floorplan.id),
    restaurantKey: String(floorplan.restaurant_key ?? "demo-restaurant"),
    name: String(floorplan.name ?? "Floor"),
    tables: Array.isArray(floorplan.tables) ? floorplan.tables.map(normalizeTable) : [],
  }));
}

export function buildSyntheticFloorplan(tablesPayload: any): FloorplanRecord[] {
  const tables = extractTableRows(tablesPayload).map(normalizeTable);
  return [
    {
      id: "synthetic-floor",
      restaurantKey: "demo-restaurant",
      name: "Table Map",
      tables,
    },
  ];
}

function extractTableOverviewRows(payload: any): any[] {
  if (Array.isArray(payload)) {
    return payload;
  }
  if (Array.isArray(payload?.tables)) {
    return payload.tables;
  }
  if (Array.isArray(payload?.value)) {
    return payload.value;
  }
  return [];
}

export function normalizeTableOverview(payload: any): TableOverviewRecord[] {
  return extractTableOverviewRows(payload).map((table) => ({
    tableId: String(table.table_id),
    label: String(table.label ?? "Table"),
    status: (table.status ?? "FREE") as TableStatus,
    currentPartyId: table.current_party_id ?? null,
    currentTableGroupId: table.current_table_group_id ?? null,
    knownChecksCount: Number(table.known_checks_count ?? 0),
    knownOpenTotalGrossCents: table.known_open_total_gross_cents ?? null,
    openedAt: table.opened_at ?? null,
  }));
}

export function applyTableOverview(
  floorplans: FloorplanRecord[],
  overview: TableOverviewRecord[],
): FloorplanRecord[] {
  const overviewByTableId = new Map(overview.map((table) => [table.tableId, table]));
  return floorplans.map((floorplan) => ({
    ...floorplan,
    tables: floorplan.tables.map((table) => {
      const detail = overviewByTableId.get(table.id);
      if (!detail) {
        return table;
      }
      return {
        ...table,
        label: detail.label,
        status: detail.status,
        currentPartyId: detail.currentPartyId,
        currentTableGroupId: detail.currentTableGroupId,
        knownChecksCount: detail.knownChecksCount,
        knownOpenTotalGrossCents: detail.knownOpenTotalGrossCents,
        openedAt: detail.openedAt,
      };
    }),
  }));
}

export function normalizeParty(payload: any): PartyRecord {
  return {
    id: String(payload.id),
    restaurantKey: String(payload.restaurant_key),
    tableGroupId: String(payload.table_group_id),
    primaryTableId: String(payload.primary_table_id),
    guestCount: Number(payload.guest_count ?? 1),
    note: payload.note ?? null,
    status: String(payload.status ?? "OPEN"),
    createdAt: String(payload.created_at ?? ""),
    closedAt: payload.closed_at ?? null,
  };
}

export function normalizeCheck(payload: any): CheckRecord {
  return {
    id: String(payload.id),
    partyId: String(payload.party_id),
    tableGroupId: String(payload.table_group_id),
    status: String(payload.status ?? "OPEN"),
    currency: String(payload.currency ?? "EUR"),
    label: payload.label ?? null,
    totalCents: Number(payload.total_cents ?? 0),
    subtotalCents: Number(payload.subtotal_cents ?? 0),
    taxCents: Number(payload.tax_cents ?? 0),
    paidTotalCents: Number(payload.paid_total_cents ?? 0),
    roundingCents: Number(payload.rounding_cents ?? 0),
    amountDueCents: Number(payload.amount_due_cents ?? 0),
    receiptNo: payload.receipt_no ?? null,
    items: Array.isArray(payload.items)
      ? payload.items.map((item: any) => ({
          itemId: String(item.item_id),
          productId: item.product_id ?? null,
          nameSnapshot: String(item.name_snapshot),
          qty: Number(item.qty ?? 1),
          note: item.note ?? null,
          pricingModel: item.pricing_model,
          voided: Boolean(item.voided),
          components: Array.isArray(item.components)
            ? item.components.map((component: any) => ({
                componentId: component.component_id ?? null,
                nameSnapshot: String(component.name_snapshot),
                vatRateSnapshot: Number(component.vat_rate_snapshot ?? 0),
                unitGrossCentsSnapshot: Number(component.unit_gross_cents_snapshot ?? 0),
                qty: Number(component.qty ?? 1),
              }))
            : [],
        }))
      : [],
  };
}

export function normalizeSession(payload: any): SessionRecord | null {
  if (!payload) {
    return null;
  }
  return {
    id: String(payload.id),
    status: String(payload.status ?? "OPEN"),
    openingCashCents: Number(payload.opening_cash_cents ?? 0),
    countedCashCents: payload.counted_cash_cents ?? null,
    expectedCashCents: payload.expected_cash_cents ?? null,
    diffCashCents: payload.diff_cash_cents ?? null,
    note: payload.note ?? null,
    openedAt: String(payload.opened_at ?? ""),
    closedAt: payload.closed_at ?? null,
  };
}

export function normalizeProduct(payload: any): ProductRecord {
  return {
    id: String(payload.id),
    restaurantKey: String(payload.restaurant_key ?? "demo-restaurant"),
    externalPlu: String(payload.external_plu ?? ""),
    name: String(payload.name ?? ""),
    receiptName: String(payload.receipt_name ?? ""),
    barcode: payload.barcode ?? null,
    category: String(payload.category ?? "Uncategorized"),
    categoryId: payload.category_id ?? null,
    categoryName: String(payload.category_name ?? payload.category ?? "Uncategorized"),
    subcategoryId: payload.subcategory_id ?? null,
    subcategoryName: payload.subcategory_name ?? null,
    sortOrder: Number(payload.sort_order ?? 0),
    colorCode: payload.color_code ?? null,
    unitGrossCents: Number(payload.unit_gross_cents ?? 0),
    vatRate: Number(payload.vat_rate ?? 0),
    imagePath: payload.image_path ?? null,
    isActive: Boolean(payload.is_active ?? true),
    allergens: Array.isArray(payload.allergens) ? payload.allergens.map(String) : [],
    createdAt: String(payload.created_at ?? ""),
    updatedAt: String(payload.updated_at ?? ""),
  };
}

export function normalizeProductCategory(payload: any): ProductCategoryRecord {
  return {
    id: String(payload.id ?? ""),
    restaurantKey: String(payload.restaurant_key ?? "demo-restaurant"),
    name: String(payload.name ?? ""),
    colorCode: payload.color_code ?? null,
    sortOrder: Number(payload.sort_order ?? 0),
    isActive: Boolean(payload.is_active ?? true),
    productCount: Number(payload.product_count ?? 0),
    activeProductCount: Number(payload.active_product_count ?? 0),
    createdAt: String(payload.created_at ?? ""),
    updatedAt: String(payload.updated_at ?? ""),
  };
}

export function normalizeProductSubcategory(payload: any): ProductSubcategoryRecord {
  return {
    id: String(payload.id ?? ""),
    restaurantKey: String(payload.restaurant_key ?? "demo-restaurant"),
    categoryId: String(payload.category_id ?? ""),
    categoryName: String(payload.category_name ?? ""),
    name: String(payload.name ?? ""),
    sortOrder: Number(payload.sort_order ?? 0),
    isActive: Boolean(payload.is_active ?? true),
    productCount: Number(payload.product_count ?? 0),
    activeProductCount: Number(payload.active_product_count ?? 0),
    createdAt: String(payload.created_at ?? ""),
    updatedAt: String(payload.updated_at ?? ""),
  };
}

export function normalizeGridPage(payload: any): ProductGridPageRecord {
  return {
    id: String(payload.id),
    restaurantKey: String(payload.restaurant_key ?? "demo-restaurant"),
    category: String(payload.category ?? "Uncategorized"),
    categoryId: String(payload.category_id ?? ""),
    categoryName: String(payload.category_name ?? payload.category ?? "Uncategorized"),
    categorySortOrder: Number(payload.category_sort_order ?? 0),
    subcategoryId: String(payload.subcategory_id ?? ""),
    subcategoryName: String(payload.subcategory_name ?? payload.title ?? ""),
    subcategorySortOrder: Number(payload.subcategory_sort_order ?? 0),
    title: String(payload.title ?? ""),
    pageNumber: Number(payload.page_number ?? 1),
    rows: Number(payload.rows ?? 4),
    cols: Number(payload.cols ?? 4),
    sortOrder: Number(payload.sort_order ?? 0),
    filledSlotsCount: Number(payload.filled_slots_count ?? 0),
    totalPages: Number(payload.total_pages ?? 1),
    createdAt: String(payload.created_at ?? ""),
    updatedAt: String(payload.updated_at ?? ""),
  };
}

export function normalizeGridSlot(payload: any): ProductGridSlotRecord {
  return {
    pageId: String(payload.page_id ?? ""),
    position: Number(payload.position ?? 0),
    productId: payload.product_id ?? null,
    labelOverride: payload.label_override ?? null,
    imageOverridePath: payload.image_override_path ?? null,
    isManual: Boolean(payload.is_manual ?? false),
    product: payload.product ? normalizeProduct(payload.product) : null,
  };
}
