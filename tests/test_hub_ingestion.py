from shared.ids import stable_uuid

from tests.conftest import make_event


def test_hub_deduplicates_event_ids(hub_client) -> None:
    session_id = stable_uuid("session:dedupe")
    event = make_event(
        event_type="pos.session.opened",
        payload={"opening_cash_cents": 1000},
        seq=1,
        stream_id="stream-dedupe",
        session_id=session_id,
    )

    first = hub_client.post("/api/sync/pos/events:push", json={"events": [event]})
    second = hub_client.post("/api/sync/pos/events:push", json={"events": [event]})

    assert first.status_code == 200
    assert second.status_code == 200
    assert first.json()["applied_event_ids"] == [event["event_id"]]
    assert second.json()["duplicate_event_ids"] == [event["event_id"]]


def test_hub_stops_at_gap_until_missing_seq_arrives(hub_client) -> None:
    session_a = stable_uuid("session:gap:a")
    session_b = stable_uuid("session:gap:b")
    event_1 = make_event(
        event_type="pos.session.opened",
        payload={"opening_cash_cents": 1000},
        seq=1,
        stream_id="stream-gap",
        session_id=session_a,
    )
    event_2 = make_event(
        event_type="pos.session.closed",
        payload={"counted_cash_cents": 1400, "expected_cash_cents": 1400, "diff_cash_cents": 0},
        seq=2,
        stream_id="stream-gap",
        session_id=session_a,
    )
    event_3 = make_event(
        event_type="pos.session.opened",
        payload={"opening_cash_cents": 500},
        seq=3,
        stream_id="stream-gap",
        session_id=session_b,
    )

    first = hub_client.post("/api/sync/pos/events:push", json={"events": [event_1, event_3]})
    second = hub_client.post("/api/sync/pos/events:push", json={"events": [event_2, event_3]})

    assert first.status_code == 200
    assert first.json()["applied_event_ids"] == [event_1["event_id"]]
    assert first.json()["gap_streams"][0]["expected_seq"] == 2
    assert second.status_code == 200
    assert second.json()["applied_event_ids"] == [event_2["event_id"], event_3["event_id"]]


def test_hub_quarantines_invalid_event_and_blocks_stream(hub_client) -> None:
    valid = make_event(
        event_type="pos.session.opened",
        payload={"opening_cash_cents": 800},
        seq=1,
        stream_id="stream-quarantine",
        session_id=stable_uuid("session:quarantine"),
    )
    invalid = make_event(
        event_type="pos.session.closed",
        payload={"counted_cash_cents": 800, "diff_cash_cents": 0},
        seq=2,
        stream_id="stream-quarantine",
        session_id=stable_uuid("session:quarantine"),
    )
    later = make_event(
        event_type="pos.session.opened",
        payload={"opening_cash_cents": 200},
        seq=3,
        stream_id="stream-quarantine",
        session_id=stable_uuid("session:quarantine:2"),
    )

    first = hub_client.post("/api/sync/pos/events:push", json={"events": [valid, invalid, later]})
    second = hub_client.post("/api/sync/pos/events:push", json={"events": [later]})

    assert first.status_code == 200
    assert first.json()["applied_event_ids"] == [valid["event_id"]]
    assert first.json()["quarantined_event_ids"] == [invalid["event_id"]]
    assert second.status_code == 200
    assert second.json()["blocked_streams"][0]["stream_id"] == "stream-quarantine"


def test_finalize_uses_occurred_at_restaurant_timezone_for_bucketing(hub_client) -> None:
    restaurant_key = "timezone-restaurant"
    table_id = stable_uuid("table:timezone")
    table_group_id = stable_uuid("group:timezone")
    party_id = stable_uuid("party:timezone")
    check_id = stable_uuid("check:timezone")
    payment_id = stable_uuid("payment:timezone")
    receipt_id = stable_uuid("receipt:timezone")
    occurred_at = "2026-01-01T22:30:00+00:00"
    refs = {"table_id": table_id, "table_group_id": table_group_id, "party_id": party_id}
    events = [
        make_event(
            event_type="pos.party.opened",
            payload={"guest_count": 2},
            seq=1,
            stream_id="stream-timezone",
            restaurant_key=restaurant_key,
            refs=refs,
            occurred_at=occurred_at,
        ),
        make_event(
            event_type="pos.table_group.created",
            payload={
                "table_group_id": table_group_id,
                "table_ids": [table_id],
                "primary_table_id": table_id,
                "reason": "party_seating",
            },
            seq=2,
            stream_id="stream-timezone",
            restaurant_key=restaurant_key,
            refs=refs,
            occurred_at=occurred_at,
        ),
        make_event(
            event_type="pos.party.seated",
            payload={"table_group_id": table_group_id, "primary_table_id": table_id},
            seq=3,
            stream_id="stream-timezone",
            restaurant_key=restaurant_key,
            refs=refs,
            occurred_at=occurred_at,
        ),
        make_event(
            event_type="pos.check.opened",
            payload={"currency": "EUR"},
            seq=4,
            stream_id="stream-timezone",
            restaurant_key=restaurant_key,
            refs={**refs, "check_id": check_id},
            occurred_at=occurred_at,
        ),
        make_event(
            event_type="pos.check.items_added",
            payload={
                "items": [
                    {
                        "item_id": stable_uuid("item:timezone"),
                        "name_snapshot": "Cocktail",
                        "qty": 1,
                        "pricing_model": "COMPOSITE_VAT",
                        "components": [
                            {
                                "component_id": stable_uuid("component:timezone:spirits"),
                                "name_snapshot": "Spirits",
                                "vat_rate_snapshot": 0.255,
                                "unit_gross_cents_snapshot": 900,
                                "qty": 1,
                            },
                            {
                                "component_id": stable_uuid("component:timezone:mixer"),
                                "name_snapshot": "Mixer",
                                "vat_rate_snapshot": 0.14,
                                "unit_gross_cents_snapshot": 300,
                                "qty": 1,
                            },
                        ],
                    }
                ]
            },
            seq=5,
            stream_id="stream-timezone",
            restaurant_key=restaurant_key,
            refs={**refs, "check_id": check_id},
            occurred_at=occurred_at,
        ),
        make_event(
            event_type="pos.payment.recorded",
            payload={"payment_id": payment_id, "method": "CARD_EXTERNAL", "amount_cents": 1200},
            seq=6,
            stream_id="stream-timezone",
            restaurant_key=restaurant_key,
            refs={**refs, "check_id": check_id},
            occurred_at=occurred_at,
        ),
        make_event(
            event_type="pos.check.finalized",
            payload={"final_status": "PAID", "rounding_cents": 0, "amount_due_cents": 1200},
            seq=7,
            stream_id="stream-timezone",
            restaurant_key=restaurant_key,
            refs={**refs, "check_id": check_id},
            occurred_at=occurred_at,
        ),
        make_event(
            event_type="pos.receipt.issued",
            payload={"receipt_id": receipt_id, "receipt_no": 1, "issued_at": occurred_at},
            seq=8,
            stream_id="stream-timezone",
            restaurant_key=restaurant_key,
            refs={**refs, "check_id": check_id, "receipt_id": receipt_id},
            occurred_at=occurred_at,
        ),
    ]

    push = hub_client.post("/api/sync/pos/events:push", json={"events": events})
    report = hub_client.get(
        f"/api/dashboard/pos/restaurants/{restaurant_key}/reports/daily",
        params={"day": "2026-01-02"},
    )
    vat = hub_client.get(
        f"/api/dashboard/pos/restaurants/{restaurant_key}/reports/vat",
        params={"day": "2026-01-02"},
    )

    assert push.status_code == 200
    assert push.json()["applied_event_ids"] == [event["event_id"] for event in events]
    assert report.status_code == 200
    assert report.json()["totals"]["gross_cents"] == 1200
    assert report.json()["totals"]["paid_checks_count"] == 1
    assert vat.status_code == 200
    assert vat.json()["vat_breakdown"] == [
        {"vat_rate": "0.14", "gross_cents": 300, "net_cents": 263, "tax_cents": 37},
        {"vat_rate": "0.255", "gross_cents": 900, "net_cents": 717, "tax_cents": 183},
    ]
