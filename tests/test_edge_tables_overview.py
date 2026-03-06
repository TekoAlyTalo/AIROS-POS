from shared.ids import stable_uuid


def test_tables_overview_counts_known_checks_and_open_gross(edge_service) -> None:
    service, db = edge_service
    table_ids = [table["id"] for table in service.list_tables()[:2]]
    party = service.open_party(
        guest_count=2,
        table_ids=[table_ids[0]],
        primary_table_id=table_ids[0],
        note="overview test",
        actor_user_id=stable_uuid("user:test"),
    )
    paid_check = service.open_check(party["id"], label="Paid", actor_user_id=stable_uuid("user:test"))
    service.add_items(
        paid_check["id"],
        items=[
            {
                "item_id": stable_uuid("item:overview:cocktail"),
                "name_snapshot": "Cocktail",
                "qty": 1,
                "pricing_model": "COMPOSITE_VAT",
                "components": [
                    {
                        "component_id": stable_uuid("component:overview:spirits"),
                        "name_snapshot": "Spirits",
                        "vat_rate_snapshot": 0.255,
                        "unit_gross_cents_snapshot": 900,
                        "qty": 1,
                    },
                    {
                        "component_id": stable_uuid("component:overview:mixer"),
                        "name_snapshot": "Mixer",
                        "vat_rate_snapshot": 0.14,
                        "unit_gross_cents_snapshot": 300,
                        "qty": 1,
                    },
                ],
            }
        ],
        actor_user_id=stable_uuid("user:test"),
    )
    service.record_payment(
        paid_check["id"],
        method="CARD_EXTERNAL",
        amount_cents=1200,
        external_ref="terminal-1",
        actor_user_id=stable_uuid("user:test"),
    )
    service.finalize_check(
        paid_check["id"],
        payment_method="CARD_EXTERNAL",
        issue_receipt=True,
        actor_user_id=stable_uuid("user:test"),
    )

    open_check = service.open_check(party["id"], label="Open", actor_user_id=stable_uuid("user:test"))
    service.add_items(
        open_check["id"],
        items=[
            {
                "item_id": stable_uuid("item:overview:beer"),
                "name_snapshot": "Beer",
                "qty": 1,
                "pricing_model": "SINGLE_VAT",
                "components": [
                    {
                        "component_id": stable_uuid("component:overview:beer"),
                        "name_snapshot": "Beer",
                        "vat_rate_snapshot": 0.255,
                        "unit_gross_cents_snapshot": 500,
                        "qty": 1,
                    }
                ],
            }
        ],
        actor_user_id=stable_uuid("user:test"),
    )

    overview = service.list_tables_overview()
    occupied = next(row for row in overview if row["table_id"] == table_ids[0])
    free = next(row for row in overview if row["table_id"] == table_ids[1])

    assert occupied["status"] == "OCCUPIED"
    assert occupied["current_party_id"] == party["id"]
    assert occupied["known_checks_count"] == 2
    assert occupied["known_open_total_gross_cents"] == 500
    assert occupied["opened_at"] is not None

    assert free["status"] == "FREE"
    assert free["current_party_id"] is None
    assert free["known_checks_count"] == 0
    assert free["known_open_total_gross_cents"] is None
    assert free["opened_at"] is None
