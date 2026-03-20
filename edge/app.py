from __future__ import annotations

from fastapi import Depends, FastAPI, File, Header, HTTPException, Query, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from sqlalchemy.orm import Session

from edge.catalog import PRODUCT_IMAGES_DIR
from edge.catalog_service import CatalogService
from edge.config import EdgeSettings, get_settings
from edge.db import create_session_factory
from edge.schemas import (
    AddItemsRequest,
    ClosePartyRequest,
    CloseSessionRequest,
    FinalizeCheckRequest,
    MarkDeliveredRequest,
    MergeCheckRequest,
    OpenCheckRequest,
    OpenSessionRequest,
    PartyMoveRequest,
    PartyOpenRequest,
    ProductAllergensReplaceRequest,
    ProductCategoryCreateRequest,
    ProductCategoryPatchRequest,
    ProductCreateRequest,
    ProductGridPageCreateRequest,
    ProductGridPagePatchRequest,
    ProductGridSlotsReplaceRequest,
    ProductPatchRequest,
    ProductSubcategoryCreateRequest,
    ProductSubcategoryPatchRequest,
    RecordPaymentRequest,
    RemoveItemsRequest,
    SplitCheckRequest,
    VoidCheckRequest,
)
from edge.service import EdgeError, EdgeService
from shared.ids import stable_uuid


DEV_TOKENS = {
    "dev-staff-token": {"role": "staff", "user_id": stable_uuid("user:staff")},
    "dev-manager-token": {"role": "manager", "user_id": stable_uuid("user:manager")},
    "dev-owner-token": {"role": "owner", "user_id": stable_uuid("user:owner")},
}


def get_auth_context(authorization: str = Header(default="Bearer dev-staff-token")) -> dict[str, str]:
    parts = authorization.split(" ", 1)
    if len(parts) != 2 or parts[0].lower() != "bearer":
        raise HTTPException(status_code=401, detail="invalid authorization header")
    token = DEV_TOKENS.get(parts[1])
    if token is None:
        raise HTTPException(status_code=401, detail="unknown token")
    return token


def _handle_edge_call(fn):
    try:
        return fn()
    except EdgeError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


def create_app(settings: EdgeSettings | None = None) -> FastAPI:
    settings = settings or get_settings()
    session_factory = create_session_factory(settings)
    app = FastAPI(title="AIROS POS Edge", version="0.1.0")

    app.add_middleware(
        CORSMiddleware,
        allow_origins=[
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "http://localhost:5174",
            "http://127.0.0.1:5174",
        ],
        allow_origin_regex=(
            r"^http://("
            r"192\.168\.\d{1,3}\.\d{1,3}"
            r"|10\.\d{1,3}\.\d{1,3}\.\d{1,3}"
            r"|172\.(1[6-9]|2\d|3[0-1])\.\d{1,3}\.\d{1,3}"
            r"):(5173|5174)$"
        ),
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    PRODUCT_IMAGES_DIR.mkdir(parents=True, exist_ok=True)
    app.mount("/static/products", StaticFiles(directory=PRODUCT_IMAGES_DIR), name="product-images")

    def get_db():
        db = session_factory()
        try:
            yield db
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    @app.get("/healthz")
    def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/api/staff/pos/floorplans")
    def list_floorplans(
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> list[dict]:
        return _handle_edge_call(lambda: EdgeService(db, settings).list_floorplans())

    @app.get("/api/staff/pos/floorplans/{floorplan_id}")
    def get_floorplan(
        floorplan_id: str,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(lambda: EdgeService(db, settings).get_floorplan(floorplan_id))

    @app.get("/api/staff/pos/tables")
    def list_tables(
        floorplan_id: str | None = Query(default=None),
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> list[dict]:
        return _handle_edge_call(lambda: EdgeService(db, settings).list_tables(floorplan_id))

    @app.get("/api/staff/pos/tables/overview")
    def list_tables_overview(
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> list[dict]:
        return _handle_edge_call(lambda: EdgeService(db, settings).list_tables_overview())

    @app.get("/api/staff/pos/parties/{party_id}")
    def get_party(
        party_id: str,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(lambda: EdgeService(db, settings).get_party(party_id))

    @app.get("/api/staff/pos/parties/{party_id}/checks")
    def list_party_checks(
        party_id: str,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> list[dict]:
        return _handle_edge_call(lambda: EdgeService(db, settings).list_party_checks(party_id))

    @app.patch("/api/staff/pos/tables/{table_id}/status")
    def patch_table_status(
        table_id: str,
        body: dict,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(lambda: EdgeService(db, settings).update_table_status(table_id, body["status"]))

    @app.post("/api/staff/pos/parties/open")
    def open_party(
        request: PartyOpenRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(
            lambda: EdgeService(db, settings).open_party(
                guest_count=request.guest_count,
                table_ids=request.table_ids,
                primary_table_id=request.primary_table_id,
                note=request.note,
                actor_user_id=auth["user_id"],
            )
        )

    @app.post("/api/staff/pos/parties/{party_id}/seat")
    def seat_party(
        party_id: str,
        request: PartyMoveRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(
            lambda: EdgeService(db, settings).move_party(
                party_id,
                table_ids=request.table_ids,
                primary_table_id=request.primary_table_id,
                reason=request.reason,
                actor_user_id=auth["user_id"],
            )
        )

    @app.post("/api/staff/pos/parties/{party_id}/move")
    def move_party(
        party_id: str,
        request: PartyMoveRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(
            lambda: EdgeService(db, settings).move_party(
                party_id,
                table_ids=request.table_ids,
                primary_table_id=request.primary_table_id,
                reason=request.reason,
                actor_user_id=auth["user_id"],
            )
        )

    @app.post("/api/staff/pos/parties/{party_id}/close")
    def close_party(
        party_id: str,
        request: ClosePartyRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(
            lambda: EdgeService(db, settings).close_party(
                party_id,
                reason=request.reason,
                final_status=request.final_status,
                actor_user_id=auth["user_id"],
            )
        )

    @app.post("/api/staff/pos/parties/{party_id}/checks/open")
    def open_check(
        party_id: str,
        request: OpenCheckRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(
            lambda: EdgeService(db, settings).open_check(
                party_id,
                label=request.label,
                actor_user_id=auth["user_id"],
            )
        )

    @app.get("/api/staff/pos/checks/{check_id}")
    def get_check(
        check_id: str,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(lambda: EdgeService(db, settings).get_check(check_id))

    @app.post("/api/staff/pos/checks/{check_id}/items")
    def add_items(
        check_id: str,
        request: AddItemsRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(
            lambda: EdgeService(db, settings).add_items(
                check_id,
                items=[item.model_dump(exclude_none=True) for item in request.items],
                actor_user_id=auth["user_id"],
            )
        )

    @app.post("/api/staff/pos/checks/{check_id}/items:remove")
    def remove_items(
        check_id: str,
        request: RemoveItemsRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(
            lambda: EdgeService(db, settings).remove_items(
                check_id,
                removed=[item.model_dump() for item in request.removed],
                actor_user_id=auth["user_id"],
            )
        )

    @app.post("/api/staff/pos/checks/{check_id}/split")
    def split_check(
        check_id: str,
        request: SplitCheckRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(
            lambda: EdgeService(db, settings).split_check(
                check_id,
                moved_item_ids=request.moved_item_ids,
                target_check_id=request.target_check_id,
                label=request.label,
                actor_user_id=auth["user_id"],
            )
        )

    @app.post("/api/staff/pos/checks/merge")
    def merge_checks(
        request: MergeCheckRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(
            lambda: EdgeService(db, settings).merge_checks(
                target_check_id=request.target_check_id,
                source_check_ids=request.source_check_ids,
                actor_user_id=auth["user_id"],
            )
        )

    @app.post("/api/staff/pos/checks/{check_id}/void")
    def void_check(
        check_id: str,
        request: VoidCheckRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(
            lambda: EdgeService(db, settings).void_check(
                check_id,
                reason_code=request.reason_code,
                reason_note=request.reason_note,
                actor_user_id=auth["user_id"],
            )
        )

    @app.post("/api/staff/pos/checks/{check_id}/payments")
    def record_payment(
        check_id: str,
        request: RecordPaymentRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(
            lambda: EdgeService(db, settings).record_payment(
                check_id,
                method=request.method,
                amount_cents=request.amount_cents,
                external_ref=request.external_ref,
                actor_user_id=auth["user_id"],
            )
        )

    @app.post("/api/staff/pos/checks/{check_id}/finalize")
    def finalize_check(
        check_id: str,
        request: FinalizeCheckRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(
            lambda: EdgeService(db, settings).finalize_check(
                check_id,
                payment_method=request.payment_method,
                issue_receipt=request.issue_receipt,
                actor_user_id=auth["user_id"],
            )
        )

    @app.post("/api/staff/pos/sessions/open")
    def open_session(
        request: OpenSessionRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(
            lambda: EdgeService(db, settings).open_session(
                opening_cash_cents=request.opening_cash_cents,
                actor_user_id=auth["user_id"],
            )
        )

    @app.get("/api/staff/pos/sessions/current")
    def current_session(
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict | None:
        return _handle_edge_call(lambda: EdgeService(db, settings).get_current_session())

    @app.post("/api/staff/pos/sessions/close")
    def close_session(
        request: CloseSessionRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(
            lambda: EdgeService(db, settings).close_session(
                counted_cash_cents=request.counted_cash_cents,
                note=request.note,
                actor_user_id=auth["user_id"],
            )
        )

    @app.get("/api/staff/pos/outbox/pending")
    def pending_outbox(
        limit: int = Query(default=100, ge=1, le=500),
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> list[dict]:
        return _handle_edge_call(lambda: EdgeService(db, settings).list_pending_outbox(limit))

    @app.post("/api/staff/pos/outbox/mark-delivered")
    def mark_outbox_delivered(
        request: MarkDeliveredRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(lambda: EdgeService(db, settings).mark_outbox_delivered(request.event_ids))

    @app.get("/api/staff/pos/products")
    def list_products(
        category_id: str | None = Query(default=None),
        subcategory_id: str | None = Query(default=None),
        category: str | None = Query(default=None),
        q: str | None = Query(default=None),
        active: bool | None = Query(default=None),
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> list[dict]:
        return _handle_edge_call(
            lambda: CatalogService(db, settings).list_products(
                category_id=category_id,
                subcategory_id=subcategory_id,
                category=category,
                q=q,
                active=active,
            )
        )

    @app.post("/api/staff/pos/products")
    def create_product(
        request: ProductCreateRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(lambda: CatalogService(db, settings).create_product(**request.model_dump()))

    @app.get("/api/staff/pos/product-categories")
    def list_product_categories(
        active: bool | None = Query(default=None),
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> list[dict]:
        return _handle_edge_call(lambda: CatalogService(db, settings).list_categories(active=active))

    @app.post("/api/staff/pos/product-categories")
    def create_product_category(
        request: ProductCategoryCreateRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(lambda: CatalogService(db, settings).create_category(**request.model_dump()))

    @app.patch("/api/staff/pos/product-categories/{category_id}")
    def patch_product_category(
        category_id: str,
        request: ProductCategoryPatchRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(
            lambda: CatalogService(db, settings).update_category(
                category_id,
                **request.model_dump(exclude_unset=True),
            )
        )

    @app.delete("/api/staff/pos/product-categories/{category_id}")
    def delete_product_category(
        category_id: str,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(lambda: CatalogService(db, settings).delete_category(category_id))

    @app.get("/api/staff/pos/product-subcategories")
    def list_product_subcategories(
        category_id: str | None = Query(default=None),
        active: bool | None = Query(default=None),
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> list[dict]:
        return _handle_edge_call(
            lambda: CatalogService(db, settings).list_subcategories(category_id=category_id, active=active)
        )

    @app.post("/api/staff/pos/product-subcategories")
    def create_product_subcategory(
        request: ProductSubcategoryCreateRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(lambda: CatalogService(db, settings).create_subcategory(**request.model_dump()))

    @app.patch("/api/staff/pos/product-subcategories/{subcategory_id}")
    def patch_product_subcategory(
        subcategory_id: str,
        request: ProductSubcategoryPatchRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(
            lambda: CatalogService(db, settings).update_subcategory(
                subcategory_id,
                **request.model_dump(exclude_unset=True),
            )
        )

    @app.delete("/api/staff/pos/product-subcategories/{subcategory_id}")
    def delete_product_subcategory(
        subcategory_id: str,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(lambda: CatalogService(db, settings).delete_subcategory(subcategory_id))

    @app.get("/api/staff/pos/product-groups")
    def list_product_groups(
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> list[str]:
        return _handle_edge_call(lambda: CatalogService(db, settings).list_product_groups())

    @app.post("/api/staff/pos/products:import")
    async def import_products(
        file: UploadFile = File(...),
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        raw_bytes = await file.read()
        return _handle_edge_call(lambda: CatalogService(db, settings).import_solmio_products(raw_bytes))

    @app.get("/api/staff/pos/products/{product_id}")
    def get_product(
        product_id: str,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(lambda: CatalogService(db, settings).get_product(product_id))

    @app.patch("/api/staff/pos/products/{product_id}")
    def patch_product(
        product_id: str,
        request: ProductPatchRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(
            lambda: CatalogService(db, settings).update_product(
                product_id,
                **request.model_dump(exclude_unset=True),
            )
        )

    @app.put("/api/staff/pos/products/{product_id}/allergens")
    def put_product_allergens(
        product_id: str,
        request: ProductAllergensReplaceRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(
            lambda: CatalogService(db, settings).replace_product_allergens(product_id, request.allergens)
        )

    @app.post("/api/staff/pos/products/{product_id}/image")
    async def upload_product_image(
        product_id: str,
        file: UploadFile = File(...),
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        raw_bytes = await file.read()
        return _handle_edge_call(lambda: CatalogService(db, settings).upload_product_image(product_id, raw_bytes))

    @app.get("/api/staff/pos/product-grids/pages")
    def list_product_grid_pages(
        category_id: str | None = Query(default=None),
        subcategory_id: str | None = Query(default=None),
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> list[dict]:
        return _handle_edge_call(
            lambda: CatalogService(db, settings).list_grid_pages(
                category_id=category_id,
                subcategory_id=subcategory_id,
            )
        )

    @app.post("/api/staff/pos/product-grids/pages")
    def create_product_grid_page(
        request: ProductGridPageCreateRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(
            lambda: CatalogService(db, settings).create_grid_page(
                category_id=request.category_id,
                subcategory_id=request.subcategory_id,
                title=request.title,
                page_number=request.page_number,
                rows=request.rows,
                cols=request.cols,
                sort_order=request.sort_order,
            )
        )

    @app.patch("/api/staff/pos/product-grids/pages/{page_id}")
    def patch_product_grid_page(
        page_id: str,
        request: ProductGridPagePatchRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> dict:
        return _handle_edge_call(
            lambda: CatalogService(db, settings).update_grid_page(
                page_id,
                **request.model_dump(exclude_unset=True),
            )
        )

    @app.get("/api/staff/pos/product-grids/pages/{page_id}/slots")
    def get_product_grid_slots(
        page_id: str,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> list[dict]:
        return _handle_edge_call(lambda: CatalogService(db, settings).get_grid_slots(page_id))

    @app.put("/api/staff/pos/product-grids/pages/{page_id}/slots")
    def put_product_grid_slots(
        page_id: str,
        request: ProductGridSlotsReplaceRequest,
        db: Session = Depends(get_db),
        auth: dict[str, str] = Depends(get_auth_context),
    ) -> list[dict]:
        return _handle_edge_call(
            lambda: CatalogService(db, settings).replace_grid_slots(
                page_id,
                [slot.model_dump(exclude_none=True) for slot in request.slots],
            )
        )

    return app
