import {
  applyTableOverview,
  normalizeGridPage,
  normalizeGridSlot,
  buildSyntheticFloorplan,
  normalizeCheck,
  normalizeFloorplans,
  normalizeParty,
  normalizeProductCategory,
  normalizeProduct,
  normalizeProductSubcategory,
  normalizeSession,
  normalizeTableOverview,
  type CheckRecord,
  type FloorplanRecord,
  type PartyRecord,
  type ProductCategoryRecord,
  type ProductGridPageRecord,
  type ProductGridSlotRecord,
  type ProductRecord,
  type ProductSubcategoryRecord,
  type SessionRecord,
  type TableOverviewRecord,
} from "@/lib/normalize";

export const EDGE_BASE_URL = import.meta.env.VITE_EDGE_BASE_URL ?? "http://localhost:18000";
export const DEV_AUTH_TOKEN = "Bearer dev-staff-token";

export class ApiError extends Error {
  status: number;
  detail: string;

  constructor(status: number, detail: string) {
    super(detail);
    this.status = status;
    this.detail = detail;
  }
}

type RequestOptions = RequestInit & {
  query?: Record<string, string | number | boolean | null | undefined>;
};

function buildUrl(path: string, query?: RequestOptions["query"]): string {
  const url = new URL(`${EDGE_BASE_URL}${path}`);
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value === undefined || value === null || value === "") {
      continue;
    }
    url.searchParams.set(key, String(value));
  }
  return url.toString();
}

function buildHeaders(init?: RequestOptions): HeadersInit {
  const headers = new Headers(init?.headers ?? {});
  headers.set("Authorization", DEV_AUTH_TOKEN);
  if (!(init?.body instanceof FormData) && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  return headers;
}

async function request<T>(path: string, init?: RequestOptions): Promise<T> {
  const { query, ...rest } = init ?? {};
  const response = await fetch(buildUrl(path, query), {
    ...rest,
    headers: buildHeaders(init),
  });

  const text = await response.text();
  const data = text ? JSON.parse(text) : null;

  if (!response.ok) {
    const detail =
      typeof data?.detail === "string"
        ? data.detail
        : Array.isArray(data?.detail)
          ? data.detail.map((item: any) => item.msg).join(", ")
          : response.statusText;
    throw new ApiError(response.status, detail);
  }

  return data as T;
}

export async function getFloorplansOrTables(): Promise<FloorplanRecord[]> {
  try {
    const floorplans = await request<any>("/api/staff/pos/floorplans");
    const normalized = normalizeFloorplans(floorplans);
    if (normalized.length > 0) {
      return normalized;
    }
  } catch {
    // Fall through to tables endpoint.
  }

  const tables = await request<any>("/api/staff/pos/tables");
  return buildSyntheticFloorplan(tables);
}

export async function getTableOverview(): Promise<TableOverviewRecord[]> {
  const payload = await request<any>("/api/staff/pos/tables/overview");
  return normalizeTableOverview(payload);
}

export function applyOverviewToFloorplans(
  floorplans: FloorplanRecord[],
  overview: TableOverviewRecord[],
): FloorplanRecord[] {
  return applyTableOverview(floorplans, overview);
}

export async function getCurrentSession(): Promise<SessionRecord | null> {
  try {
    const payload = await request<any | null>("/api/staff/pos/sessions/current");
    return normalizeSession(payload);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return null;
    }
    throw error;
  }
}

export async function getParty(partyId: string): Promise<PartyRecord> {
  const payload = await request<any>(`/api/staff/pos/parties/${partyId}`);
  return normalizeParty(payload);
}

export async function listPartyChecks(partyId: string): Promise<CheckRecord[]> {
  const payload = await request<any[]>(`/api/staff/pos/parties/${partyId}/checks`);
  return payload.map(normalizeCheck);
}

export async function openParty(input: {
  tableId: string;
  guestCount: number;
  note?: string;
}): Promise<PartyRecord> {
  const payload = await request<any>("/api/staff/pos/parties/open", {
    method: "POST",
    body: JSON.stringify({
      guest_count: input.guestCount,
      table_ids: [input.tableId],
      primary_table_id: input.tableId,
      note: input.note || undefined,
    }),
  });
  return normalizeParty(payload);
}

export async function openCheck(partyId: string): Promise<CheckRecord> {
  const payload = await request<any>(`/api/staff/pos/parties/${partyId}/checks/open`, {
    method: "POST",
    body: JSON.stringify({}),
  });
  return normalizeCheck(payload);
}

export async function getCheck(checkId: string): Promise<CheckRecord> {
  const payload = await request<any>(`/api/staff/pos/checks/${checkId}`);
  return normalizeCheck(payload);
}

export async function addItems(
  checkId: string,
  body: {
    items: Array<{
      product_id?: string;
      name_snapshot: string;
      qty: number;
      pricing_model: "SINGLE_VAT" | "COMPOSITE_VAT";
      note?: string;
      components: Array<{
        name_snapshot: string;
        vat_rate_snapshot: number;
        unit_gross_cents_snapshot: number;
        qty: number;
      }>;
    }>;
  },
): Promise<CheckRecord> {
  const payload = await request<any>(`/api/staff/pos/checks/${checkId}/items`, {
    method: "POST",
    body: JSON.stringify(body),
  });
  return normalizeCheck(payload);
}

export async function listProducts(input?: {
  category_id?: string;
  subcategory_id?: string;
  category?: string;
  q?: string;
  active?: boolean;
}): Promise<ProductRecord[]> {
  const payload = await request<any[]>("/api/staff/pos/products", {
    query: {
      category_id: input?.category_id,
      subcategory_id: input?.subcategory_id,
      category: input?.category,
      q: input?.q,
      active: input?.active,
    },
  });
  return payload.map(normalizeProduct);
}

export async function createProduct(body: {
  external_plu: string;
  name: string;
  receipt_name: string;
  barcode?: string | null;
  category?: string | null;
  category_id?: string | null;
  subcategory_id?: string | null;
  sort_order?: number | null;
  color_code?: string | null;
  unit_gross_cents: number;
  vat_rate: number;
  is_active?: boolean;
  allergens?: string[];
}): Promise<ProductRecord> {
  const payload = await request<any>("/api/staff/pos/products", {
    method: "POST",
    body: JSON.stringify(body),
  });
  return normalizeProduct(payload);
}

export async function listProductGroups(): Promise<string[]> {
  return request<string[]>("/api/staff/pos/product-groups");
}

export async function listProductCategories(input?: { active?: boolean }): Promise<ProductCategoryRecord[]> {
  const payload = await request<any[]>("/api/staff/pos/product-categories", {
    query: { active: input?.active },
  });
  return payload.map(normalizeProductCategory);
}

export async function createProductCategory(body: {
  name: string;
  color_code?: string | null;
  sort_order?: number | null;
  is_active?: boolean;
}): Promise<ProductCategoryRecord> {
  const payload = await request<any>("/api/staff/pos/product-categories", {
    method: "POST",
    body: JSON.stringify(body),
  });
  return normalizeProductCategory(payload);
}

export async function patchProductCategory(
  categoryId: string,
  body: {
    name?: string;
    color_code?: string | null;
    sort_order?: number | null;
    is_active?: boolean;
  },
): Promise<ProductCategoryRecord> {
  const payload = await request<any>(`/api/staff/pos/product-categories/${categoryId}`, {
    method: "PATCH",
    body: JSON.stringify(body),
  });
  return normalizeProductCategory(payload);
}

export async function deleteProductCategory(categoryId: string): Promise<{ deleted: boolean; reassigned_category_id: string }> {
  return request(`/api/staff/pos/product-categories/${categoryId}`, {
    method: "DELETE",
  });
}

export async function listProductSubcategories(input?: {
  category_id?: string;
  active?: boolean;
}): Promise<ProductSubcategoryRecord[]> {
  const payload = await request<any[]>("/api/staff/pos/product-subcategories", {
    query: {
      category_id: input?.category_id,
      active: input?.active,
    },
  });
  return payload.map(normalizeProductSubcategory);
}

export async function createProductSubcategory(body: {
  category_id: string;
  name: string;
  sort_order?: number | null;
  is_active?: boolean;
}): Promise<ProductSubcategoryRecord> {
  const payload = await request<any>("/api/staff/pos/product-subcategories", {
    method: "POST",
    body: JSON.stringify(body),
  });
  return normalizeProductSubcategory(payload);
}

export async function patchProductSubcategory(
  subcategoryId: string,
  body: {
    category_id?: string;
    name?: string;
    sort_order?: number | null;
    is_active?: boolean;
  },
): Promise<ProductSubcategoryRecord> {
  const payload = await request<any>(`/api/staff/pos/product-subcategories/${subcategoryId}`, {
    method: "PATCH",
    body: JSON.stringify(body),
  });
  return normalizeProductSubcategory(payload);
}

export async function deleteProductSubcategory(
  subcategoryId: string,
): Promise<{ deleted: boolean; reassigned_subcategory_id: string }> {
  return request(`/api/staff/pos/product-subcategories/${subcategoryId}`, {
    method: "DELETE",
  });
}

export async function importProducts(file: File): Promise<{
  created: number;
  updated: number;
  skipped: number;
  failed: number;
  failed_rows: Array<{ row_number: number; reason: string }>;
}> {
  const formData = new FormData();
  formData.append("file", file);
  return request("/api/staff/pos/products:import", {
    method: "POST",
    body: formData,
  });
}

export async function getProduct(productId: string): Promise<ProductRecord> {
  const payload = await request<any>(`/api/staff/pos/products/${productId}`);
  return normalizeProduct(payload);
}

export async function patchProduct(
  productId: string,
  body: {
    external_plu?: string;
    name?: string;
    receipt_name?: string;
    barcode?: string | null;
    category?: string;
    category_id?: string | null;
    subcategory_id?: string | null;
    sort_order?: number | null;
    color_code?: string | null;
    unit_gross_cents?: number;
    vat_rate?: number;
    is_active?: boolean;
  },
): Promise<ProductRecord> {
  const payload = await request<any>(`/api/staff/pos/products/${productId}`, {
    method: "PATCH",
    body: JSON.stringify(body),
  });
  return normalizeProduct(payload);
}

export async function uploadProductImage(productId: string, file: File): Promise<ProductRecord> {
  const formData = new FormData();
  formData.append("file", file);
  const payload = await request<any>(`/api/staff/pos/products/${productId}/image`, {
    method: "POST",
    body: formData,
  });
  return normalizeProduct(payload);
}

export async function replaceProductAllergens(
  productId: string,
  allergens: string[],
): Promise<ProductRecord> {
  const payload = await request<any>(`/api/staff/pos/products/${productId}/allergens`, {
    method: "PUT",
    body: JSON.stringify({ allergens }),
  });
  return normalizeProduct(payload);
}

export async function listProductGridPages(input?: {
  category_id?: string;
  subcategory_id?: string;
}): Promise<ProductGridPageRecord[]> {
  const payload = await request<any[]>("/api/staff/pos/product-grids/pages", {
    query: {
      category_id: input?.category_id,
      subcategory_id: input?.subcategory_id,
    },
  });
  return payload.map(normalizeGridPage);
}

export async function createProductGridPage(body: {
  category_id: string;
  subcategory_id: string;
  title?: string;
  page_number?: number;
  rows?: number;
  cols?: number;
  sort_order?: number;
}): Promise<ProductGridPageRecord> {
  const payload = await request<any>("/api/staff/pos/product-grids/pages", {
    method: "POST",
    body: JSON.stringify(body),
  });
  return normalizeGridPage(payload);
}

export async function patchProductGridPage(
  pageId: string,
  body: {
    category_id?: string;
    subcategory_id?: string;
    title?: string;
    page_number?: number;
    rows?: number;
    cols?: number;
    sort_order?: number;
  },
): Promise<ProductGridPageRecord> {
  const payload = await request<any>(`/api/staff/pos/product-grids/pages/${pageId}`, {
    method: "PATCH",
    body: JSON.stringify(body),
  });
  return normalizeGridPage(payload);
}

export async function getProductGridSlots(pageId: string): Promise<ProductGridSlotRecord[]> {
  const payload = await request<any[]>(`/api/staff/pos/product-grids/pages/${pageId}/slots`);
  return payload.map(normalizeGridSlot);
}

export async function replaceProductGridSlots(
  pageId: string,
  body: {
    slots: Array<{
      position: number;
      product_id?: string | null;
      label_override?: string | null;
      image_override_path?: string | null;
    }>;
  },
): Promise<ProductGridSlotRecord[]> {
  const payload = await request<any[]>(`/api/staff/pos/product-grids/pages/${pageId}/slots`, {
    method: "PUT",
    body: JSON.stringify(body),
  });
  return payload.map(normalizeGridSlot);
}

export function edgeAssetUrl(path: string | null): string | null {
  if (!path) {
    return null;
  }
  return `${EDGE_BASE_URL}${path}`;
}

export async function recordPayment(input: {
  checkId: string;
  method: "CASH" | "CARD_EXTERNAL";
  amountCents: number;
  externalRef?: string;
}): Promise<CheckRecord> {
  const payload = await request<any>(`/api/staff/pos/checks/${input.checkId}/payments`, {
    method: "POST",
    body: JSON.stringify({
      method: input.method,
      amount_cents: input.amountCents,
      external_ref: input.externalRef || undefined,
    }),
  });
  return normalizeCheck(payload);
}

export async function finalizeCheck(input: {
  checkId: string;
  paymentMethod: "CASH" | "CARD_EXTERNAL";
}): Promise<CheckRecord> {
  const payload = await request<any>(`/api/staff/pos/checks/${input.checkId}/finalize`, {
    method: "POST",
    body: JSON.stringify({
      payment_method: input.paymentMethod,
      issue_receipt: true,
    }),
  });
  return normalizeCheck(payload);
}
