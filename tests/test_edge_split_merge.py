from shared.ids import stable_uuid


def test_split_and_merge_recompute_assignments(edge_service) -> None:
    service, db = edge_service
    table_ids = [table["id"] for table in service.list_tables()[:2]]
    party = service.open_party(
        guest_count=3,
        table_ids=table_ids,
        primary_table_id=table_ids[0],
        note="split-check test",
        actor_user_id=stable_uuid("user:test"),
    )
    source_check = service.open_check(party["id"], label="Main", actor_user_id=stable_uuid("user:test"))
    updated_check = service.add_items(
        source_check["id"],
        items=[
            {
                "item_id": stable_uuid("item:beer"),
                "name_snapshot": "Beer",
                "qty": 1,
                "pricing_model": "SINGLE_VAT",
                "components": [
                    {
                        "component_id": stable_uuid("component:beer"),
                        "name_snapshot": "Beer",
                        "vat_rate_snapshot": 0.255,
                        "unit_gross_cents_snapshot": 500,
                        "qty": 1,
                    }
                ],
            },
            {
                "item_id": stable_uuid("item:cocktail"),
                "name_snapshot": "Cocktail",
                "qty": 1,
                "pricing_model": "COMPOSITE_VAT",
                "components": [
                    {
                        "component_id": stable_uuid("component:cocktail:spirits"),
                        "name_snapshot": "Spirits",
                        "vat_rate_snapshot": 0.255,
                        "unit_gross_cents_snapshot": 900,
                        "qty": 1,
                    },
                    {
                        "component_id": stable_uuid("component:cocktail:mixer"),
                        "name_snapshot": "Mixer",
                        "vat_rate_snapshot": 0.14,
                        "unit_gross_cents_snapshot": 300,
                        "qty": 1,
                    },
                ],
            },
        ],
        actor_user_id=stable_uuid("user:test"),
    )

    assert updated_check["total_cents"] == 1700

    target_check = service.split_check(
        source_check["id"],
        moved_item_ids=[stable_uuid("item:beer")],
        target_check_id=None,
        label="Split",
        actor_user_id=stable_uuid("user:test"),
    )
    source_after_split = service.get_check(source_check["id"])

    assert source_after_split["total_cents"] == 1200
    assert target_check["total_cents"] == 500
    assert [item["item_id"] for item in source_after_split["items"]] == [stable_uuid("item:cocktail")]
    assert [item["item_id"] for item in target_check["items"]] == [stable_uuid("item:beer")]

    merged_check = service.merge_checks(
        target_check_id=source_check["id"],
        source_check_ids=[target_check["id"]],
        actor_user_id=stable_uuid("user:test"),
    )
    split_after_merge = service.get_check(target_check["id"])

    assert merged_check["total_cents"] == 1700
    assert split_after_merge["total_cents"] == 0
    assert sorted(item["item_id"] for item in merged_check["items"]) == sorted(
        [stable_uuid("item:beer"), stable_uuid("item:cocktail")]
    )
