import { useEffect, useMemo, useRef, useState } from "react";
import {
  Edit3,
  FolderPlus,
  Grid2x2,
  LayoutGrid,
  ListPlus,
  PackagePlus,
  PencilLine,
  Search,
  Settings2,
  Upload,
  X,
} from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { edgeAssetUrl } from "@/lib/api";
import type {
  ProductCategoryRecord,
  ProductGridPageRecord,
  ProductGridSlotRecord,
  ProductRecord,
  ProductSubcategoryRecord,
} from "@/lib/normalize";
import { cn, formatCurrency } from "@/lib/utils";

type GridSlotInput = {
  position: number;
  product_id?: string | null;
  label_override?: string | null;
  image_override_path?: string | null;
};

type ProductFormState = {
  externalPlu: string;
  name: string;
  receiptName: string;
  barcode: string;
  categoryId: string;
  subcategoryId: string;
  sortOrder: string;
  colorCode: string;
  unitGrossCents: string;
  vatRate: "0.14" | "0.255";
  isActive: boolean;
  allergens: string[];
};

type ProductsWorkspaceProps = {
  products: ProductRecord[];
  categories: ProductCategoryRecord[];
  subcategories: ProductSubcategoryRecord[];
  gridPages: ProductGridPageRecord[];
  gridSlotsByPage: Record<string, ProductGridSlotRecord[]>;
  activeCheckId: string | null;
  busy: boolean;
  onAddProduct: (product: ProductRecord) => Promise<void>;
  onPatchGridPage: (
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
  ) => Promise<void>;
  onReplaceGridSlots: (pageId: string, slots: GridSlotInput[]) => Promise<void>;
  onCreateProduct: (body: {
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
  }) => Promise<void>;
  onPatchProduct: (
    productId: string,
    body: {
      external_plu?: string;
      name?: string;
      receipt_name?: string;
      barcode?: string | null;
      category_id?: string | null;
      subcategory_id?: string | null;
      sort_order?: number | null;
      color_code?: string | null;
      unit_gross_cents?: number;
      vat_rate?: number;
      is_active?: boolean;
    },
  ) => Promise<void>;
  onReplaceProductAllergens: (productId: string, allergens: string[]) => Promise<void>;
  onUploadProductImage: (productId: string, file: File) => Promise<void>;
  onCreateCategory: (body: {
    name: string;
    color_code?: string | null;
    sort_order?: number | null;
    is_active?: boolean;
  }) => Promise<void>;
  onPatchCategory: (
    categoryId: string,
    body: {
      name?: string;
      color_code?: string | null;
      sort_order?: number | null;
      is_active?: boolean;
    },
  ) => Promise<void>;
  onDeleteCategory: (categoryId: string) => Promise<void>;
  onCreateSubcategory: (body: {
    category_id: string;
    name: string;
    sort_order?: number | null;
    is_active?: boolean;
  }) => Promise<void>;
  onPatchSubcategory: (
    subcategoryId: string,
    body: {
      category_id?: string;
      name?: string;
      sort_order?: number | null;
      is_active?: boolean;
    },
  ) => Promise<void>;
  onDeleteSubcategory: (subcategoryId: string) => Promise<void>;
  onImportProducts: (file: File) => Promise<{
    created: number;
    updated: number;
    skipped: number;
    failed: number;
    failed_rows: Array<{ row_number: number; reason: string }>;
  }>;
};

const squareGridOptions = [2, 3, 4, 5, 6, 7, 8];
const allergenOptions = [
  "gluten",
  "milk",
  "egg",
  "nuts",
  "peanuts",
  "fish",
  "shellfish",
  "soy",
  "celery",
  "mustard",
  "sesame",
  "lupin",
  "sulphites",
  "molluscs",
];

function sortCategories(categories: ProductCategoryRecord[]): ProductCategoryRecord[] {
  return [...categories].sort((left, right) => left.sortOrder - right.sortOrder || left.name.localeCompare(right.name));
}

function sortSubcategories(subcategories: ProductSubcategoryRecord[]): ProductSubcategoryRecord[] {
  return [...subcategories].sort((left, right) => left.sortOrder - right.sortOrder || left.name.localeCompare(right.name));
}

function sortProducts(products: ProductRecord[]): ProductRecord[] {
  return [...products].sort((left, right) => {
    if (left.sortOrder !== right.sortOrder) {
      return left.sortOrder - right.sortOrder;
    }
    return left.name.localeCompare(right.name);
  });
}

function buildSlotsForPage(page: ProductGridPageRecord, slots: ProductGridSlotRecord[] | undefined): ProductGridSlotRecord[] {
  const slotMap = new Map((slots ?? []).map((slot) => [slot.position, slot]));
  return Array.from({ length: page.rows * page.cols }, (_, position) => {
    return (
      slotMap.get(position) ?? {
        pageId: page.id,
        position,
        productId: null,
        labelOverride: null,
        imageOverridePath: null,
        isManual: false,
        product: null,
      }
    );
  });
}

function imageUrlForSlot(slot: ProductGridSlotRecord): string | null {
  return edgeAssetUrl(slot.imageOverridePath ?? slot.product?.imagePath ?? null);
}

async function convertToPng(file: File): Promise<File> {
  if (file.type === "image/png") {
    return file;
  }
  const dataUrl = await new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ""));
    reader.onerror = () => reject(new Error("Unable to read the image file."));
    reader.readAsDataURL(file);
  });
  const image = await new Promise<HTMLImageElement>((resolve, reject) => {
    const element = new Image();
    element.onload = () => resolve(element);
    element.onerror = () => reject(new Error("Unable to decode the image file."));
    element.src = dataUrl;
  });
  const canvas = document.createElement("canvas");
  canvas.width = image.naturalWidth || image.width;
  canvas.height = image.naturalHeight || image.height;
  const context = canvas.getContext("2d");
  if (!context) {
    throw new Error("Unable to prepare image upload.");
  }
  context.drawImage(image, 0, 0);
  const blob = await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob((nextBlob) => {
      if (!nextBlob) {
        reject(new Error("Unable to encode PNG image."));
        return;
      }
      resolve(nextBlob);
    }, "image/png");
  });
  const baseName = file.name.replace(/\.[^.]+$/, "") || "product";
  return new File([blob], `${baseName}.png`, { type: "image/png" });
}

function emptyProductForm(categoryId: string, subcategoryId: string): ProductFormState {
  return {
    externalPlu: "",
    name: "",
    receiptName: "",
    barcode: "",
    categoryId,
    subcategoryId,
    sortOrder: "",
    colorCode: "",
    unitGrossCents: "",
    vatRate: "0.14",
    isActive: true,
    allergens: [],
  };
}

function productToForm(product: ProductRecord): ProductFormState {
  return {
    externalPlu: product.externalPlu,
    name: product.name,
    receiptName: product.receiptName,
    barcode: product.barcode ?? "",
    categoryId: product.categoryId ?? "",
    subcategoryId: product.subcategoryId ?? "",
    sortOrder: String(product.sortOrder),
    colorCode: product.colorCode ?? "",
    unitGrossCents: String(product.unitGrossCents),
    vatRate: product.vatRate === 0.255 ? "0.255" : "0.14",
    isActive: product.isActive,
    allergens: [...product.allergens],
  };
}

export function ProductsWorkspace({
  products,
  categories,
  subcategories,
  gridPages,
  gridSlotsByPage,
  activeCheckId,
  busy,
  onAddProduct,
  onPatchGridPage,
  onReplaceGridSlots,
  onCreateProduct,
  onPatchProduct,
  onReplaceProductAllergens,
  onUploadProductImage,
  onCreateCategory,
  onPatchCategory,
  onDeleteCategory,
  onCreateSubcategory,
  onPatchSubcategory,
  onDeleteSubcategory,
  onImportProducts,
}: ProductsWorkspaceProps) {
  const importInputRef = useRef<HTMLInputElement | null>(null);
  const [editMode, setEditMode] = useState(false);
  const [assignMode, setAssignMode] = useState(false);
  const [activeCategoryId, setActiveCategoryId] = useState("");
  const [activeSubcategoryId, setActiveSubcategoryId] = useState("");
  const [activePageBySubcategory, setActivePageBySubcategory] = useState<Record<string, string>>({});
  const [assigningSlot, setAssigningSlot] = useState<ProductGridSlotRecord | null>(null);
  const [slotQuery, setSlotQuery] = useState("");
  const [categoryDialog, setCategoryDialog] = useState<"create" | "edit" | null>(null);
  const [categoryName, setCategoryName] = useState("");
  const [categoryColorCode, setCategoryColorCode] = useState("");
  const [categoryIsActive, setCategoryIsActive] = useState(true);
  const [subcategoryDialog, setSubcategoryDialog] = useState<"create" | "edit" | null>(null);
  const [subcategoryName, setSubcategoryName] = useState("");
  const [subcategoryIsActive, setSubcategoryIsActive] = useState(true);
  const [productDialogOpen, setProductDialogOpen] = useState(false);
  const [editingProductId, setEditingProductId] = useState<string | null>(null);
  const [productForm, setProductForm] = useState<ProductFormState>(() => emptyProductForm("", ""));
  const [lastImportSummary, setLastImportSummary] = useState<{
    created: number;
    updated: number;
    skipped: number;
    failed: number;
    failed_rows: Array<{ row_number: number; reason: string }>;
  } | null>(null);

  const activeProductCategoryIds = new Set(products.filter((product) => product.isActive && product.categoryId).map((product) => product.categoryId as string));
  const visibleCategories = useMemo(
    () =>
      sortCategories(
        categories.filter(
          (category) => category.isActive && (editMode || activeProductCategoryIds.has(category.id) || category.activeProductCount > 0),
        ),
      ),
    [categories, editMode, products],
  );
  const currentCategory = visibleCategories.find((category) => category.id === activeCategoryId) ?? visibleCategories[0] ?? null;
  const categorySubcategories = useMemo(
    () =>
      sortSubcategories(
        subcategories.filter(
          (subcategory) =>
            subcategory.categoryId === currentCategory?.id &&
            subcategory.isActive &&
            (editMode || subcategory.activeProductCount > 0),
        ),
      ),
    [subcategories, currentCategory, editMode],
  );
  const currentSubcategory =
    categorySubcategories.find((subcategory) => subcategory.id === activeSubcategoryId) ?? categorySubcategories[0] ?? null;
  const pagesForSubcategory = useMemo(
    () =>
      [...gridPages]
        .filter((page) => page.subcategoryId === currentSubcategory?.id)
        .sort((left, right) => left.pageNumber - right.pageNumber || left.sortOrder - right.sortOrder),
    [gridPages, currentSubcategory],
  );
  const activePageId = currentSubcategory ? activePageBySubcategory[currentSubcategory.id] ?? pagesForSubcategory[0]?.id ?? null : null;
  const activePage = pagesForSubcategory.find((page) => page.id === activePageId) ?? pagesForSubcategory[0] ?? null;
  const activeSlots = activePage ? buildSlotsForPage(activePage, gridSlotsByPage[activePage.id]) : [];
  const productsInSubcategory = sortProducts(products.filter((product) => product.subcategoryId === currentSubcategory?.id));
  const slotCandidates = productsInSubcategory.filter((product) => {
    const query = slotQuery.trim().toLowerCase();
    if (!query) {
      return true;
    }
    return (
      product.name.toLowerCase().includes(query) ||
      product.receiptName.toLowerCase().includes(query) ||
      product.externalPlu.toLowerCase().includes(query)
    );
  });
  const editingProduct = products.find((product) => product.id === editingProductId) ?? null;

  useEffect(() => {
    if (!currentCategory && visibleCategories[0]) {
      setActiveCategoryId(visibleCategories[0].id);
      return;
    }
    if (currentCategory && activeCategoryId !== currentCategory.id) {
      setActiveCategoryId(currentCategory.id);
    }
  }, [activeCategoryId, currentCategory, visibleCategories]);

  useEffect(() => {
    if (!currentSubcategory && categorySubcategories[0]) {
      setActiveSubcategoryId(categorySubcategories[0].id);
      return;
    }
    if (currentSubcategory && activeSubcategoryId !== currentSubcategory.id) {
      setActiveSubcategoryId(currentSubcategory.id);
    }
  }, [activeSubcategoryId, currentSubcategory, categorySubcategories]);

  useEffect(() => {
    if (!currentSubcategory) {
      return;
    }
    const currentPageId = activePageBySubcategory[currentSubcategory.id];
    if (currentPageId && pagesForSubcategory.some((page) => page.id === currentPageId)) {
      return;
    }
    if (pagesForSubcategory[0]) {
      setActivePageBySubcategory((current) => ({ ...current, [currentSubcategory.id]: pagesForSubcategory[0].id }));
    }
  }, [activePageBySubcategory, currentSubcategory, pagesForSubcategory]);

  useEffect(() => {
    if (categoryDialog === "create") {
      setCategoryName("");
      setCategoryColorCode(currentCategory?.colorCode ?? "");
      setCategoryIsActive(true);
      return;
    }
    if (categoryDialog === "edit" && currentCategory) {
      setCategoryName(currentCategory.name);
      setCategoryColorCode(currentCategory.colorCode ?? "");
      setCategoryIsActive(currentCategory.isActive);
    }
  }, [categoryDialog, currentCategory]);

  useEffect(() => {
    if (subcategoryDialog === "create") {
      setSubcategoryName("");
      setSubcategoryIsActive(true);
      return;
    }
    if (subcategoryDialog === "edit" && currentSubcategory) {
      setSubcategoryName(currentSubcategory.name);
      setSubcategoryIsActive(currentSubcategory.isActive);
    }
  }, [subcategoryDialog, currentSubcategory]);

  function openCreateProduct() {
    const nextCategoryId = currentCategory?.id ?? visibleCategories[0]?.id ?? "";
    const nextSubcategoryId = currentSubcategory?.id ?? categorySubcategories[0]?.id ?? "";
    setEditingProductId(null);
    setProductForm(emptyProductForm(nextCategoryId, nextSubcategoryId));
    setProductDialogOpen(true);
  }

  function openEditProduct(product: ProductRecord) {
    setEditingProductId(product.id);
    setProductForm(productToForm(product));
    setProductDialogOpen(true);
  }

  function resolveProductSubcategoryOptions(categoryId: string) {
    return sortSubcategories(subcategories.filter((subcategory) => subcategory.categoryId === categoryId && subcategory.isActive));
  }

  async function handleImportFile(file: File) {
    const summary = await onImportProducts(file);
    setLastImportSummary(summary);
    toast.success(
      `Import: ${summary.created} created, ${summary.updated} updated, ${summary.skipped} skipped, ${summary.failed} failed`,
    );
  }

  async function handleAutoFillPage() {
    if (!activePage) {
      return;
    }
    const payload = activeSlots.map((slot) => ({
      position: slot.position,
      product_id: slot.product?.id ?? null,
      label_override: slot.labelOverride,
      image_override_path: slot.imageOverridePath,
    }));
    await onReplaceGridSlots(activePage.id, payload);
    toast.success("Current page pinned");
  }

  async function handleClearPage() {
    if (!activePage) {
      return;
    }
    await onReplaceGridSlots(activePage.id, []);
    toast.success("Manual slot layout cleared");
  }

  async function handleAssignProduct(productId: string | null) {
    if (!activePage || !assigningSlot) {
      return;
    }
    const payload = activeSlots.map((slot) => ({
      position: slot.position,
      product_id: slot.position === assigningSlot.position ? productId : slot.isManual ? slot.product?.id ?? null : null,
      label_override: slot.position === assigningSlot.position ? null : slot.labelOverride,
      image_override_path: slot.position === assigningSlot.position ? null : slot.imageOverridePath,
    }));
    await onReplaceGridSlots(activePage.id, payload);
    setAssigningSlot(null);
    setSlotQuery("");
    toast.success(productId ? "Slot assigned" : "Slot cleared");
  }

  async function handleSaveCategory() {
    if (categoryDialog === "create") {
      await onCreateCategory({
        name: categoryName.trim(),
        color_code: categoryColorCode.trim() || null,
        is_active: categoryIsActive,
      });
      toast.success("Category created");
    } else if (categoryDialog === "edit" && currentCategory) {
      await onPatchCategory(currentCategory.id, {
        name: categoryName.trim(),
        color_code: categoryColorCode.trim() || null,
        is_active: categoryIsActive,
      });
      toast.success("Category updated");
    }
    setCategoryDialog(null);
  }

  async function handleSaveSubcategory() {
    if (!currentCategory) {
      return;
    }
    if (subcategoryDialog === "create") {
      await onCreateSubcategory({
        category_id: currentCategory.id,
        name: subcategoryName.trim(),
        is_active: subcategoryIsActive,
      });
      toast.success("Subcategory created");
    } else if (subcategoryDialog === "edit" && currentSubcategory) {
      await onPatchSubcategory(currentSubcategory.id, {
        name: subcategoryName.trim(),
        is_active: subcategoryIsActive,
      });
      toast.success("Subcategory updated");
    }
    setSubcategoryDialog(null);
  }

  async function handleSaveProduct() {
    const body = {
      external_plu: productForm.externalPlu.trim(),
      name: productForm.name.trim(),
      receipt_name: productForm.receiptName.trim(),
      barcode: productForm.barcode.trim() || null,
      category_id: productForm.categoryId || null,
      subcategory_id: productForm.subcategoryId || null,
      sort_order: productForm.sortOrder.trim() ? Number(productForm.sortOrder) : undefined,
      color_code: productForm.colorCode.trim() || null,
      unit_gross_cents: Number(productForm.unitGrossCents),
      vat_rate: Number(productForm.vatRate),
      is_active: productForm.isActive,
    };
    if (editingProductId) {
      await onPatchProduct(editingProductId, body);
      await onReplaceProductAllergens(editingProductId, productForm.allergens);
      toast.success("Product updated");
    } else {
      await onCreateProduct({ ...body, allergens: productForm.allergens });
      toast.success("Product created");
    }
    setProductDialogOpen(false);
    setEditingProductId(null);
  }

  function handleProductButtonClick(slot: ProductGridSlotRecord) {
    if (editMode) {
      if (assignMode || !slot.product) {
        setAssigningSlot(slot);
        return;
      }
      openEditProduct(slot.product);
      return;
    }
    if (slot.product) {
      void onAddProduct(slot.product).catch((error: unknown) => {
        toast.error(error instanceof Error ? error.message : "Unable to add product.");
      });
    }
  }

  return (
    <section className="h-full">
      <Card className="flex h-full min-h-0 flex-col p-3 sm:p-4">
        <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
          <div className="flex min-w-0 items-center gap-2">
            <p className="text-[11px] uppercase tracking-[0.28em] text-muted-foreground">Products</p>
            {currentCategory ? (
              <span className="rounded-full border border-white/10 bg-white/[0.03] px-3 py-1 text-xs text-muted-foreground">
                {currentCategory.name}
              </span>
            ) : null}
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button
              type="button"
              variant={editMode ? "default" : "secondary"}
              size="sm"
              onClick={() => setEditMode((current) => !current)}
            >
              <Settings2 className="h-3.5 w-3.5" />
              {editMode ? "Edit mode on" : "Admin edit mode"}
            </Button>
            <input
              ref={importInputRef}
              type="file"
              accept=".csv,text/csv"
              className="hidden"
              onChange={(event) => {
                const file = event.target.files?.[0];
                if (file) {
                  void handleImportFile(file).catch((error: unknown) => {
                    toast.error(error instanceof Error ? error.message : "Unable to import products.");
                  });
                }
                event.currentTarget.value = "";
              }}
            />
            <Button type="button" variant="secondary" size="sm" onClick={() => importInputRef.current?.click()}>
              <Upload className="h-3.5 w-3.5" />
              Import CSV
            </Button>
          </div>
        </div>

        <CardContent className="flex min-h-0 flex-1 flex-col gap-2.5 p-0">
          {lastImportSummary ? (
            <div className="rounded-[24px] border border-white/10 bg-white/[0.03] px-4 py-2.5 text-sm">
              <p className="font-medium text-foreground">
                Import diagnostics: {lastImportSummary.created} created, {lastImportSummary.updated} updated, {lastImportSummary.skipped} skipped, {lastImportSummary.failed} failed
              </p>
              {lastImportSummary.failed_rows.length > 0 ? (
                <div className="mt-2 space-y-1 text-muted-foreground">
                  {lastImportSummary.failed_rows.slice(0, 4).map((failure) => (
                    <p key={`${failure.row_number}-${failure.reason}`}>
                      Row {failure.row_number}: {failure.reason}
                    </p>
                  ))}
                </div>
              ) : null}
            </div>
          ) : null}

          {editMode ? (
            <div className="flex flex-wrap items-center gap-2 rounded-[24px] border border-white/10 bg-white/[0.03] px-3 py-2">
              <Button
                type="button"
                variant={assignMode ? "default" : "secondary"}
                size="sm"
                onClick={() => setAssignMode((current) => !current)}
              >
                <LayoutGrid className="h-3.5 w-3.5" />
                {assignMode ? "Assigning slots" : "Assign slots"}
              </Button>
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={() =>
                  void handleAutoFillPage().catch((error: unknown) => {
                    toast.error(error instanceof Error ? error.message : "Unable to pin the current page.");
                  })
                }
                disabled={!activePage}
              >
                <Grid2x2 className="h-3.5 w-3.5" />
                Auto fill
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() =>
                  void handleClearPage().catch((error: unknown) => {
                    toast.error(error instanceof Error ? error.message : "Unable to clear the grid.");
                  })
                }
                disabled={!activePage}
              >
                <X className="h-3.5 w-3.5" />
                Clear grid
              </Button>
              <Button type="button" variant="secondary" size="sm" onClick={() => setCategoryDialog("create")}>
                <FolderPlus className="h-3.5 w-3.5" />
                New category
              </Button>
              <Button type="button" variant="secondary" size="sm" onClick={() => setCategoryDialog("edit")} disabled={!currentCategory}>
                <Edit3 className="h-3.5 w-3.5" />
                Edit category
              </Button>
              <Button type="button" variant="secondary" size="sm" onClick={() => setSubcategoryDialog("create")} disabled={!currentCategory}>
                <ListPlus className="h-3.5 w-3.5" />
                New subcategory
              </Button>
              <Button type="button" variant="secondary" size="sm" onClick={() => setSubcategoryDialog("edit")} disabled={!currentSubcategory}>
                <PencilLine className="h-3.5 w-3.5" />
                Edit subcategory
              </Button>
              <Button type="button" variant="secondary" size="sm" onClick={openCreateProduct}>
                <PackagePlus className="h-3.5 w-3.5" />
                New product
              </Button>
              {activePage ? (
                <select
                  className="h-10 rounded-2xl border border-white/10 bg-input/90 px-3 text-sm text-foreground outline-none"
                  value={activePage.rows}
                  onChange={(event) =>
                    void onPatchGridPage(activePage.id, {
                      rows: Number(event.target.value),
                      cols: Number(event.target.value),
                    }).catch((error: unknown) => {
                      toast.error(error instanceof Error ? error.message : "Unable to update grid size.");
                    })
                  }
                >
                  {squareGridOptions.map((size) => (
                    <option key={size} value={size}>
                      {size} x {size}
                    </option>
                  ))}
                </select>
              ) : null}
            </div>
          ) : null}

          <div className="space-y-2">
            <div className="scrollbar-thin flex gap-2 overflow-x-auto pb-1">
              {visibleCategories.map((category) => (
                <Button
                  key={category.id}
                  type="button"
                  variant={category.id === currentCategory?.id ? "default" : "secondary"}
                  className="h-10 shrink-0 rounded-full px-5 text-sm"
                  onClick={() => setActiveCategoryId(category.id)}
                >
                  {category.name}
                </Button>
              ))}
            </div>

            <div className="scrollbar-thin flex gap-2 overflow-x-auto pb-1">
              {categorySubcategories.map((subcategory) => (
                <Button
                  key={subcategory.id}
                  type="button"
                  variant={subcategory.id === currentSubcategory?.id ? "default" : "secondary"}
                  className="h-10 shrink-0 rounded-full px-5 text-sm"
                  onClick={() => setActiveSubcategoryId(subcategory.id)}
                >
                  {subcategory.name}
                </Button>
              ))}
            </div>
          </div>

          {!activeCheckId ? (
            <div className="rounded-[24px] border border-amber-500/30 bg-amber-500/10 px-4 py-3 text-sm text-amber-100">
              Select table first
            </div>
          ) : null}

          {activePage ? (
            <div className="grid min-h-0 flex-1 grid-cols-[minmax(0,1fr)_64px] gap-2.5 sm:grid-cols-[minmax(0,1fr)_74px]">
              <div className="min-h-0 rounded-[28px] border border-white/10 bg-slate-950/25 p-2.5">
                <div
                  className="grid h-full min-h-[420px] gap-2.5"
                  style={{
                    gridTemplateColumns: `repeat(${activePage.cols}, minmax(0, 1fr))`,
                    gridTemplateRows: `repeat(${activePage.rows}, minmax(0, 1fr))`,
                  }}
                >
                  {activeSlots.map((slot) => {
                    const label = slot.labelOverride ?? slot.product?.name ?? "Assign product";
                    const imageUrl = imageUrlForSlot(slot);
                    return (
                      <button
                        key={`${slot.pageId}-${slot.position}`}
                        type="button"
                        className={cn(
                          "group flex min-h-0 flex-col overflow-hidden rounded-[20px] border border-white/10 p-2 text-left transition",
                          slot.product
                            ? "bg-white/[0.04] hover:border-primary/40 hover:bg-white/[0.06]"
                            : "bg-white/[0.02] hover:border-white/20",
                        )}
                        onClick={() => handleProductButtonClick(slot)}
                        disabled={busy || (!editMode && slot.product?.isActive === false)}
                      >
                        <div
                          className="flex h-full min-h-0 flex-col rounded-[16px] border border-white/8 p-2"
                          style={{
                            background: slot.product?.colorCode
                              ? `linear-gradient(180deg, ${slot.product.colorCode}33, rgba(15, 23, 42, 0.72))`
                              : "linear-gradient(180deg, rgba(255,255,255,0.08), rgba(15,23,42,0.55))",
                          }}
                        >
                          {editMode ? (
                            <div className="mb-1 flex items-center justify-between gap-2 text-[10px] uppercase tracking-[0.22em] text-muted-foreground">
                              <span>{slot.product?.externalPlu ?? `Slot ${slot.position + 1}`}</span>
                              <span>{assignMode ? "Assign" : "Edit"}</span>
                            </div>
                          ) : null}
                          <div className="min-h-0 flex-[1.18] overflow-hidden rounded-[14px] bg-slate-950/35">
                            {imageUrl ? (
                              <img src={imageUrl} alt={label} className="h-full w-full object-cover" />
                            ) : (
                              <div className="flex h-full w-full items-center justify-center text-center text-[11px] uppercase tracking-[0.28em] text-muted-foreground">
                                {slot.product ? slot.product.name.slice(0, 2).toUpperCase() : "--"}
                              </div>
                            )}
                          </div>
                          <div className="mt-2 space-y-0.5">
                            <p className="line-clamp-2 text-sm font-semibold leading-tight text-foreground">{label}</p>
                            <p className="text-sm font-medium text-primary/90">
                              {slot.product ? formatCurrency(slot.product.unitGrossCents) : editMode ? "Tap to assign" : "No product"}
                            </p>
                          </div>
                        </div>
                      </button>
                    );
                  })}
                </div>
              </div>

              <div className="flex flex-col gap-2">
                {pagesForSubcategory.map((page) => (
                  <Button
                    key={page.id}
                    type="button"
                    variant={page.id === activePage.id ? "default" : "secondary"}
                    className="h-14 rounded-[18px] px-0 text-base font-semibold"
                    onClick={() => setActivePageBySubcategory((current) => ({ ...current, [page.subcategoryId]: page.id }))}
                  >
                    {page.pageNumber}
                  </Button>
                ))}
              </div>
            </div>
          ) : (
            <div className="rounded-[28px] border border-dashed border-white/10 p-5 text-sm text-muted-foreground">
              Import products or create a category and subcategory to start building the grid.
            </div>
          )}
        </CardContent>
      </Card>

      <Dialog open={assigningSlot !== null} onOpenChange={(open) => !open && setAssigningSlot(null)}>
        <DialogContent className="w-[min(94vw,42rem)]">
          <DialogHeader>
            <DialogTitle>Assign slot</DialogTitle>
            <DialogDescription>Search within the current subcategory and assign a product to this slot.</DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="relative">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                value={slotQuery}
                onChange={(event) => setSlotQuery(event.target.value)}
                className="pl-10"
                placeholder="Search product"
              />
            </div>
            <div className="grid max-h-[420px] gap-3 overflow-y-auto pr-1">
              <Button
                type="button"
                variant="ghost"
                className="justify-start"
                onClick={() =>
                  void handleAssignProduct(null).catch((error: unknown) => {
                    toast.error(error instanceof Error ? error.message : "Unable to clear slot.");
                  })
                }
              >
                <X className="h-4 w-4" />
                Clear slot
              </Button>
              {slotCandidates.map((product) => (
                <button
                  key={product.id}
                  type="button"
                  className="rounded-[24px] border border-white/10 bg-white/[0.03] p-4 text-left transition hover:border-primary/35 hover:bg-white/[0.05]"
                  onClick={() =>
                    void handleAssignProduct(product.id).catch((error: unknown) => {
                      toast.error(error instanceof Error ? error.message : "Unable to assign slot.");
                    })
                  }
                >
                  <div className="flex items-center justify-between gap-3">
                    <div>
                      <p className="font-medium text-foreground">{product.name}</p>
                      <p className="mt-1 text-xs text-muted-foreground">
                        {product.receiptName} · PLU {product.externalPlu}
                      </p>
                    </div>
                    <span className="text-sm text-foreground">{formatCurrency(product.unitGrossCents)}</span>
                  </div>
                </button>
              ))}
            </div>
          </div>
        </DialogContent>
      </Dialog>

      <Dialog open={categoryDialog !== null} onOpenChange={(open) => !open && setCategoryDialog(null)}>
        <DialogContent className="w-[min(94vw,32rem)]">
          <DialogHeader>
            <DialogTitle>{categoryDialog === "create" ? "Create category" : "Edit category"}</DialogTitle>
            <DialogDescription>Categories drive the first row of the touch grid.</DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="category-name">Name</Label>
              <Input id="category-name" value={categoryName} onChange={(event) => setCategoryName(event.target.value)} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="category-color">Color code</Label>
              <Input
                id="category-color"
                value={categoryColorCode}
                onChange={(event) => setCategoryColorCode(event.target.value)}
                placeholder="#39516e"
              />
            </div>
            <div className="flex items-center justify-between rounded-[24px] border border-white/10 bg-white/[0.03] p-3">
              <div>
                <p className="text-sm font-medium text-foreground">Visible on POS</p>
                <p className="text-xs text-muted-foreground">Inactive categories stay editable but do not show in sales mode.</p>
              </div>
              <Button type="button" variant={categoryIsActive ? "default" : "secondary"} size="sm" onClick={() => setCategoryIsActive((current) => !current)}>
                {categoryIsActive ? "Active" : "Inactive"}
              </Button>
            </div>
            <div className="flex justify-between gap-2">
              {categoryDialog === "edit" && currentCategory ? (
                <Button
                  type="button"
                  variant="ghost"
                  onClick={() => {
                    if (window.confirm(`Delete category "${currentCategory.name}"? Products will be moved to a fallback category.`)) {
                      void onDeleteCategory(currentCategory.id)
                        .then(() => {
                          toast.success("Category deleted");
                          setCategoryDialog(null);
                        })
                        .catch((error: unknown) => {
                          toast.error(error instanceof Error ? error.message : "Unable to delete category.");
                        });
                    }
                  }}
                >
                  Delete
                </Button>
              ) : (
                <span />
              )}
              <div className="flex gap-2">
                <Button type="button" variant="ghost" onClick={() => setCategoryDialog(null)}>
                  Cancel
                </Button>
                <Button
                  type="button"
                  onClick={() =>
                    void handleSaveCategory().catch((error: unknown) => {
                      toast.error(error instanceof Error ? error.message : "Unable to save category.");
                    })
                  }
                >
                  Save
                </Button>
              </div>
            </div>
          </div>
        </DialogContent>
      </Dialog>

      <Dialog open={subcategoryDialog !== null} onOpenChange={(open) => !open && setSubcategoryDialog(null)}>
        <DialogContent className="w-[min(94vw,32rem)]">
          <DialogHeader>
            <DialogTitle>{subcategoryDialog === "create" ? "Create subcategory" : "Edit subcategory"}</DialogTitle>
            <DialogDescription>Subcategories drive the second row under the selected main category.</DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="subcategory-name">Name</Label>
              <Input id="subcategory-name" value={subcategoryName} onChange={(event) => setSubcategoryName(event.target.value)} />
            </div>
            <div className="flex items-center justify-between rounded-[24px] border border-white/10 bg-white/[0.03] p-3">
              <div>
                <p className="text-sm font-medium text-foreground">Visible on POS</p>
                <p className="text-xs text-muted-foreground">Inactive subcategories stay editable but do not show in sales mode.</p>
              </div>
              <Button type="button" variant={subcategoryIsActive ? "default" : "secondary"} size="sm" onClick={() => setSubcategoryIsActive((current) => !current)}>
                {subcategoryIsActive ? "Active" : "Inactive"}
              </Button>
            </div>
            <div className="flex justify-between gap-2">
              {subcategoryDialog === "edit" && currentSubcategory ? (
                <Button
                  type="button"
                  variant="ghost"
                  onClick={() => {
                    if (
                      window.confirm(
                        `Delete subcategory "${currentSubcategory.name}"? Products will be moved to a fallback subcategory.`,
                      )
                    ) {
                      void onDeleteSubcategory(currentSubcategory.id)
                        .then(() => {
                          toast.success("Subcategory deleted");
                          setSubcategoryDialog(null);
                        })
                        .catch((error: unknown) => {
                          toast.error(error instanceof Error ? error.message : "Unable to delete subcategory.");
                        });
                    }
                  }}
                >
                  Delete
                </Button>
              ) : (
                <span />
              )}
              <div className="flex gap-2">
                <Button type="button" variant="ghost" onClick={() => setSubcategoryDialog(null)}>
                  Cancel
                </Button>
                <Button
                  type="button"
                  onClick={() =>
                    void handleSaveSubcategory().catch((error: unknown) => {
                      toast.error(error instanceof Error ? error.message : "Unable to save subcategory.");
                    })
                  }
                >
                  Save
                </Button>
              </div>
            </div>
          </div>
        </DialogContent>
      </Dialog>

      <Dialog
        open={productDialogOpen}
        onOpenChange={(open) => {
          if (!open) {
            setProductDialogOpen(false);
            setEditingProductId(null);
          }
        }}
      >
        <DialogContent className="w-[min(96vw,52rem)]">
          <DialogHeader>
            <DialogTitle>{editingProductId ? "Edit product" : "Create product"}</DialogTitle>
            <DialogDescription>Keep product metadata simple: fixed gross price, fixed VAT, optional image, and allergens.</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 md:grid-cols-2">
            <div className="space-y-2">
              <Label htmlFor="product-plu">PLU</Label>
              <Input
                id="product-plu"
                value={productForm.externalPlu}
                onChange={(event) => setProductForm((current) => ({ ...current, externalPlu: event.target.value }))}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="product-barcode">Barcode</Label>
              <Input
                id="product-barcode"
                value={productForm.barcode}
                onChange={(event) => setProductForm((current) => ({ ...current, barcode: event.target.value }))}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="product-name">Name</Label>
              <Input
                id="product-name"
                value={productForm.name}
                onChange={(event) => setProductForm((current) => ({ ...current, name: event.target.value }))}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="product-receipt-name">Receipt name</Label>
              <Input
                id="product-receipt-name"
                value={productForm.receiptName}
                onChange={(event) => setProductForm((current) => ({ ...current, receiptName: event.target.value }))}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="product-category">Category</Label>
              <select
                id="product-category"
                className="h-11 w-full rounded-2xl border border-white/10 bg-input/90 px-3 text-sm text-foreground outline-none"
                value={productForm.categoryId}
                onChange={(event) => {
                  const nextCategoryId = event.target.value;
                  const nextSubcategoryId = resolveProductSubcategoryOptions(nextCategoryId)[0]?.id ?? "";
                  setProductForm((current) => ({
                    ...current,
                    categoryId: nextCategoryId,
                    subcategoryId: nextSubcategoryId,
                  }));
                }}
              >
                {sortCategories(categories.filter((category) => category.isActive)).map((category) => (
                  <option key={category.id} value={category.id}>
                    {category.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="product-subcategory">Subcategory</Label>
              <select
                id="product-subcategory"
                className="h-11 w-full rounded-2xl border border-white/10 bg-input/90 px-3 text-sm text-foreground outline-none"
                value={productForm.subcategoryId}
                onChange={(event) => setProductForm((current) => ({ ...current, subcategoryId: event.target.value }))}
              >
                {resolveProductSubcategoryOptions(productForm.categoryId).map((subcategory) => (
                  <option key={subcategory.id} value={subcategory.id}>
                    {subcategory.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="product-sort-order">Sort order</Label>
              <Input
                id="product-sort-order"
                value={productForm.sortOrder}
                onChange={(event) => setProductForm((current) => ({ ...current, sortOrder: event.target.value }))}
                placeholder="0"
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="product-color">Color code</Label>
              <Input
                id="product-color"
                value={productForm.colorCode}
                onChange={(event) => setProductForm((current) => ({ ...current, colorCode: event.target.value }))}
                placeholder="#5a4433"
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="product-gross-cents">Unit gross cents</Label>
              <Input
                id="product-gross-cents"
                value={productForm.unitGrossCents}
                onChange={(event) => setProductForm((current) => ({ ...current, unitGrossCents: event.target.value }))}
                placeholder="600"
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="product-vat-rate">VAT rate</Label>
              <select
                id="product-vat-rate"
                className="h-11 w-full rounded-2xl border border-white/10 bg-input/90 px-3 text-sm text-foreground outline-none"
                value={productForm.vatRate}
                onChange={(event) => setProductForm((current) => ({ ...current, vatRate: event.target.value as "0.14" | "0.255" }))}
              >
                <option value="0.14">14%</option>
                <option value="0.255">25.5%</option>
              </select>
            </div>
          </div>

          <div className="space-y-3">
            <div className="flex items-center justify-between rounded-[24px] border border-white/10 bg-white/[0.03] p-3">
              <div>
                <p className="text-sm font-medium text-foreground">Product active on POS</p>
                <p className="text-xs text-muted-foreground">Inactive products stay editable but do not sell from the grid.</p>
              </div>
              <Button
                type="button"
                variant={productForm.isActive ? "default" : "secondary"}
                size="sm"
                onClick={() => setProductForm((current) => ({ ...current, isActive: !current.isActive }))}
              >
                {productForm.isActive ? "Active" : "Inactive"}
              </Button>
            </div>

            <div className="space-y-2">
              <Label>Allergens</Label>
              <div className="flex flex-wrap gap-2">
                {allergenOptions.map((code) => {
                  const selected = productForm.allergens.includes(code);
                  return (
                    <Button
                      key={code}
                      type="button"
                      variant={selected ? "default" : "secondary"}
                      size="sm"
                      onClick={() =>
                        setProductForm((current) => ({
                          ...current,
                          allergens: selected
                            ? current.allergens.filter((item) => item !== code)
                            : [...current.allergens, code].sort(),
                        }))
                      }
                    >
                      {code}
                    </Button>
                  );
                })}
              </div>
            </div>

            {editingProduct ? (
              <div className="space-y-2">
                <Label>Image upload</Label>
                <div className="flex flex-wrap items-center gap-3">
                  <label className="inline-flex cursor-pointer items-center gap-2 rounded-full border border-white/10 bg-secondary/80 px-4 py-2 text-sm font-medium text-secondary-foreground transition hover:bg-secondary">
                    <Upload className="h-4 w-4" />
                    Upload image
                    <input
                      type="file"
                      accept="image/*"
                      className="hidden"
                      onChange={(event) => {
                        const file = event.target.files?.[0];
                        if (!file || !editingProduct) {
                          return;
                        }
                        void convertToPng(file)
                          .then((pngFile) => onUploadProductImage(editingProduct.id, pngFile))
                          .then(() => toast.success("Product image updated"))
                          .catch((error: unknown) => {
                            toast.error(error instanceof Error ? error.message : "Unable to upload image.");
                          });
                        event.currentTarget.value = "";
                      }}
                    />
                  </label>
                  {editingProduct.imagePath ? (
                    <img
                      src={edgeAssetUrl(editingProduct.imagePath) ?? undefined}
                      alt={editingProduct.name}
                      className="h-16 w-16 rounded-[18px] object-cover"
                    />
                  ) : (
                    <span className="text-xs text-muted-foreground">No image uploaded yet.</span>
                  )}
                </div>
              </div>
            ) : null}
          </div>

          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={() => setProductDialogOpen(false)}>
              Cancel
            </Button>
            <Button
              type="button"
              onClick={() =>
                void handleSaveProduct().catch((error: unknown) => {
                  toast.error(error instanceof Error ? error.message : "Unable to save product.");
                })
              }
            >
              {editingProductId ? "Save product" : "Create product"}
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </section>
  );
}
