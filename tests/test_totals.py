from shared.money import amount_due_with_rounding
from shared.totals import calculate_check_totals


def test_composite_cocktail_vat_totals() -> None:
    totals = calculate_check_totals(
        [
            {
                "components": [
                    {
                        "component_id": "spirits",
                        "name_snapshot": "Spirits",
                        "vat_rate_snapshot": 0.255,
                        "unit_gross_cents_snapshot": 900,
                        "qty": 1,
                    },
                    {
                        "component_id": "mixer",
                        "name_snapshot": "Mixer",
                        "vat_rate_snapshot": 0.14,
                        "unit_gross_cents_snapshot": 300,
                        "qty": 1,
                    },
                ]
            }
        ]
    )

    assert totals.total_cents == 1200
    assert totals.subtotal_cents == 980
    assert totals.tax_cents == 220
    assert totals.vat_breakdown["0.255"].gross_cents == 900
    assert totals.vat_breakdown["0.255"].net_cents == 717
    assert totals.vat_breakdown["0.255"].tax_cents == 183
    assert totals.vat_breakdown["0.14"].gross_cents == 300
    assert totals.vat_breakdown["0.14"].net_cents == 263
    assert totals.vat_breakdown["0.14"].tax_cents == 37


def test_rounding_is_payment_method_specific() -> None:
    cash_rounding, cash_due = amount_due_with_rounding(1202, "CASH")
    card_rounding, card_due = amount_due_with_rounding(1202, "CARD_EXTERNAL")

    assert cash_rounding == -2
    assert cash_due == 1200
    assert card_rounding == 0
    assert card_due == 1202
