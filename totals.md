# AIROS POS Totals & VAT Specification (v1)

## Scope
This document defines deterministic money, VAT, and rounding rules for the AIROS POS system.

## Money representation
- All monetary amounts are integers in cents: `*_cents`.
- All unit prices in product snapshots are **GROSS** (VAT included).

## Composite VAT (mandatory)
A sold item may consist of multiple VAT components, each with explicit pricing.
- Composite component gross amounts MUST be explicit.
- The system MUST NOT infer/derive component amounts from a total.

### Component fields (snapshot)
Each component includes:
- `vat_rate_snapshot` (e.g. `0.255` or `0.14`)
- `unit_gross_cents_snapshot` (integer cents, VAT included)
- `qty` (integer)

## Deterministic VAT math (gross includes VAT)
For each component:
- `gross_cents = unit_gross_cents_snapshot * qty`
- `net_cents = round_half_away_from_zero(gross_cents / (1 + vat_rate_snapshot))`
- `tax_cents = gross_cents - net_cents`

Notes:
- All values are non-negative in v1.
- Always preserve: `net_cents + tax_cents == gross_cents`.

## Check totals (derived in Hub)
Hub Strategy 1: totals are derived from facts + assignments + voids.
For a check, using all **active** (not voided) items currently assigned to that check:
- `total_cents = Σ component_gross_cents`
- `subtotal_cents = Σ component_net_cents`
- `tax_cents = total_cents - subtotal_cents`

## Rounding (only at finalization/payment stage)
Rounding is applied only at finalization time, and MUST NOT affect VAT reporting.

- `rounding_cents` is determined by payment method:
  - CASH: apply configured cash rounding (default: nearest 5 cents).
  - CARD_EXTERNAL: no rounding (`rounding_cents = 0`).
- `amount_due_cents = total_cents + rounding_cents`

VAT/net/tax calculations are always based on `total_cents` (pre-rounding).

## Reporting time
- Use `occurred_at` as the authoritative event time.
- Day bucketing MUST use the restaurant timezone (default `Europe/Helsinki`).

## Test vectors (golden)
Composite cocktail example:
- Spirits: gross 900, vat 0.255 => net round(900/1.255)=717, tax=183
- Mixer: gross 300, vat 0.14 => net round(300/1.14)=263, tax=37
Totals: gross 1200, net 980, tax 220
VAT breakdown:
- 25.5%: gross 900, net 717, tax 183
- 14%: gross 300, net 263, tax 37
