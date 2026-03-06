from __future__ import annotations

from decimal import Decimal, ROUND_HALF_UP


def ensure_non_negative_cents(value: int, field_name: str) -> None:
    if not isinstance(value, int):
        raise ValueError(f"{field_name} must be an integer number of cents")
    if value < 0:
        raise ValueError(f"{field_name} must be non-negative cents")


def round_half_away_from_zero(value: Decimal | float | int) -> int:
    decimal_value = value if isinstance(value, Decimal) else Decimal(str(value))
    sign = -1 if decimal_value < 0 else 1
    rounded = abs(decimal_value).quantize(Decimal("1"), rounding=ROUND_HALF_UP)
    return sign * int(rounded)


def cash_rounding_delta(total_cents: int, increment: int = 5) -> int:
    if increment <= 0:
        raise ValueError("increment must be positive")
    if total_cents < 0:
        raise ValueError("total_cents must be non-negative")
    remainder = total_cents % increment
    if remainder <= increment // 2:
        return -remainder
    return increment - remainder


def amount_due_with_rounding(total_cents: int, payment_method: str) -> tuple[int, int]:
    if payment_method == "CASH":
        rounding_cents = cash_rounding_delta(total_cents)
    elif payment_method == "CARD_EXTERNAL":
        rounding_cents = 0
    else:
        raise ValueError(f"unsupported payment method: {payment_method}")
    return rounding_cents, total_cents + rounding_cents

