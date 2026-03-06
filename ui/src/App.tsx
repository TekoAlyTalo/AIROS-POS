import { useEffect, useState } from "react";
import { Globe, Loader2, RadioTower, RotateCcw, Sparkles } from "lucide-react";
import { toast } from "sonner";

import { AppShell } from "@/components/app-shell";
import { ProductOrderAside } from "@/components/product-order-aside";
import { ProductsWorkspace } from "@/components/products-workspace";
import { StatusChip } from "@/components/status-chip";
import { TableDrawer } from "@/components/table-drawer";
import { TableMapCanvas } from "@/components/table-map-canvas";
import { Button } from "@/components/ui/button";
import {
  addItems,
  applyOverviewToFloorplans,
  ApiError,
  createProduct,
  createProductCategory,
  createProductSubcategory,
  deleteProductCategory,
  deleteProductSubcategory,
  finalizeCheck,
  getCheck,
  getCurrentSession,
  getFloorplansOrTables,
  getParty,
  getProductGridSlots,
  getTableOverview,
  importProducts,
  listPartyChecks,
  listProductCategories,
  listProductGridPages,
  listProducts,
  listProductSubcategories,
  openCheck,
  openParty,
  patchProduct,
  patchProductCategory,
  patchProductGridPage,
  patchProductSubcategory,
  recordPayment,
  replaceProductAllergens,
  replaceProductGridSlots,
  uploadProductImage,
} from "@/lib/api";
import type {
  CheckRecord,
  FloorplanRecord,
  PartyRecord,
  ProductCategoryRecord,
  ProductGridPageRecord,
  ProductGridSlotRecord,
  ProductRecord,
  ProductSubcategoryRecord,
  SessionRecord,
  TableRecord,
} from "@/lib/normalize";
import type { AppRoute } from "@/lib/router";
import { resolveRoute, routePath, toAppHref } from "@/lib/router";
import { formatCompactId } from "@/lib/utils";

function App() {
  const [route, setRoute] = useState<AppRoute>(() => resolveRoute(window.location.pathname));
  const [floorplans, setFloorplans] = useState<FloorplanRecord[]>([]);
  const [selectedFloorplanId, setSelectedFloorplanId] = useState<string | null>(null);
  const [selectedTableId, setSelectedTableId] = useState<string | null>(null);
  const [partyStore, setPartyStore] = useState<Record<string, PartyRecord>>({});
  const [partyChecks, setPartyChecks] = useState<Record<string, string[]>>({});
  const [activeCheckByParty, setActiveCheckByParty] = useState<Record<string, string>>({});
  const [checkStore, setCheckStore] = useState<Record<string, CheckRecord>>({});
  const [products, setProducts] = useState<ProductRecord[]>([]);
  const [productCategories, setProductCategories] = useState<ProductCategoryRecord[]>([]);
  const [productSubcategories, setProductSubcategories] = useState<ProductSubcategoryRecord[]>([]);
  const [gridPages, setGridPages] = useState<ProductGridPageRecord[]>([]);
  const [gridSlotsByPage, setGridSlotsByPage] = useState<Record<string, ProductGridSlotRecord[]>>({});
  const [sessionInfo, setSessionInfo] = useState<SessionRecord | null>(null);
  const [mapLoading, setMapLoading] = useState(true);
  const [drawerBusy, setDrawerBusy] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [connectionState, setConnectionState] = useState<"online" | "offline" | "loading">("loading");

  const selectedFloorplan =
    floorplans.find((floorplan) => floorplan.id === selectedFloorplanId) ?? floorplans[0] ?? null;
  const tables = selectedFloorplan?.tables ?? [];
  const selectedTable = tables.find((table) => table.id === selectedTableId) ?? null;
  const currentPartyId = selectedTable?.currentPartyId ?? null;
  const selectedParty = currentPartyId ? partyStore[currentPartyId] ?? null : null;
  const selectedPartyCheckIds = currentPartyId ? partyChecks[currentPartyId] ?? [] : [];
  const selectedCheckId = currentPartyId
    ? activeCheckByParty[currentPartyId] ??
      selectedPartyCheckIds.find((checkId) => checkStore[checkId]?.status === "OPEN") ??
      selectedPartyCheckIds[0] ??
      null
    : null;
  const selectedCheck = selectedCheckId ? checkStore[selectedCheckId] ?? null : null;

  function navigate(nextRoute: AppRoute) {
    const href = toAppHref(routePath(nextRoute));
    if (window.location.pathname !== href) {
      window.history.pushState({}, "", href);
    }
    setRoute(nextRoute);
  }

  async function loadMap(background = false) {
    if (!background) {
      setMapLoading(true);
      setConnectionState("loading");
    }
    try {
      const [nextFloorplans, nextSession, nextOverview] = await Promise.all([
        getFloorplansOrTables(),
        getCurrentSession(),
        getTableOverview(),
      ]);
      const mergedFloorplans = applyOverviewToFloorplans(nextFloorplans, nextOverview);
      setFloorplans(mergedFloorplans);
      setSelectedFloorplanId((current) => current ?? nextFloorplans[0]?.id ?? null);
      setSelectedTableId((current) => {
        const tableIds = new Set(mergedFloorplans.flatMap((floorplan) => floorplan.tables.map((table) => table.id)));
        if (current && tableIds.has(current)) {
          return current;
        }
        return mergedFloorplans[0]?.tables[0]?.id ?? null;
      });
      setSessionInfo(nextSession);
      setErrorMessage(null);
      setConnectionState("online");
    } catch (error) {
      const message = error instanceof ApiError ? error.detail : "Unable to reach the Edge API.";
      setConnectionState("offline");
      setErrorMessage(message);
    } finally {
      if (!background) {
        setMapLoading(false);
      }
    }
  }

  async function loadCatalog() {
    const [nextProducts, nextCategories, nextSubcategories, nextPages] = await Promise.all([
      listProducts(),
      listProductCategories(),
      listProductSubcategories(),
      listProductGridPages(),
    ]);
    setProducts(nextProducts);
    setProductCategories(nextCategories);
    setProductSubcategories(nextSubcategories);
    setGridPages(nextPages);
    const slotEntries = await Promise.all(
      nextPages.map(async (page) => [page.id, await getProductGridSlots(page.id)] as const),
    );
    setGridSlotsByPage(Object.fromEntries(slotEntries));
  }

  async function refreshCheckState(checkId: string) {
    const nextCheck = await getCheck(checkId);
    setCheckStore((current) => ({ ...current, [checkId]: nextCheck }));
    return nextCheck;
  }

  useEffect(() => {
    void loadMap();
    void loadCatalog().catch(() => {
      toast.error("Unable to load product catalog.");
    });
  }, []);

  useEffect(() => {
    const onPopState = () => setRoute(resolveRoute(window.location.pathname));
    window.addEventListener("popstate", onPopState);
    return () => {
      window.removeEventListener("popstate", onPopState);
    };
  }, []);

  useEffect(() => {
    const intervalId = window.setInterval(() => {
      void loadMap(true);
    }, 5000);
    return () => {
      window.clearInterval(intervalId);
    };
  }, []);

  useEffect(() => {
    if (!selectedCheckId) {
      return;
    }
    if (checkStore[selectedCheckId]) {
      return;
    }
    void refreshCheckState(selectedCheckId).catch(() => {
      toast.error("Unable to refresh the selected check.");
    });
  }, [selectedCheckId, checkStore]);

  useEffect(() => {
    if (!currentPartyId) {
      return;
    }
    const partyId = currentPartyId;
    let cancelled = false;
    async function loadPartyContext() {
      try {
        const [nextParty, nextChecks] = await Promise.all([getParty(partyId), listPartyChecks(partyId)]);
        if (cancelled) {
          return;
        }
        setPartyStore((current) => ({ ...current, [nextParty.id]: nextParty }));
        setPartyChecks((current) => ({ ...current, [nextParty.id]: nextChecks.map((check) => check.id) }));
        setCheckStore((current) => {
          const nextStore = { ...current };
          for (const check of nextChecks) {
            nextStore[check.id] = check;
          }
          return nextStore;
        });
        setActiveCheckByParty((current) => {
          const existing = current[nextParty.id];
          if (existing && nextChecks.some((check) => check.id === existing)) {
            return current;
          }
          const fallback = nextChecks.find((check) => check.status === "OPEN")?.id ?? nextChecks[0]?.id;
          return fallback ? { ...current, [nextParty.id]: fallback } : current;
        });
      } catch (error) {
        if (!cancelled) {
          toast.error(error instanceof ApiError ? error.detail : "Unable to refresh party context.");
        }
      }
    }
    void loadPartyContext();
    return () => {
      cancelled = true;
    };
  }, [currentPartyId]);

  async function handleOpenParty(input: { table: TableRecord; guestCount: number; note: string }) {
    setDrawerBusy(true);
    try {
      const party = await openParty({
        tableId: input.table.id,
        guestCount: input.guestCount,
        note: input.note,
      });
      setPartyStore((current) => ({ ...current, [party.id]: party }));
      toast.success(`Party opened at ${input.table.label}`);
      await loadMap();
      setSelectedTableId(input.table.id);
    } catch (error) {
      toast.error(error instanceof ApiError ? error.detail : "Unable to open party.");
    } finally {
      setDrawerBusy(false);
    }
  }

  async function handleOpenCheck(partyId: string) {
    setDrawerBusy(true);
    try {
      const check = await openCheck(partyId);
      setCheckStore((current) => ({ ...current, [check.id]: check }));
      setPartyChecks((current) => ({
        ...current,
        [partyId]: Array.from(new Set([...(current[partyId] ?? []), check.id])),
      }));
      setActiveCheckByParty((current) => ({ ...current, [partyId]: check.id }));
      toast.success("Check opened");
      await loadMap();
    } catch (error) {
      toast.error(error instanceof ApiError ? error.detail : "Unable to open check.");
    } finally {
      setDrawerBusy(false);
    }
  }

  async function handleSelectCheck(partyId: string, checkId: string) {
    setActiveCheckByParty((current) => ({ ...current, [partyId]: checkId }));
    try {
      await refreshCheckState(checkId);
    } catch (error) {
      toast.error(error instanceof ApiError ? error.detail : "Unable to fetch check.");
    }
  }

  async function handleAddItem(checkId: string, body: Parameters<typeof addItems>[1]) {
    setDrawerBusy(true);
    try {
      const check = await addItems(checkId, body);
      setCheckStore((current) => ({ ...current, [check.id]: check }));
      toast.success("Item added");
      await loadMap();
    } catch (error) {
      toast.error(error instanceof ApiError ? error.detail : "Unable to add item.");
    } finally {
      setDrawerBusy(false);
    }
  }

  async function handleRecordPayment(input: Parameters<typeof recordPayment>[0]) {
    setDrawerBusy(true);
    try {
      const check = await recordPayment(input);
      setCheckStore((current) => ({ ...current, [check.id]: check }));
      toast.success(`${input.method} payment recorded`);
      await loadMap();
    } catch (error) {
      toast.error(error instanceof ApiError ? error.detail : "Unable to record payment.");
    } finally {
      setDrawerBusy(false);
    }
  }

  async function handleFinalize(input: Parameters<typeof finalizeCheck>[0]) {
    setDrawerBusy(true);
    try {
      const check = await finalizeCheck(input);
      setCheckStore((current) => ({ ...current, [check.id]: check }));
      toast.success(
        check.receiptNo ? `Check finalized. Receipt #${check.receiptNo}` : "Check finalized successfully.",
      );
      await loadMap();
    } catch (error) {
      toast.error(error instanceof ApiError ? error.detail : "Unable to finalize check.");
    } finally {
      setDrawerBusy(false);
    }
  }

  async function handleAddProduct(product: ProductRecord) {
    if (!selectedCheckId) {
      toast.error("Select table first");
      return;
    }
    await handleAddItem(selectedCheckId, {
      items: [
        {
          product_id: product.id,
          name_snapshot: product.receiptName,
          qty: 1,
          pricing_model: "SINGLE_VAT",
          components: [
            {
              name_snapshot: product.receiptName,
              vat_rate_snapshot: product.vatRate,
              unit_gross_cents_snapshot: product.unitGrossCents,
              qty: 1,
            },
          ],
        },
      ],
    });
  }

  async function handlePatchGridPage(
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
  ) {
    await patchProductGridPage(pageId, body);
    await loadCatalog();
  }

  async function handleReplaceGridSlots(
    pageId: string,
    slots: Array<{
      position: number;
      product_id?: string | null;
      label_override?: string | null;
      image_override_path?: string | null;
    }>,
  ) {
    await replaceProductGridSlots(pageId, { slots });
    await loadCatalog();
  }

  async function handlePatchProduct(
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
  ) {
    await patchProduct(productId, body);
    await loadCatalog();
  }

  async function handleCreateProduct(
    body: {
      external_plu: string;
      name: string;
      receipt_name: string;
      barcode?: string | null;
      category_id?: string | null;
      subcategory_id?: string | null;
      sort_order?: number | null;
      color_code?: string | null;
      unit_gross_cents: number;
      vat_rate: number;
      is_active?: boolean;
      allergens?: string[];
    },
  ) {
    await createProduct(body);
    await loadCatalog();
  }

  async function handleUploadProductImage(productId: string, file: File) {
    await uploadProductImage(productId, file);
    await loadCatalog();
  }

  async function handleReplaceProductAllergens(productId: string, allergens: string[]) {
    await replaceProductAllergens(productId, allergens);
    await loadCatalog();
  }

  async function handleCreateCategory(body: {
    name: string;
    color_code?: string | null;
    sort_order?: number | null;
    is_active?: boolean;
  }) {
    await createProductCategory(body);
    await loadCatalog();
  }

  async function handlePatchCategory(
    categoryId: string,
    body: {
      name?: string;
      color_code?: string | null;
      sort_order?: number | null;
      is_active?: boolean;
    },
  ) {
    await patchProductCategory(categoryId, body);
    await loadCatalog();
  }

  async function handleDeleteCategory(categoryId: string) {
    await deleteProductCategory(categoryId);
    await loadCatalog();
  }

  async function handleCreateSubcategory(body: {
    category_id: string;
    name: string;
    sort_order?: number | null;
    is_active?: boolean;
  }) {
    await createProductSubcategory(body);
    await loadCatalog();
  }

  async function handlePatchSubcategory(
    subcategoryId: string,
    body: {
      category_id?: string;
      name?: string;
      sort_order?: number | null;
      is_active?: boolean;
    },
  ) {
    await patchProductSubcategory(subcategoryId, body);
    await loadCatalog();
  }

  async function handleDeleteSubcategory(subcategoryId: string) {
    await deleteProductSubcategory(subcategoryId);
    await loadCatalog();
  }

  async function handleImportProducts(file: File) {
    const summary = await importProducts(file);
    await loadCatalog();
    return summary;
  }

  const statusControls = (
    <div className="flex flex-wrap items-center gap-2">
      <StatusChip
        label={connectionState === "online" ? "API Live" : connectionState === "loading" ? "API Loading" : "API Offline"}
        tone={connectionState === "online" ? "success" : connectionState === "loading" ? "info" : "danger"}
      />
      <StatusChip
        label={sessionInfo ? `Session ${sessionInfo.status}` : "Session Closed"}
        tone={sessionInfo ? "info" : "warning"}
        compactId={sessionInfo?.id}
      />
      <StatusChip label="Opas Off" tone="neutral" />
      <StatusChip label="Voice Off" tone="neutral" />
      <Button variant="secondary" size="sm">
        <Globe className="h-3.5 w-3.5" />
        FI
      </Button>
      <Button variant="ghost" size="sm">
        EN
      </Button>
    </div>
  );

  const header =
    route === "table-map" ? (
      <header className="glass-card flex flex-col gap-4 p-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <p className="text-xs uppercase tracking-[0.28em] text-muted-foreground">Staff operations</p>
            <h2 className="mt-2 text-xl font-semibold tracking-[0.04em] text-foreground">Floor map and service drawer</h2>
          </div>
          {statusControls}
        </div>

        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap gap-2">
            {floorplans.map((floorplan) => (
              <Button
                key={floorplan.id}
                variant={floorplan.id === selectedFloorplanId ? "default" : "secondary"}
                size="sm"
                onClick={() => setSelectedFloorplanId(floorplan.id)}
              >
                <RadioTower className="h-3.5 w-3.5" />
                {floorplan.name}
              </Button>
            ))}
          </div>

          <div className="flex items-center gap-2 rounded-full border border-white/[0.08] bg-white/[0.03] px-3 py-2 text-xs text-muted-foreground">
            <Sparkles className="h-3.5 w-3.5 text-primary" />
            <span>Selected floor</span>
            <span className="font-medium text-foreground">
              {floorplans.find((floorplan) => floorplan.id === selectedFloorplanId)?.name ?? "N/A"}
            </span>
            <span className="text-[10px] uppercase tracking-[0.18em] text-muted-foreground">
              {formatCompactId(selectedFloorplanId)}
            </span>
          </div>
        </div>
      </header>
    ) : null;

  return (
    <AppShell
      currentRoute={route}
      onNavigate={navigate}
      header={header}
      aside={
        route === "table-map" ? (
          <TableDrawer
            table={selectedTable}
            party={selectedParty}
            sessionInfo={sessionInfo}
            checkIds={selectedPartyCheckIds}
            activeCheckId={selectedCheckId}
            activeCheck={selectedCheck}
            busy={drawerBusy}
            onOpenParty={handleOpenParty}
            onOpenCheck={handleOpenCheck}
            onSelectCheck={handleSelectCheck}
            onRefreshCheck={refreshCheckState}
            onAddItems={handleAddItem}
            onRecordPayment={handleRecordPayment}
            onFinalize={handleFinalize}
          />
        ) : (
          <ProductOrderAside
            table={selectedTable}
            party={selectedParty}
            checkIds={selectedPartyCheckIds}
            activeCheckId={selectedCheckId}
            activeCheck={selectedCheck}
            busy={drawerBusy}
            onOpenCheck={handleOpenCheck}
            onSelectCheck={handleSelectCheck}
          />
        )
      }
    >
      {route === "table-map" ? (
        <section className="glass-card flex min-h-[620px] flex-col overflow-hidden p-5">
          <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
            <div>
              <p className="text-xs uppercase tracking-[0.32em] text-muted-foreground">Floor view</p>
              <h1 className="mt-2 text-2xl font-semibold tracking-[0.04em] text-foreground">Table Map</h1>
              <p className="mt-2 text-sm text-muted-foreground">
                Auto-refreshing every 5 seconds with live table overview.
              </p>
            </div>
            <Button variant="secondary" onClick={() => void loadMap()} disabled={mapLoading}>
              {mapLoading ? <Loader2 className="h-4 w-4 animate-spin" /> : <RotateCcw className="h-4 w-4" />}
              Refresh
            </Button>
          </div>

          {errorMessage ? (
            <div className="mb-4 rounded-2xl border border-destructive/30 bg-destructive/10 p-4 text-sm text-destructive-foreground">
              {errorMessage}
            </div>
          ) : null}

          <div className="flex-1">
            <TableMapCanvas
              tables={tables}
              selectedTableId={selectedTableId}
              onSelectTable={setSelectedTableId}
              loading={mapLoading}
            />
          </div>
        </section>
      ) : (
        <ProductsWorkspace
          products={products}
          categories={productCategories}
          subcategories={productSubcategories}
          gridPages={gridPages}
          gridSlotsByPage={gridSlotsByPage}
          activeCheckId={selectedCheckId}
          busy={drawerBusy}
          onAddProduct={handleAddProduct}
          onPatchGridPage={handlePatchGridPage}
          onReplaceGridSlots={handleReplaceGridSlots}
          onCreateProduct={handleCreateProduct}
          onPatchProduct={handlePatchProduct}
          onReplaceProductAllergens={handleReplaceProductAllergens}
          onUploadProductImage={handleUploadProductImage}
          onCreateCategory={handleCreateCategory}
          onPatchCategory={handlePatchCategory}
          onDeleteCategory={handleDeleteCategory}
          onCreateSubcategory={handleCreateSubcategory}
          onPatchSubcategory={handlePatchSubcategory}
          onDeleteSubcategory={handleDeleteSubcategory}
          onImportProducts={handleImportProducts}
        />
      )}
    </AppShell>
  );
}

export default App;
