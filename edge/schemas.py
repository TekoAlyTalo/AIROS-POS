from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ItemComponentInput(StrictModel):
    component_id: str | None = None
    name_snapshot: str
    vat_rate_snapshot: float
    unit_gross_cents_snapshot: int = Field(ge=0)
    qty: int = Field(ge=1)


class ItemInput(StrictModel):
    item_id: str | None = None
    product_id: str | None = None
    name_snapshot: str
    qty: int = Field(ge=1)
    note: str | None = None
    pricing_model: Literal["SINGLE_VAT", "COMPOSITE_VAT"]
    components: list[ItemComponentInput]


class PartyOpenRequest(StrictModel):
    guest_count: int = Field(ge=1)
    table_ids: list[str] = Field(min_length=1)
    primary_table_id: str
    note: str | None = None


class PartyMoveRequest(StrictModel):
    table_ids: list[str] = Field(min_length=1)
    primary_table_id: str
    reason: Literal["staff_move", "reservation_change"] = "staff_move"


class ClosePartyRequest(StrictModel):
    reason: Literal["paid_and_left", "no_sale", "force_close"] = "paid_and_left"
    final_status: Literal["FREE", "DIRTY"] = "DIRTY"


class OpenCheckRequest(StrictModel):
    label: str | None = None


class AddItemsRequest(StrictModel):
    items: list[ItemInput] = Field(min_length=1)


class RemoveItemInput(StrictModel):
    item_id: str
    reason: Literal["mistake", "void_line", "customer_changed_mind"]


class RemoveItemsRequest(StrictModel):
    removed: list[RemoveItemInput] = Field(min_length=1)


class SplitCheckRequest(StrictModel):
    moved_item_ids: list[str] = Field(min_length=1)
    target_check_id: str | None = None
    label: str | None = None


class MergeCheckRequest(StrictModel):
    target_check_id: str
    source_check_ids: list[str] = Field(min_length=1)


class VoidCheckRequest(StrictModel):
    reason_code: str
    reason_note: str | None = None


class RecordPaymentRequest(StrictModel):
    method: Literal["CASH", "CARD_EXTERNAL"]
    amount_cents: int = Field(ge=1)
    external_ref: str | None = None


class FinalizeCheckRequest(StrictModel):
    payment_method: Literal["CASH", "CARD_EXTERNAL"]
    issue_receipt: bool = True


class OpenSessionRequest(StrictModel):
    opening_cash_cents: int = Field(ge=0)


class CloseSessionRequest(StrictModel):
    counted_cash_cents: int = Field(ge=0)
    note: str | None = None


class MarkDeliveredRequest(StrictModel):
    event_ids: list[str] = Field(min_length=1)


class ProductPatchRequest(StrictModel):
    name: str | None = None
    receipt_name: str | None = None
    external_plu: str | None = None
    barcode: str | None = None
    category: str | None = None
    category_id: str | None = None
    subcategory_id: str | None = None
    sort_order: int | None = None
    color_code: str | None = None
    unit_gross_cents: int | None = Field(default=None, ge=0)
    vat_rate: float | None = None
    is_active: bool | None = None


class ProductCreateRequest(StrictModel):
    external_plu: str
    name: str
    receipt_name: str
    barcode: str | None = None
    category: str | None = None
    category_id: str | None = None
    subcategory_id: str | None = None
    sort_order: int | None = None
    color_code: str | None = None
    unit_gross_cents: int = Field(ge=0)
    vat_rate: float
    is_active: bool = True
    allergens: list[str] = Field(default_factory=list)


class ProductAllergensReplaceRequest(StrictModel):
    allergens: list[str]


class ProductCategoryCreateRequest(StrictModel):
    name: str
    color_code: str | None = None
    sort_order: int | None = None
    is_active: bool = True


class ProductCategoryPatchRequest(StrictModel):
    name: str | None = None
    color_code: str | None = None
    sort_order: int | None = None
    is_active: bool | None = None


class ProductSubcategoryCreateRequest(StrictModel):
    category_id: str
    name: str
    sort_order: int | None = None
    is_active: bool = True


class ProductSubcategoryPatchRequest(StrictModel):
    category_id: str | None = None
    name: str | None = None
    sort_order: int | None = None
    is_active: bool | None = None


class ProductGridPageCreateRequest(StrictModel):
    category_id: str
    subcategory_id: str
    title: str | None = None
    page_number: int | None = Field(default=None, ge=1)
    rows: int = Field(default=4, ge=2, le=8)
    cols: int = Field(default=4, ge=2, le=8)
    sort_order: int | None = None


class ProductGridPagePatchRequest(StrictModel):
    category_id: str | None = None
    subcategory_id: str | None = None
    title: str | None = None
    page_number: int | None = Field(default=None, ge=1)
    rows: int | None = Field(default=None, ge=2, le=8)
    cols: int | None = Field(default=None, ge=2, le=8)
    sort_order: int | None = None


class ProductGridSlotInput(StrictModel):
    position: int = Field(ge=0)
    product_id: str | None = None
    label_override: str | None = None
    image_override_path: str | None = None


class ProductGridSlotsReplaceRequest(StrictModel):
    slots: list[ProductGridSlotInput]
