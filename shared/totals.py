from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from typing import Iterable

from shared.money import round_half_away_from_zero


@dataclass(frozen=True)
class ComponentTotals:
    vat_rate: Decimal
    gross_cents: int
    net_cents: int
    tax_cents: int


@dataclass(frozen=True)
class CheckTotals:
    total_cents: int
    subtotal_cents: int
    tax_cents: int
    vat_breakdown: dict[str, ComponentTotals]


def _component_totals(component: dict) -> ComponentTotals:
    vat_rate = Decimal(str(component["vat_rate_snapshot"]))
    gross_cents = int(component["unit_gross_cents_snapshot"]) * int(component["qty"])
    net_cents = round_half_away_from_zero(Decimal(gross_cents) / (Decimal("1") + vat_rate))
    tax_cents = gross_cents - net_cents
    return ComponentTotals(
        vat_rate=vat_rate,
        gross_cents=gross_cents,
        net_cents=net_cents,
        tax_cents=tax_cents,
    )


def calculate_check_totals(items: Iterable[dict]) -> CheckTotals:
    total_cents = 0
    subtotal_cents = 0
    vat_breakdown: dict[str, ComponentTotals] = {}

    for item in items:
        if item.get("voided"):
            continue
        for component in item["components"]:
            totals = _component_totals(component)
            key = str(totals.vat_rate)
            current = vat_breakdown.get(key)
            if current is None:
                vat_breakdown[key] = totals
            else:
                vat_breakdown[key] = ComponentTotals(
                    vat_rate=totals.vat_rate,
                    gross_cents=current.gross_cents + totals.gross_cents,
                    net_cents=current.net_cents + totals.net_cents,
                    tax_cents=current.tax_cents + totals.tax_cents,
                )
            total_cents += totals.gross_cents
            subtotal_cents += totals.net_cents

    return CheckTotals(
        total_cents=total_cents,
        subtotal_cents=subtotal_cents,
        tax_cents=total_cents - subtotal_cents,
        vat_breakdown=vat_breakdown,
    )

