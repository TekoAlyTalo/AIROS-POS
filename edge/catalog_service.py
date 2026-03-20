from __future__ import annotations

from datetime import UTC, datetime
from math import ceil
from pathlib import Path
from typing import Any

from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session, joinedload, selectinload

from edge.catalog import (
    ALLOWED_GRID_SIZES,
    BOOTSTRAP_TAXONOMY,
    DEFAULT_CATEGORY_COLOR,
    DEFAULT_GRID_COLS,
    DEFAULT_GRID_ROWS,
    FALLBACK_CATEGORY_NAME,
    FALLBACK_SUBCATEGORY_NAME,
    PRODUCT_IMAGES_DIR,
    ProductImportSummary,
    default_product_sort_order,
    ensure_valid_allergens,
    infer_taxonomy,
    parse_solmio_row,
    product_image_public_path,
    read_solmio_csv_rows,
)
from edge.config import EdgeSettings
from edge.models import (
    Product,
    ProductAllergenLink,
    ProductCategory,
    ProductGridPage,
    ProductGridSlot,
    ProductSubcategory,
)
from edge.service import EdgeError
from shared.ids import new_uuid, stable_uuid


class CatalogService:
    def __init__(self, db: Session, settings: EdgeSettings):
        self.db = db
        self.settings = settings
        self.ensure_catalog_bootstrap()

    def ensure_catalog_bootstrap(self) -> None:
        self._bootstrap_default_taxonomy()
        self._backfill_products()
        self._backfill_grid_pages()
        self.db.flush()

    def list_categories(self, *, active: bool | None = None) -> list[dict[str, Any]]:
        statement = select(ProductCategory).where(ProductCategory.restaurant_key == self.settings.restaurant_key)
        if active is not None:
            statement = statement.where(ProductCategory.is_active.is_(active))
        categories = self.db.scalars(statement.order_by(ProductCategory.sort_order, ProductCategory.name)).all()
        active_counts = self._counts_by_field(Product.category_id, active_only=True)
        all_counts = self._counts_by_field(Product.category_id, active_only=False)
        return [self._serialize_category(category, all_counts, active_counts) for category in categories]

    def create_category(
        self,
        *,
        name: str,
        color_code: str | None = None,
        sort_order: int | None = None,
        is_active: bool = True,
    ) -> dict[str, Any]:
        normalized_name = name.strip()
        if not normalized_name:
            raise EdgeError("name is required")
        existing = self.db.scalar(
            select(ProductCategory).where(
                ProductCategory.restaurant_key == self.settings.restaurant_key,
                ProductCategory.name == normalized_name,
            )
        )
        if existing is not None:
            raise EdgeError("category already exists")
        category = ProductCategory(
            id=new_uuid(),
            restaurant_key=self.settings.restaurant_key,
            name=normalized_name,
            color_code=color_code.strip() if isinstance(color_code, str) and color_code.strip() else None,
            sort_order=sort_order if sort_order is not None else self._next_category_sort_order(),
            is_active=is_active,
        )
        self.db.add(category)
        self.db.flush()
        self._get_or_create_subcategory(category, FALLBACK_SUBCATEGORY_NAME)
        self.db.flush()
        return self._serialize_category(category)

    def update_category(self, category_id: str, **changes: Any) -> dict[str, Any]:
        category = self._require_category(category_id)
        if "name" in changes and changes["name"] is not None:
            next_name = changes["name"].strip()
            if not next_name:
                raise EdgeError("name is required")
            duplicate = self.db.scalar(
                select(ProductCategory).where(
                    ProductCategory.restaurant_key == self.settings.restaurant_key,
                    ProductCategory.name == next_name,
                    ProductCategory.id != category.id,
                )
            )
            if duplicate is not None:
                raise EdgeError("category already exists")
            category.name = next_name
            for product in category.products:
                product.category = next_name
            for page in category.grid_pages:
                page.category = next_name
        if "color_code" in changes:
            color_code = changes["color_code"]
            category.color_code = color_code.strip() if isinstance(color_code, str) and color_code.strip() else None
        if "sort_order" in changes and changes["sort_order"] is not None:
            category.sort_order = int(changes["sort_order"])
        if "is_active" in changes and changes["is_active"] is not None:
            category.is_active = bool(changes["is_active"])
        category.updated_at = self._now()
        self.db.flush()
        return self._serialize_category(category)

    def delete_category(self, category_id: str) -> dict[str, Any]:
        category = self._require_category(category_id)
        fallback_category = self._find_reassignment_category(excluding_category_id=category.id)
        fallback_subcategory = self._fallback_subcategory_for_category(fallback_category)
        for product in list(category.products):
            product.category_id = fallback_category.id
            product.subcategory_id = fallback_subcategory.id
            product.category = fallback_category.name
            product.updated_at = self._now()
        for subcategory in list(category.subcategories):
            self._delete_grid_pages_for_subcategory(subcategory.id)
        self._delete_grid_pages_for_category(category.id)
        self.db.delete(category)
        self.db.flush()
        return {"deleted": True, "reassigned_category_id": fallback_category.id}

    def list_subcategories(
        self,
        *,
        category_id: str | None = None,
        active: bool | None = None,
    ) -> list[dict[str, Any]]:
        statement = (
            select(ProductSubcategory)
            .where(ProductSubcategory.restaurant_key == self.settings.restaurant_key)
            .options(joinedload(ProductSubcategory.category))
        )
        if category_id:
            statement = statement.where(ProductSubcategory.category_id == category_id)
        if active is not None:
            statement = statement.where(ProductSubcategory.is_active.is_(active))
        subcategories = self.db.scalars(
            statement.order_by(ProductSubcategory.category_id, ProductSubcategory.sort_order, ProductSubcategory.name)
        ).all()
        active_counts = self._counts_by_field(Product.subcategory_id, active_only=True)
        all_counts = self._counts_by_field(Product.subcategory_id, active_only=False)
        return [self._serialize_subcategory(subcategory, all_counts, active_counts) for subcategory in subcategories]

    def create_subcategory(
        self,
        *,
        category_id: str,
        name: str,
        sort_order: int | None = None,
        is_active: bool = True,
    ) -> dict[str, Any]:
        category = self._require_category(category_id)
        normalized_name = name.strip()
        if not normalized_name:
            raise EdgeError("name is required")
        existing = self.db.scalar(
            select(ProductSubcategory).where(
                ProductSubcategory.restaurant_key == self.settings.restaurant_key,
                ProductSubcategory.category_id == category.id,
                ProductSubcategory.name == normalized_name,
            )
        )
        if existing is not None:
            raise EdgeError("subcategory already exists")
        subcategory = ProductSubcategory(
            id=new_uuid(),
            restaurant_key=self.settings.restaurant_key,
            category_id=category.id,
            name=normalized_name,
            sort_order=sort_order if sort_order is not None else self._next_subcategory_sort_order(category.id),
            is_active=is_active,
        )
        self.db.add(subcategory)
        self.db.flush()
        self._ensure_page_set(subcategory.id)
        self.db.flush()
        return self._serialize_subcategory(subcategory)

    def update_subcategory(self, subcategory_id: str, **changes: Any) -> dict[str, Any]:
        subcategory = self._require_subcategory(subcategory_id)
        if "category_id" in changes and changes["category_id"] is not None and changes["category_id"] != subcategory.category_id:
            next_category = self._require_category(changes["category_id"])
            duplicate = self.db.scalar(
                select(ProductSubcategory).where(
                    ProductSubcategory.restaurant_key == self.settings.restaurant_key,
                    ProductSubcategory.category_id == next_category.id,
                    ProductSubcategory.name == subcategory.name,
                    ProductSubcategory.id != subcategory.id,
                )
            )
            if duplicate is not None:
                raise EdgeError("subcategory already exists in category")
            subcategory.category_id = next_category.id
            for product in subcategory.products:
                product.category_id = next_category.id
                product.category = next_category.name
            for page in subcategory.grid_pages:
                page.category_id = next_category.id
                page.category = next_category.name
        if "name" in changes and changes["name"] is not None:
            next_name = changes["name"].strip()
            if not next_name:
                raise EdgeError("name is required")
            duplicate = self.db.scalar(
                select(ProductSubcategory).where(
                    ProductSubcategory.restaurant_key == self.settings.restaurant_key,
                    ProductSubcategory.category_id == subcategory.category_id,
                    ProductSubcategory.name == next_name,
                    ProductSubcategory.id != subcategory.id,
                )
            )
            if duplicate is not None:
                raise EdgeError("subcategory already exists in category")
            subcategory.name = next_name
            for page in subcategory.grid_pages:
                page.title = next_name
        if "sort_order" in changes and changes["sort_order"] is not None:
            subcategory.sort_order = int(changes["sort_order"])
        if "is_active" in changes and changes["is_active"] is not None:
            subcategory.is_active = bool(changes["is_active"])
        subcategory.updated_at = self._now()
        self.db.flush()
        return self._serialize_subcategory(subcategory)

    def delete_subcategory(self, subcategory_id: str) -> dict[str, Any]:
        subcategory = self._require_subcategory(subcategory_id)
        category = self._require_category(subcategory.category_id)
        fallback_subcategory = self._find_reassignment_subcategory(category, excluding_subcategory_id=subcategory.id)
        for product in list(subcategory.products):
            product.subcategory_id = fallback_subcategory.id
            product.category_id = category.id
            product.category = category.name
            product.updated_at = self._now()
        self._delete_grid_pages_for_subcategory(subcategory.id)
        self.db.delete(subcategory)
        self.db.flush()
        return {"deleted": True, "reassigned_subcategory_id": fallback_subcategory.id}

    def list_products(
        self,
        *,
        category_id: str | None = None,
        subcategory_id: str | None = None,
        category: str | None = None,
        q: str | None = None,
        active: bool | None = None,
    ) -> list[dict[str, Any]]:
        statement = (
            select(Product)
            .where(Product.restaurant_key == self.settings.restaurant_key)
            .options(
                joinedload(Product.category_ref),
                joinedload(Product.subcategory_ref),
                selectinload(Product.allergens),
            )
        )
        if category_id:
            statement = statement.where(Product.category_id == category_id)
        elif category:
            statement = statement.where(Product.category == category.strip())
        if subcategory_id:
            statement = statement.where(Product.subcategory_id == subcategory_id)
        if active is not None:
            statement = statement.where(Product.is_active.is_(active))
        if q:
            pattern = f"%{q.strip().lower()}%"
            statement = statement.where(
                or_(
                    func.lower(Product.name).like(pattern),
                    func.lower(Product.receipt_name).like(pattern),
                    func.lower(Product.external_plu).like(pattern),
                    func.lower(func.coalesce(Product.barcode, "")).like(pattern),
                )
            )
        products = self.db.scalars(
            statement.order_by(Product.category, Product.subcategory_id, Product.sort_order, Product.name, Product.external_plu)
        ).all()
        return [self._serialize_product(product) for product in products]

    def list_product_groups(self) -> list[str]:
        return [item["name"] for item in self.list_categories(active=True) if item["active_product_count"] > 0]

    def get_product(self, product_id: str) -> dict[str, Any]:
        return self._serialize_product(self._require_product(product_id))

    def create_product(self, **payload: Any) -> dict[str, Any]:
        external_plu = self._normalize_required_string(payload.get("external_plu"), "external_plu")
        if self.db.scalar(
            select(Product.id).where(
                Product.restaurant_key == self.settings.restaurant_key,
                Product.external_plu == external_plu,
            )
        ):
            raise EdgeError("external_plu already exists")
        category, subcategory = self._resolve_taxonomy_from_payload(
            category_id=payload.get("category_id"),
            subcategory_id=payload.get("subcategory_id"),
            category_name=payload.get("category"),
        )
        product = Product(
            id=new_uuid(),
            restaurant_key=self.settings.restaurant_key,
            external_plu=external_plu,
            name=self._normalize_required_string(payload.get("name"), "name"),
            receipt_name=self._normalize_required_string(payload.get("receipt_name"), "receipt_name"),
            barcode=self._normalize_optional_string(payload.get("barcode")),
            category=category.name,
            category_id=category.id,
            subcategory_id=subcategory.id,
            sort_order=self._resolve_product_sort_order(payload.get("sort_order"), external_plu, subcategory.id),
            color_code=self._normalize_optional_string(payload.get("color_code")),
            unit_gross_cents=self._validate_unit_gross_cents(payload.get("unit_gross_cents")),
            vat_rate=self._validate_vat_rate(payload.get("vat_rate")),
            image_path=None,
            is_active=bool(payload.get("is_active", True)),
        )
        self.db.add(product)
        self.db.flush()
        self.replace_product_allergens(product.id, payload.get("allergens") or [])
        self.db.flush()
        self._ensure_page_set(subcategory.id)
        self.db.flush()
        return self.get_product(product.id)

    def update_product(self, product_id: str, **changes: Any) -> dict[str, Any]:
        product = self._require_product(product_id)

        if "external_plu" in changes and changes["external_plu"] is not None:
            external_plu = self._normalize_required_string(changes["external_plu"], "external_plu")
            duplicate = self.db.scalar(
                select(Product.id).where(
                    Product.restaurant_key == self.settings.restaurant_key,
                    Product.external_plu == external_plu,
                    Product.id != product.id,
                )
            )
            if duplicate:
                raise EdgeError("external_plu already exists")
            product.external_plu = external_plu

        category_name = changes.get("category")
        category_id = changes.get("category_id")
        subcategory_id = changes.get("subcategory_id")
        if category_name is not None or category_id is not None or subcategory_id is not None:
            category, subcategory = self._resolve_taxonomy_from_payload(
                category_id=category_id if category_id is not None else product.category_id,
                subcategory_id=subcategory_id if subcategory_id is not None else product.subcategory_id,
                category_name=category_name,
            )
            product.category = category.name
            product.category_id = category.id
            product.subcategory_id = subcategory.id

        if "name" in changes and changes["name"] is not None:
            product.name = self._normalize_required_string(changes["name"], "name")
        if "receipt_name" in changes and changes["receipt_name"] is not None:
            product.receipt_name = self._normalize_required_string(changes["receipt_name"], "receipt_name")
        if "barcode" in changes:
            product.barcode = self._normalize_optional_string(changes["barcode"])
        if "color_code" in changes:
            product.color_code = self._normalize_optional_string(changes["color_code"])
        if "sort_order" in changes and changes["sort_order"] is not None:
            product.sort_order = int(changes["sort_order"])
        if "unit_gross_cents" in changes and changes["unit_gross_cents"] is not None:
            product.unit_gross_cents = self._validate_unit_gross_cents(changes["unit_gross_cents"])
        if "vat_rate" in changes and changes["vat_rate"] is not None:
            product.vat_rate = self._validate_vat_rate(changes["vat_rate"])
        if "is_active" in changes and changes["is_active"] is not None:
            product.is_active = bool(changes["is_active"])
        product.updated_at = self._now()
        self.db.flush()
        if product.subcategory_id:
            self._ensure_page_set(product.subcategory_id)
            self.db.flush()
        return self.get_product(product.id)

    def replace_product_allergens(self, product_id: str, allergens: list[str]) -> dict[str, Any]:
        product = self._require_product(product_id)
        try:
            normalized_allergens = ensure_valid_allergens([code.strip() for code in allergens])
        except ValueError as exc:
            raise EdgeError(str(exc)) from exc

        existing_codes = {link.allergen_code for link in product.allergens}
        for link in list(product.allergens):
            if link.allergen_code not in normalized_allergens:
                product.allergens.remove(link)
                self.db.delete(link)
        for code in normalized_allergens:
            if code not in existing_codes:
                link = ProductAllergenLink(product_id=product.id, allergen_code=code)
                self.db.add(link)
                product.allergens.append(link)
        product.updated_at = self._now()
        self.db.flush()
        self.db.expire(product, ["allergens"])
        return self.get_product(product.id)

    def import_solmio_products(self, raw_bytes: bytes) -> dict[str, Any]:
        summary = ProductImportSummary()
        touched_subcategories: set[str] = set()

        for row_number, raw_row in enumerate(read_solmio_csv_rows(raw_bytes), start=2):
            summary.total_rows += 1
            if not any((value or "").strip() for value in raw_row.values()):
                summary.skipped += 1
                continue
            try:
                row = parse_solmio_row(raw_row, row_number=row_number)
            except ValueError as exc:
                summary.add_failure(row_number, str(exc))
                continue

            category = self._get_or_create_category(row.category_name)
            subcategory = self._get_or_create_subcategory(category, row.subcategory_name)
            product = self.db.scalar(
                select(Product).where(
                    Product.restaurant_key == self.settings.restaurant_key,
                    Product.external_plu == row.external_plu,
                )
            )
            values = {
                "name": row.name,
                "receipt_name": row.receipt_name,
                "barcode": row.barcode,
                "category": category.name,
                "category_id": category.id,
                "subcategory_id": subcategory.id,
                "sort_order": row.sort_order,
                "color_code": row.color_code,
                "unit_gross_cents": row.unit_gross_cents,
                "vat_rate": row.vat_rate,
            }
            if product is None:
                self.db.add(
                    Product(
                        id=stable_uuid(f"product:{self.settings.restaurant_key}:{row.external_plu}"),
                        restaurant_key=self.settings.restaurant_key,
                        external_plu=row.external_plu,
                        is_active=True,
                        **values,
                    )
                )
                summary.created += 1
            else:
                changed = False
                for field, value in values.items():
                    if getattr(product, field) != value:
                        setattr(product, field, value)
                        changed = True
                if changed:
                    product.updated_at = self._now()
                    summary.updated += 1
                else:
                    summary.skipped += 1
            touched_subcategories.add(subcategory.id)

        self.db.flush()
        for subcategory_id in touched_subcategories:
            self._ensure_page_set(subcategory_id)
        self.db.flush()
        return summary.to_dict()

    def import_solmio_products_from_path(self, path: Path) -> dict[str, Any]:
        if not path.exists():
            raise EdgeError(f"CSV file not found: {path}")
        return self.import_solmio_products(path.read_bytes())

    def list_grid_pages(
        self,
        *,
        category_id: str | None = None,
        subcategory_id: str | None = None,
    ) -> list[dict[str, Any]]:
        page_sets = self._build_relevant_page_sets(category_id=category_id, subcategory_id=subcategory_id)
        serialized: list[dict[str, Any]] = []
        for page_set in page_sets.values():
            effective_slots = page_set["effective_slots"]
            visible_pages = page_set["visible_pages"]
            total_pages = len(visible_pages)
            for page in visible_pages:
                filled_slots_count = sum(1 for slot in effective_slots[page.id] if slot["product"] is not None)
                serialized.append(self._serialize_page(page, filled_slots_count=filled_slots_count, total_pages=total_pages))
        serialized.sort(
            key=lambda item: (
                item.get("category_sort_order", 0),
                item.get("subcategory_sort_order", 0),
                item["page_number"],
            )
        )
        return serialized

    def create_grid_page(
        self,
        *,
        category_id: str,
        subcategory_id: str,
        rows: int,
        cols: int,
        page_number: int | None = None,
        sort_order: int | None = None,
        title: str | None = None,
    ) -> dict[str, Any]:
        self._validate_grid_size(rows, cols)
        category = self._require_category(category_id)
        subcategory = self._require_subcategory(subcategory_id)
        if subcategory.category_id != category.id:
            raise EdgeError("subcategory does not belong to category")
        existing_pages = self._load_pages_for_subcategory(subcategory.id)
        next_page_number = page_number if page_number is not None else (existing_pages[-1].page_number + 1 if existing_pages else 1)
        if next_page_number < 1:
            raise EdgeError("page_number must be positive")
        duplicate = self.db.scalar(
            select(ProductGridPage).where(
                ProductGridPage.restaurant_key == self.settings.restaurant_key,
                ProductGridPage.subcategory_id == subcategory.id,
                ProductGridPage.page_number == next_page_number,
            )
        )
        if duplicate is not None:
            raise EdgeError("page_number already exists for subcategory")
        if existing_pages:
            self._update_page_set_dimensions(subcategory.id, rows=rows, cols=cols)
        page = self._create_page_record(
            category=category,
            subcategory=subcategory,
            page_number=next_page_number,
            rows=rows,
            cols=cols,
            sort_order=sort_order if sort_order is not None else next_page_number,
            title=title or subcategory.name,
        )
        self.db.flush()
        return self._serialize_page(page, filled_slots_count=0, total_pages=len(existing_pages) + 1)

    def update_grid_page(self, page_id: str, **changes: Any) -> dict[str, Any]:
        page = self._require_page(page_id)
        next_rows = int(changes.get("rows", page.rows))
        next_cols = int(changes.get("cols", page.cols))
        self._validate_grid_size(next_rows, next_cols)

        if "category_id" in changes and changes["category_id"] is not None:
            category = self._require_category(changes["category_id"])
            page.category_id = category.id
            page.category = category.name
        if "subcategory_id" in changes and changes["subcategory_id"] is not None:
            subcategory = self._require_subcategory(changes["subcategory_id"])
            page.subcategory_id = subcategory.id
            page.title = subcategory.name
            page.category_id = subcategory.category_id
            page.category = self._require_category(subcategory.category_id).name
        if "page_number" in changes and changes["page_number"] is not None and int(changes["page_number"]) != page.page_number:
            next_page_number = int(changes["page_number"])
            if next_page_number < 1:
                raise EdgeError("page_number must be positive")
            duplicate = self.db.scalar(
                select(ProductGridPage).where(
                    ProductGridPage.restaurant_key == self.settings.restaurant_key,
                    ProductGridPage.subcategory_id == page.subcategory_id,
                    ProductGridPage.page_number == next_page_number,
                    ProductGridPage.id != page.id,
                )
            )
            if duplicate is not None:
                raise EdgeError("page_number already exists for subcategory")
            page.page_number = next_page_number
        if "title" in changes and changes["title"] is not None:
            next_title = changes["title"].strip()
            if not next_title:
                raise EdgeError("title is required")
            page.title = next_title
        if "sort_order" in changes and changes["sort_order"] is not None:
            page.sort_order = int(changes["sort_order"])
        if next_rows != page.rows or next_cols != page.cols:
            self._update_page_set_dimensions(page.subcategory_id, rows=next_rows, cols=next_cols)
        page.updated_at = self._now()
        self.db.flush()
        page_set = self._build_page_set(page.subcategory_id)
        effective_slots = page_set["effective_slots"][page.id]
        return self._serialize_page(
            self._require_page(page.id),
            filled_slots_count=sum(1 for slot in effective_slots if slot["product"] is not None),
            total_pages=len(page_set["visible_pages"]),
        )

    def get_grid_slots(self, page_id: str) -> list[dict[str, Any]]:
        page = self._require_page(page_id)
        page_set = self._build_page_set(page.subcategory_id)
        return page_set["effective_slots"][page.id]

    def replace_grid_slots(self, page_id: str, slots: list[dict[str, Any]]) -> list[dict[str, Any]]:
        page = self._require_page(page_id)
        self._ensure_slot_count(page)
        total_slots = page.rows * page.cols
        seen_positions: set[int] = set()
        requested_product_ids: list[str] = []
        payload_by_position: dict[int, dict[str, Any]] = {}

        for payload in slots:
            position = int(payload["position"])
            if position < 0 or position >= total_slots:
                raise EdgeError("slot position is out of range")
            if position in seen_positions:
                raise EdgeError("slot position appears multiple times")
            seen_positions.add(position)
            product_id = payload.get("product_id")
            if product_id is not None:
                requested_product_ids.append(product_id)
                self._require_product(product_id)
            payload_by_position[position] = payload

        if len(set(requested_product_ids)) != len(requested_product_ids):
            raise EdgeError("product assigned more than once in payload")

        pages = self._load_pages_for_subcategory(page.subcategory_id, with_slots=True)
        for current_page in pages:
            self._ensure_slot_count(current_page)

        for current_page in pages:
            for slot in current_page.slots:
                if slot.product_id in requested_product_ids:
                    slot.product_id = None
                    slot.label_override = None
                    slot.image_override_path = None

        slots_by_position = {slot.position: slot for slot in page.slots}
        for position in range(total_slots):
            slot = slots_by_position[position]
            slot.product_id = None
            slot.label_override = None
            slot.image_override_path = None

        for position, payload in payload_by_position.items():
            slot = slots_by_position[position]
            slot.product_id = payload.get("product_id")
            slot.label_override = self._normalize_optional_string(payload.get("label_override"))
            slot.image_override_path = self._normalize_optional_string(payload.get("image_override_path"))

        page.updated_at = self._now()
        self.db.flush()
        return self.get_grid_slots(page_id)

    def upload_product_image(self, product_id: str, raw_bytes: bytes) -> dict[str, Any]:
        if not raw_bytes:
            raise EdgeError("image file is empty")
        product = self._require_product(product_id)
        PRODUCT_IMAGES_DIR.mkdir(parents=True, exist_ok=True)
        image_path = PRODUCT_IMAGES_DIR / f"{product.id}.png"
        image_path.write_bytes(raw_bytes)
        product.image_path = product_image_public_path(product.id)
        product.updated_at = self._now()
        self.db.flush()
        return self.get_product(product.id)

    def _bootstrap_default_taxonomy(self) -> None:
        for index, definition in enumerate(BOOTSTRAP_TAXONOMY):
            category = self._get_or_create_category(
                definition["name"],
                color_code=definition.get("color_code"),
                sort_order=index * 100,
            )
            for sub_index, sub_name in enumerate(definition["subcategories"]):
                self._get_or_create_subcategory(category, sub_name, sort_order=sub_index * 100)

    def _backfill_products(self) -> None:
        products = self.db.scalars(
            select(Product)
            .where(Product.restaurant_key == self.settings.restaurant_key)
            .options(joinedload(Product.category_ref), joinedload(Product.subcategory_ref))
        ).all()
        for product in products:
            if product.category_id and product.subcategory_id:
                continue
            category_name, subcategory_name = infer_taxonomy(
                product_groups=product.category,
                name=product.name,
                receipt_name=product.receipt_name,
            )
            category = self._get_or_create_category(category_name)
            subcategory = self._get_or_create_subcategory(category, subcategory_name)
            product.category = category.name
            product.category_id = category.id
            product.subcategory_id = subcategory.id
            if product.sort_order == 0:
                product.sort_order = default_product_sort_order(product.external_plu, 0)
            product.updated_at = self._now()

    def _backfill_grid_pages(self) -> None:
        pages = self.db.scalars(
            select(ProductGridPage).where(ProductGridPage.restaurant_key == self.settings.restaurant_key)
        ).all()
        for page in pages:
            category_name, subcategory_name = infer_taxonomy(
                product_groups=",".join(part for part in [page.category, page.title] if part),
                name=page.title or page.category,
                receipt_name=page.title or page.category,
            )
            category = self._get_or_create_category(category_name)
            subcategory = self._get_or_create_subcategory(category, subcategory_name)
            page.category = category.name
            page.category_id = category.id
            page.subcategory_id = subcategory.id
            page.page_number = page.page_number or max(1, page.sort_order + 1)
            page.title = subcategory.name
            self._ensure_slot_count(page)

    def _build_relevant_page_sets(
        self,
        *,
        category_id: str | None = None,
        subcategory_id: str | None = None,
    ) -> dict[str, dict[str, Any]]:
        relevant_subcategory_ids: set[str] = set()
        if subcategory_id:
            self._require_subcategory(subcategory_id)
            relevant_subcategory_ids.add(subcategory_id)
        else:
            product_statement = select(Product.subcategory_id).where(
                Product.restaurant_key == self.settings.restaurant_key,
                Product.subcategory_id.is_not(None),
            )
            page_statement = select(ProductGridPage.subcategory_id).where(
                ProductGridPage.restaurant_key == self.settings.restaurant_key,
                ProductGridPage.subcategory_id.is_not(None),
            )
            if category_id:
                product_statement = product_statement.where(Product.category_id == category_id)
                page_statement = page_statement.where(ProductGridPage.category_id == category_id)
            relevant_subcategory_ids.update(value for value in self.db.scalars(product_statement).all() if value)
            relevant_subcategory_ids.update(value for value in self.db.scalars(page_statement).all() if value)

        page_sets: dict[str, dict[str, Any]] = {}
        for current_subcategory_id in sorted(relevant_subcategory_ids):
            page_sets[current_subcategory_id] = self._build_page_set(current_subcategory_id)
        return page_sets

    def _build_page_set(self, subcategory_id: str) -> dict[str, Any]:
        subcategory = self._require_subcategory(subcategory_id)
        category = self._require_category(subcategory.category_id)
        pages = self._ensure_page_set(subcategory.id)
        manual_product_ids: set[str] = set()
        products = self._load_products_for_subcategory(subcategory.id)
        product_map = {product.id: product for product in products}

        for page in pages:
            for slot in page.slots:
                if slot.product_id and slot.product_id in product_map:
                    manual_product_ids.add(slot.product_id)

        remaining_products = [product for product in products if product.id not in manual_product_ids]
        remaining_index = 0
        effective_slots: dict[str, list[dict[str, Any]]] = {}

        for page in pages:
            slots_for_page: list[dict[str, Any]] = []
            slots_by_position = {slot.position: slot for slot in page.slots}
            total_slots = page.rows * page.cols
            for position in range(total_slots):
                slot = slots_by_position[position]
                manual_product = product_map.get(slot.product_id) if slot.product_id else None
                if manual_product is not None:
                    product = manual_product
                    is_manual = True
                elif remaining_index < len(remaining_products):
                    product = remaining_products[remaining_index]
                    remaining_index += 1
                    is_manual = False
                else:
                    product = None
                    is_manual = False
                slots_for_page.append(self._serialize_slot(slot, product=product, is_manual=is_manual))
            effective_slots[page.id] = slots_for_page

        required_pages = self._required_page_count(len(products), pages[0].rows * pages[0].cols if pages else DEFAULT_GRID_ROWS * DEFAULT_GRID_COLS)
        visible_pages = [page for page in pages if page.page_number <= required_pages or self._page_has_manual_content(page)]
        if not visible_pages and pages:
            visible_pages = [pages[0]]

        return {
            "category": category,
            "subcategory": subcategory,
            "pages": pages,
            "visible_pages": visible_pages,
            "effective_slots": effective_slots,
        }

    def _ensure_page_set(self, subcategory_id: str) -> list[ProductGridPage]:
        subcategory = self._require_subcategory(subcategory_id)
        category = self._require_category(subcategory.category_id)
        pages = self._load_pages_for_subcategory(subcategory.id, with_slots=True)
        if not pages:
            page = self._create_page_record(
                category=category,
                subcategory=subcategory,
                page_number=1,
                rows=DEFAULT_GRID_ROWS,
                cols=DEFAULT_GRID_COLS,
                sort_order=1,
                title=subcategory.name,
            )
            self.db.flush()
            pages = [page]

        default_rows = pages[0].rows
        default_cols = pages[0].cols
        required_pages = self._required_page_count(
            len(self._load_products_for_subcategory(subcategory.id)),
            default_rows * default_cols,
        )
        pages_by_number = {page.page_number: page for page in pages}
        for number in range(1, required_pages + 1):
            page = pages_by_number.get(number)
            if page is None:
                page = self._create_page_record(
                    category=category,
                    subcategory=subcategory,
                    page_number=number,
                    rows=default_rows,
                    cols=default_cols,
                    sort_order=number,
                    title=subcategory.name,
                )
                pages_by_number[number] = page
                self.db.flush()
            else:
                page.category = category.name
                page.category_id = category.id
                page.subcategory_id = subcategory.id
                page.title = subcategory.name
            self._ensure_slot_count(page)

        pages = self._load_pages_for_subcategory(subcategory.id, with_slots=True)
        return pages

    def _load_pages_for_subcategory(self, subcategory_id: str, *, with_slots: bool = False) -> list[ProductGridPage]:
        statement = (
            select(ProductGridPage)
            .where(
                ProductGridPage.restaurant_key == self.settings.restaurant_key,
                ProductGridPage.subcategory_id == subcategory_id,
            )
            .order_by(ProductGridPage.page_number, ProductGridPage.sort_order, ProductGridPage.id)
        )
        if with_slots:
            statement = statement.options(selectinload(ProductGridPage.slots))
        return self.db.scalars(statement).all()

    def _load_products_for_subcategory(self, subcategory_id: str) -> list[Product]:
        return self.db.scalars(
            select(Product)
            .where(
                Product.restaurant_key == self.settings.restaurant_key,
                Product.subcategory_id == subcategory_id,
                Product.is_active.is_(True),
            )
            .options(selectinload(Product.allergens), joinedload(Product.category_ref), joinedload(Product.subcategory_ref))
            .order_by(Product.sort_order, Product.name, Product.external_plu)
        ).all()

    def _required_page_count(self, product_count: int, capacity: int) -> int:
        normalized_capacity = max(1, capacity)
        return max(1, ceil(max(product_count, 1) / normalized_capacity))

    def _update_page_set_dimensions(self, subcategory_id: str | None, *, rows: int, cols: int) -> None:
        if subcategory_id is None:
            raise EdgeError("subcategory is required")
        self._validate_grid_size(rows, cols)
        pages = self._load_pages_for_subcategory(subcategory_id, with_slots=True)
        for page in pages:
            page.rows = rows
            page.cols = cols
            page.updated_at = self._now()
            self._ensure_slot_count(page)

    def _create_page_record(
        self,
        *,
        category: ProductCategory,
        subcategory: ProductSubcategory,
        page_number: int,
        rows: int,
        cols: int,
        sort_order: int,
        title: str,
    ) -> ProductGridPage:
        page = ProductGridPage(
            id=stable_uuid(f"product-grid-page:{self.settings.restaurant_key}:{subcategory.id}:{page_number}"),
            restaurant_key=self.settings.restaurant_key,
            category=category.name,
            category_id=category.id,
            subcategory_id=subcategory.id,
            page_number=page_number,
            title=title,
            rows=rows,
            cols=cols,
            sort_order=sort_order,
        )
        self.db.add(page)
        self.db.flush()
        self._ensure_slot_count(page)
        return page

    def _counts_by_field(self, field, *, active_only: bool) -> dict[str, int]:
        statement = select(field, func.count(Product.id)).where(Product.restaurant_key == self.settings.restaurant_key)
        if active_only:
            statement = statement.where(Product.is_active.is_(True))
        statement = statement.where(field.is_not(None)).group_by(field)
        return {row[0]: int(row[1]) for row in self.db.execute(statement).all()}

    def _delete_grid_pages_for_category(self, category_id: str) -> None:
        pages = self.db.scalars(
            select(ProductGridPage)
            .where(
                ProductGridPage.restaurant_key == self.settings.restaurant_key,
                ProductGridPage.category_id == category_id,
            )
            .options(selectinload(ProductGridPage.slots))
        ).all()
        for page in pages:
            self.db.delete(page)

    def _delete_grid_pages_for_subcategory(self, subcategory_id: str) -> None:
        pages = self.db.scalars(
            select(ProductGridPage)
            .where(
                ProductGridPage.restaurant_key == self.settings.restaurant_key,
                ProductGridPage.subcategory_id == subcategory_id,
            )
            .options(selectinload(ProductGridPage.slots))
        ).all()
        for page in pages:
            self.db.delete(page)

    @staticmethod
    def _page_has_manual_content(page: ProductGridPage) -> bool:
        return any(slot.product_id for slot in page.slots)

    def _get_or_create_category(
        self,
        name: str,
        *,
        color_code: str | None = None,
        sort_order: int | None = None,
    ) -> ProductCategory:
        normalized_name = name.strip()
        existing = self.db.scalar(
            select(ProductCategory).where(
                ProductCategory.restaurant_key == self.settings.restaurant_key,
                ProductCategory.name == normalized_name,
            )
        )
        if existing is not None:
            if color_code and not existing.color_code:
                existing.color_code = color_code
            if sort_order is not None and existing.sort_order == 0:
                existing.sort_order = sort_order
            return existing
        category = ProductCategory(
            id=stable_uuid(f"product-category:{self.settings.restaurant_key}:{normalized_name.lower()}"),
            restaurant_key=self.settings.restaurant_key,
            name=normalized_name,
            color_code=color_code or self._default_category_color(normalized_name),
            sort_order=sort_order if sort_order is not None else self._default_category_sort_order(normalized_name),
            is_active=True,
        )
        self.db.add(category)
        self.db.flush()
        return category

    def _get_or_create_subcategory(
        self,
        category: ProductCategory,
        name: str,
        *,
        sort_order: int | None = None,
    ) -> ProductSubcategory:
        normalized_name = name.strip()
        existing = self.db.scalar(
            select(ProductSubcategory).where(
                ProductSubcategory.restaurant_key == self.settings.restaurant_key,
                ProductSubcategory.category_id == category.id,
                ProductSubcategory.name == normalized_name,
            )
        )
        if existing is not None:
            if sort_order is not None and existing.sort_order == 0:
                existing.sort_order = sort_order
            return existing
        subcategory = ProductSubcategory(
            id=stable_uuid(f"product-subcategory:{self.settings.restaurant_key}:{category.id}:{normalized_name.lower()}"),
            restaurant_key=self.settings.restaurant_key,
            category_id=category.id,
            name=normalized_name,
            sort_order=sort_order if sort_order is not None else self._default_subcategory_sort_order(category.name, normalized_name),
            is_active=True,
        )
        self.db.add(subcategory)
        self.db.flush()
        return subcategory

    def _require_product(self, product_id: str) -> Product:
        product = self.db.scalar(
            select(Product)
            .where(Product.id == product_id, Product.restaurant_key == self.settings.restaurant_key)
            .options(
                joinedload(Product.category_ref),
                joinedload(Product.subcategory_ref),
                selectinload(Product.allergens),
            )
        )
        if product is None:
            raise EdgeError("product not found")
        return product

    def _require_category(self, category_id: str) -> ProductCategory:
        category = self.db.scalar(
            select(ProductCategory)
            .where(ProductCategory.id == category_id, ProductCategory.restaurant_key == self.settings.restaurant_key)
            .options(
                selectinload(ProductCategory.products),
                selectinload(ProductCategory.subcategories).selectinload(ProductSubcategory.products),
                selectinload(ProductCategory.grid_pages),
            )
        )
        if category is None:
            raise EdgeError("category not found")
        return category

    def _require_subcategory(self, subcategory_id: str) -> ProductSubcategory:
        subcategory = self.db.scalar(
            select(ProductSubcategory)
            .where(
                ProductSubcategory.id == subcategory_id,
                ProductSubcategory.restaurant_key == self.settings.restaurant_key,
            )
            .options(
                joinedload(ProductSubcategory.category),
                selectinload(ProductSubcategory.products),
                selectinload(ProductSubcategory.grid_pages).selectinload(ProductGridPage.slots),
            )
        )
        if subcategory is None:
            raise EdgeError("subcategory not found")
        return subcategory

    def _require_page(self, page_id: str) -> ProductGridPage:
        page = self.db.scalar(
            select(ProductGridPage)
            .where(ProductGridPage.id == page_id, ProductGridPage.restaurant_key == self.settings.restaurant_key)
            .options(
                selectinload(ProductGridPage.slots),
                joinedload(ProductGridPage.category_ref),
                joinedload(ProductGridPage.subcategory_ref),
            )
        )
        if page is None:
            raise EdgeError("product grid page not found")
        return page

    def _ensure_slot_count(self, page: ProductGridPage) -> None:
        total_slots = page.rows * page.cols
        slots_by_position = {slot.position: slot for slot in page.slots}
        for position in range(total_slots):
            if position not in slots_by_position:
                slot = ProductGridSlot(page_id=page.id, position=position)
                self.db.add(slot)
                page.slots.append(slot)
        for slot in list(page.slots):
            if slot.position >= total_slots:
                page.slots.remove(slot)
                self.db.delete(slot)

    def _find_reassignment_category(self, *, excluding_category_id: str) -> ProductCategory:
        category = self.db.scalar(
            select(ProductCategory)
            .where(
                ProductCategory.restaurant_key == self.settings.restaurant_key,
                ProductCategory.id != excluding_category_id,
            )
            .order_by(ProductCategory.sort_order, ProductCategory.name)
        )
        if category is not None:
            return category
        replacement_name = "Tuotteet" if FALLBACK_CATEGORY_NAME == self._require_category(excluding_category_id).name else FALLBACK_CATEGORY_NAME
        category = self._get_or_create_category(replacement_name, color_code=DEFAULT_CATEGORY_COLOR)
        self._get_or_create_subcategory(category, FALLBACK_SUBCATEGORY_NAME)
        self.db.flush()
        return category

    def _fallback_subcategory_for_category(self, category: ProductCategory) -> ProductSubcategory:
        return self._get_or_create_subcategory(category, FALLBACK_SUBCATEGORY_NAME)

    def _find_reassignment_subcategory(
        self,
        category: ProductCategory,
        *,
        excluding_subcategory_id: str,
    ) -> ProductSubcategory:
        subcategory = self.db.scalar(
            select(ProductSubcategory).where(
                ProductSubcategory.restaurant_key == self.settings.restaurant_key,
                ProductSubcategory.category_id == category.id,
                ProductSubcategory.id != excluding_subcategory_id,
            )
        )
        if subcategory is not None:
            return subcategory
        replacement_name = "Yleinen" if FALLBACK_SUBCATEGORY_NAME == self._require_subcategory(excluding_subcategory_id).name else FALLBACK_SUBCATEGORY_NAME
        return self._get_or_create_subcategory(category, replacement_name)

    def _resolve_taxonomy_from_payload(
        self,
        *,
        category_id: str | None,
        subcategory_id: str | None,
        category_name: str | None,
    ) -> tuple[ProductCategory, ProductSubcategory]:
        if category_id:
            category = self._require_category(category_id)
        elif category_name:
            category = self._get_or_create_category(category_name.strip())
        else:
            category = self._get_or_create_category(FALLBACK_CATEGORY_NAME)

        if subcategory_id:
            subcategory = self._require_subcategory(subcategory_id)
            if subcategory.category_id != category.id:
                raise EdgeError("subcategory does not belong to category")
            return category, subcategory
        return category, self._fallback_subcategory_for_category(category)

    def _resolve_product_sort_order(self, raw_value: Any, external_plu: str, subcategory_id: str) -> int:
        if raw_value is not None:
            return int(raw_value)
        current_max = self.db.scalar(
            select(func.coalesce(func.max(Product.sort_order), -1)).where(
                Product.restaurant_key == self.settings.restaurant_key,
                Product.subcategory_id == subcategory_id,
            )
        )
        next_from_plu = default_product_sort_order(external_plu, int(current_max or 0) + 1)
        return max(int(current_max or -1) + 1, next_from_plu)

    def _validate_vat_rate(self, raw_value: Any) -> float:
        if raw_value is None:
            raise EdgeError("vat_rate is required")
        vat_rate = round(float(raw_value), 3)
        allowed = {round(rate, 3) for rate in self.settings.allowed_vat_rates}
        if vat_rate not in allowed:
            raise EdgeError(f"vat_rate must be one of {', '.join(str(rate) for rate in self.settings.allowed_vat_rates)}")
        return vat_rate

    def _validate_unit_gross_cents(self, raw_value: Any) -> int:
        if raw_value is None:
            raise EdgeError("unit_gross_cents is required")
        value = int(raw_value)
        if value < 0:
            raise EdgeError("unit_gross_cents must be non-negative")
        return value

    @staticmethod
    def _normalize_required_string(raw_value: Any, field_name: str) -> str:
        if raw_value is None:
            raise EdgeError(f"{field_name} is required")
        normalized = str(raw_value).strip()
        if not normalized:
            raise EdgeError(f"{field_name} is required")
        return normalized

    @staticmethod
    def _normalize_optional_string(raw_value: Any) -> str | None:
        if raw_value is None:
            return None
        normalized = str(raw_value).strip()
        return normalized or None

    def _validate_grid_size(self, rows: int, cols: int) -> None:
        if rows not in ALLOWED_GRID_SIZES or cols not in ALLOWED_GRID_SIZES:
            allowed = ", ".join(str(size) for size in sorted(ALLOWED_GRID_SIZES))
            raise EdgeError(f"grid dimensions must be one of {allowed}")

    def _default_category_color(self, category_name: str) -> str:
        for definition in BOOTSTRAP_TAXONOMY:
            if definition["name"] == category_name:
                return definition.get("color_code") or DEFAULT_CATEGORY_COLOR
        return DEFAULT_CATEGORY_COLOR

    def _default_category_sort_order(self, category_name: str) -> int:
        for index, definition in enumerate(BOOTSTRAP_TAXONOMY):
            if definition["name"] == category_name:
                return index * 100
        return self._next_category_sort_order()

    def _default_subcategory_sort_order(self, category_name: str, subcategory_name: str) -> int:
        for definition in BOOTSTRAP_TAXONOMY:
            if definition["name"] != category_name:
                continue
            for index, current_name in enumerate(definition["subcategories"]):
                if current_name == subcategory_name:
                    return index * 100
        return self._next_subcategory_sort_order_by_name(category_name)

    def _next_category_sort_order(self) -> int:
        current_max = self.db.scalar(
            select(func.coalesce(func.max(ProductCategory.sort_order), -100)).where(
                ProductCategory.restaurant_key == self.settings.restaurant_key
            )
        )
        return int(current_max) + 100

    def _next_subcategory_sort_order(self, category_id: str) -> int:
        current_max = self.db.scalar(
            select(func.coalesce(func.max(ProductSubcategory.sort_order), -100)).where(
                ProductSubcategory.restaurant_key == self.settings.restaurant_key,
                ProductSubcategory.category_id == category_id,
            )
        )
        return int(current_max) + 100

    def _next_subcategory_sort_order_by_name(self, category_name: str) -> int:
        category = self.db.scalar(
            select(ProductCategory).where(
                ProductCategory.restaurant_key == self.settings.restaurant_key,
                ProductCategory.name == category_name,
            )
        )
        if category is None:
            return 0
        return self._next_subcategory_sort_order(category.id)

    def _serialize_category(
        self,
        category: ProductCategory,
        product_counts: dict[str, int] | None = None,
        active_product_counts: dict[str, int] | None = None,
    ) -> dict[str, Any]:
        return {
            "id": category.id,
            "restaurant_key": category.restaurant_key,
            "name": category.name,
            "color_code": category.color_code,
            "sort_order": category.sort_order,
            "is_active": category.is_active,
            "product_count": (product_counts or {}).get(category.id, 0),
            "active_product_count": (active_product_counts or {}).get(category.id, 0),
            "created_at": category.created_at.isoformat(),
            "updated_at": category.updated_at.isoformat(),
        }

    def _serialize_subcategory(
        self,
        subcategory: ProductSubcategory,
        product_counts: dict[str, int] | None = None,
        active_product_counts: dict[str, int] | None = None,
    ) -> dict[str, Any]:
        category = subcategory.category if subcategory.category is not None else self._require_category(subcategory.category_id)
        return {
            "id": subcategory.id,
            "restaurant_key": subcategory.restaurant_key,
            "category_id": subcategory.category_id,
            "category_name": category.name,
            "name": subcategory.name,
            "sort_order": subcategory.sort_order,
            "is_active": subcategory.is_active,
            "product_count": (product_counts or {}).get(subcategory.id, 0),
            "active_product_count": (active_product_counts or {}).get(subcategory.id, 0),
            "created_at": subcategory.created_at.isoformat(),
            "updated_at": subcategory.updated_at.isoformat(),
        }

    def _serialize_product(self, product: Product | None) -> dict[str, Any] | None:
        if product is None:
            return None
        category_name = product.category_ref.name if product.category_ref else product.category
        subcategory_name = product.subcategory_ref.name if product.subcategory_ref else None
        return {
            "id": product.id,
            "restaurant_key": product.restaurant_key,
            "external_plu": product.external_plu,
            "name": product.name,
            "receipt_name": product.receipt_name,
            "barcode": product.barcode,
            "category": category_name,
            "category_id": product.category_id,
            "category_name": category_name,
            "subcategory_id": product.subcategory_id,
            "subcategory_name": subcategory_name,
            "sort_order": product.sort_order,
            "color_code": product.color_code,
            "unit_gross_cents": product.unit_gross_cents,
            "vat_rate": product.vat_rate,
            "image_path": product.image_path,
            "is_active": product.is_active,
            "allergens": [link.allergen_code for link in product.allergens],
            "created_at": product.created_at.isoformat(),
            "updated_at": product.updated_at.isoformat(),
        }

    def _serialize_page(self, page: ProductGridPage, *, filled_slots_count: int, total_pages: int) -> dict[str, Any]:
        category = page.category_ref if page.category_ref is not None else self._require_category(page.category_id)
        subcategory = page.subcategory_ref if page.subcategory_ref is not None else self._require_subcategory(page.subcategory_id)
        return {
            "id": page.id,
            "restaurant_key": page.restaurant_key,
            "category": category.name,
            "category_id": category.id,
            "category_name": category.name,
            "category_sort_order": category.sort_order,
            "subcategory_id": subcategory.id,
            "subcategory_name": subcategory.name,
            "subcategory_sort_order": subcategory.sort_order,
            "title": page.title,
            "page_number": page.page_number,
            "rows": page.rows,
            "cols": page.cols,
            "sort_order": page.sort_order,
            "filled_slots_count": filled_slots_count,
            "total_pages": total_pages,
            "created_at": page.created_at.isoformat(),
            "updated_at": page.updated_at.isoformat(),
        }

    def _serialize_slot(self, slot: ProductGridSlot, *, product: Product | None, is_manual: bool) -> dict[str, Any]:
        return {
            "page_id": slot.page_id,
            "position": slot.position,
            "product_id": product.id if product is not None else None,
            "label_override": slot.label_override,
            "image_override_path": slot.image_override_path,
            "is_manual": is_manual,
            "product": self._serialize_product(product),
        }

    @staticmethod
    def _now() -> datetime:
        return datetime.now(UTC)
