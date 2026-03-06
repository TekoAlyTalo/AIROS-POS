from textwrap import dedent

from edge.catalog import FALLBACK_CATEGORY_NAME, FALLBACK_SUBCATEGORY_NAME, price_eur_to_cents, vat_percent_to_fraction
from edge.catalog_service import CatalogService


def test_solmio_price_and_vat_conversion() -> None:
    assert price_eur_to_cents("6.00") == 600
    assert price_eur_to_cents("3,50") == 350
    assert vat_percent_to_fraction("25.5") == 0.255
    assert vat_percent_to_fraction("14") == 0.14


def test_importer_reports_failures_and_fallback_taxonomy(edge_service) -> None:
    pos_service, db = edge_service
    catalog = CatalogService(db, pos_service.settings)

    raw_csv = dedent(
        """\
        plu_code,button_text,button_text_short,receipt_name,barcode,color_code,price,vat_level,product_groups
        10,House Beer,BEER,House Beer,,#6b4f31,6.00,25.5,Alkoholijuomat
        11,Kitchen Surprise,SUR,Kitchen Surprise,,#334455,8.00,14,
        12,Broken Vat,BAD,Broken Vat,,#aa0000,5.00,10,
        """
    ).encode("utf-8")

    summary = catalog.import_solmio_products(raw_csv)

    assert summary["created"] == 2
    assert summary["updated"] == 0
    assert summary["skipped"] == 0
    assert summary["failed"] == 1
    assert summary["failed_rows"] == [{"row_number": 4, "reason": "unsupported vat_rate 0.100"}]

    products = {product["external_plu"]: product for product in catalog.list_products()}
    assert products["10"]["category_name"] == "Alkoholijuomat"
    assert products["10"]["subcategory_name"] == "Oluet"
    assert products["11"]["category_name"] == FALLBACK_CATEGORY_NAME
    assert products["11"]["subcategory_name"] == FALLBACK_SUBCATEGORY_NAME


def test_category_and_subcategory_crud(edge_service) -> None:
    pos_service, db = edge_service
    catalog = CatalogService(db, pos_service.settings)

    category = catalog.create_category(name="Testikategoria", color_code="#123456")
    subcategory = catalog.create_subcategory(category_id=category["id"], name="Lounas")

    categories = catalog.list_categories()
    subcategories = catalog.list_subcategories(category_id=category["id"])

    assert any(item["name"] == "Testikategoria" for item in categories)
    assert any(item["name"] == "Lounas" and item["category_id"] == category["id"] for item in subcategories)


def test_allergen_save_and_load(edge_service) -> None:
    pos_service, db = edge_service
    catalog = CatalogService(db, pos_service.settings)
    category = catalog.create_category(name="Keittio")
    subcategory = catalog.create_subcategory(category_id=category["id"], name="Pastat")
    product = catalog.create_product(
        external_plu="500",
        name="Pasta Carbonara",
        receipt_name="Carbonara",
        category_id=category["id"],
        subcategory_id=subcategory["id"],
        unit_gross_cents=1450,
        vat_rate=0.14,
        allergens=["gluten", "egg"],
    )

    updated = catalog.replace_product_allergens(product["id"], ["milk", "egg"])

    assert updated["allergens"] == ["egg", "milk"]
    assert catalog.get_product(product["id"])["allergens"] == ["egg", "milk"]


def test_fallback_paginated_grid_shows_all_products(edge_service) -> None:
    pos_service, db = edge_service
    catalog = CatalogService(db, pos_service.settings)

    raw_csv = dedent(
        """\
        plu_code,button_text,button_text_short,receipt_name,barcode,color_code,price,vat_level,product_groups
        1,Pizza 1,P1,Pizza 1,,#111111,10.00,14,Ruoka annokset
        2,Pizza 2,P2,Pizza 2,,#222222,10.00,14,Ruoka annokset
        3,Pizza 3,P3,Pizza 3,,#333333,10.00,14,Ruoka annokset
        4,Pizza 4,P4,Pizza 4,,#444444,10.00,14,Ruoka annokset
        5,Pizza 5,P5,Pizza 5,,#555555,10.00,14,Ruoka annokset
        """
    ).encode("utf-8")

    catalog.import_solmio_products(raw_csv)
    pizzas = next(item for item in catalog.list_subcategories() if item["name"] == "Pizzat")
    pages = catalog.list_grid_pages(subcategory_id=pizzas["id"])
    assert [page["page_number"] for page in pages] == [1]

    first_page = pages[0]
    catalog.update_grid_page(first_page["id"], rows=2, cols=2)

    pages = catalog.list_grid_pages(subcategory_id=pizzas["id"])
    assert [page["page_number"] for page in pages] == [1, 2]

    visible_product_ids: set[str] = set()
    for page in pages:
        slots = catalog.get_grid_slots(page["id"])
        visible_product_ids.update(slot["product_id"] for slot in slots if slot["product_id"])

    assert len(visible_product_ids) == 5


def test_page_button_logic_for_large_subcategory(edge_service) -> None:
    pos_service, db = edge_service
    catalog = CatalogService(db, pos_service.settings)
    category = catalog.create_category(name="Juomat")
    subcategory = catalog.create_subcategory(category_id=category["id"], name="Hanatuotteet")

    for index in range(1, 10):
        catalog.create_product(
            external_plu=f"7{index}",
            name=f"Juoma {index}",
            receipt_name=f"Juoma {index}",
            category_id=category["id"],
            subcategory_id=subcategory["id"],
            unit_gross_cents=600,
            vat_rate=0.255,
        )

    pages = catalog.list_grid_pages(subcategory_id=subcategory["id"])
    page = pages[0]
    catalog.update_grid_page(page["id"], rows=2, cols=2)

    pages = catalog.list_grid_pages(subcategory_id=subcategory["id"])
    assert [item["page_number"] for item in pages] == [1, 2, 3]
    assert all(item["total_pages"] == 3 for item in pages)
