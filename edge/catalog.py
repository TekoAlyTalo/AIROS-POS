from __future__ import annotations

import csv
import re
from collections import Counter
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from io import StringIO
from pathlib import Path
from typing import Any, Mapping


WORKSPACE_ROOT = Path(__file__).resolve().parents[1]
DATA_DIR = WORKSPACE_ROOT / "data"
DEFAULT_PRODUCTS_CSV_PATH = DATA_DIR / "products.csv"
PRODUCT_IMAGES_DIR = DATA_DIR / "static" / "products"
PRODUCTS_STATIC_PREFIX = "/static/products"
DEFAULT_GRID_ROWS = 4
DEFAULT_GRID_COLS = 4
ALLOWED_GRID_SIZES = {2, 3, 4, 5, 6, 7, 8}
ALLERGEN_CODES = (
    "gluten",
    "milk",
    "egg",
    "nuts",
    "peanuts",
    "fish",
    "shellfish",
    "soy",
    "celery",
    "mustard",
    "sesame",
    "lupin",
    "sulphites",
    "molluscs",
)
FALLBACK_CATEGORY_NAME = "Muut tuotteet"
FALLBACK_SUBCATEGORY_NAME = "Muut"
DEFAULT_CATEGORY_COLOR = "#39516e"
BOOTSTRAP_TAXONOMY: tuple[dict[str, Any], ...] = (
    {
        "name": "Alkoholijuomat",
        "color_code": "#8b3a3a",
        "subcategories": ("Oluet", "Siiderit", "Lonkerot", "Viinit", "Viinat", "Shotit", "Cocktailit", "Muut"),
    },
    {
        "name": "Ruoka-annokset",
        "color_code": "#5a4433",
        "subcategories": ("Pizzat", "Wingsit", "Sorminsyotavat", "Lisukkeet", "Dipit", "Muut"),
    },
    {
        "name": "Alkoholittomat juomat",
        "color_code": "#2d5863",
        "subcategories": ("Virvoitusjuomat", "Energiajuomat", "Kahvit", "Mixerit", "Muut"),
    },
    {
        "name": "Jaatelot",
        "color_code": "#4f5d7a",
        "subcategories": ("Jaatelot", "Muut"),
    },
    {
        "name": FALLBACK_CATEGORY_NAME,
        "color_code": DEFAULT_CATEGORY_COLOR,
        "subcategories": (FALLBACK_SUBCATEGORY_NAME, "Majoitus"),
    },
)

_NON_ALNUM = re.compile(r"[^a-z0-9]+")
_SPIRIT_KEYWORDS = (
    "vodka",
    "gin",
    "rum",
    "viski",
    "whisky",
    "tequila",
    "jallu",
    "jaeger",
    "cointreau",
    "campari",
    "martini",
    "malibu",
    "sambuca",
    "pernod",
    "baileys",
    "likoori",
    "liqueur",
)


@dataclass(frozen=True)
class SolmioProductRow:
    external_plu: str
    name: str
    receipt_name: str
    barcode: str | None
    category_name: str
    subcategory_name: str
    color_code: str | None
    unit_gross_cents: int
    vat_rate: float
    sort_order: int


@dataclass
class ProductImportFailure:
    row_number: int
    reason: str

    def to_dict(self) -> dict[str, Any]:
        return {"row_number": self.row_number, "reason": self.reason}


@dataclass
class ProductImportSummary:
    total_rows: int = 0
    created: int = 0
    updated: int = 0
    skipped: int = 0
    failed: int = 0
    failed_rows: list[ProductImportFailure] = field(default_factory=list)

    def add_failure(self, row_number: int, reason: str) -> None:
        self.failed += 1
        self.failed_rows.append(ProductImportFailure(row_number=row_number, reason=reason))

    def to_dict(self) -> dict[str, Any]:
        return {
            "total_rows": self.total_rows,
            "created": self.created,
            "updated": self.updated,
            "skipped": self.skipped,
            "failed": self.failed,
            "failure_reasons": dict(Counter(failure.reason for failure in self.failed_rows)),
            "failed_rows": [failure.to_dict() for failure in self.failed_rows],
        }


def decode_csv_bytes(raw_bytes: bytes) -> str:
    return raw_bytes.decode("utf-8-sig")


def read_solmio_csv_rows(raw_bytes: bytes) -> list[dict[str, str]]:
    text = decode_csv_bytes(raw_bytes)
    reader = csv.DictReader(StringIO(text))
    if reader.fieldnames is None:
        return []
    return [{key: (value or "") for key, value in row.items()} for row in reader]


def parse_solmio_row(row: Mapping[str, str], *, row_number: int) -> SolmioProductRow:
    external_plu = _required(row.get("plu_code"), "plu_code")
    name = _required(row.get("button_text") or row.get("button_text_short"), "button_text")
    receipt_name = _required(
        row.get("receipt_name") or row.get("button_text_short") or row.get("button_text"),
        "receipt_name",
    )
    category_name, subcategory_name = infer_taxonomy(
        product_groups=row.get("product_groups"),
        name=name,
        receipt_name=receipt_name,
    )
    return SolmioProductRow(
        external_plu=external_plu,
        name=name,
        receipt_name=receipt_name,
        barcode=_optional(row.get("barcode")),
        category_name=category_name,
        subcategory_name=subcategory_name,
        color_code=_optional(row.get("color_code")),
        unit_gross_cents=price_eur_to_cents(row.get("price")),
        vat_rate=vat_percent_to_fraction(row.get("vat_level")),
        sort_order=default_product_sort_order(external_plu, row_number),
    )


def split_product_groups(value: str | None) -> list[str]:
    raw = _optional(value)
    if raw is None:
        return []
    return [part.strip() for part in raw.split(",") if part.strip()]


def infer_taxonomy(*, product_groups: str | None, name: str, receipt_name: str) -> tuple[str, str]:
    group_parts = split_product_groups(product_groups)
    normalized_parts = [normalize_taxonomy_name(part) for part in group_parts]
    category_name = _normalize_category_name(normalized_parts[0]) if normalized_parts else _infer_main_category(name, receipt_name)
    subcategory_name: str | None = None
    if len(normalized_parts) > 1:
        subcategory_name = _normalize_subcategory_name(category_name, normalized_parts[1])
    if subcategory_name is None:
        subcategory_name = _infer_subcategory(category_name, name, receipt_name, normalized_parts[1:] if len(normalized_parts) > 1 else [])
    return category_name, subcategory_name


def price_eur_to_cents(value: str | None) -> int:
    decimal_value = _decimal(value, "price")
    cents = (decimal_value * Decimal("100")).quantize(Decimal("1"), rounding=ROUND_HALF_UP)
    if cents < 0:
        raise ValueError("price must be non-negative")
    return int(cents)


def vat_percent_to_fraction(value: str | None) -> float:
    decimal_value = _decimal(value, "vat_level")
    if decimal_value < 0:
        raise ValueError("vat_level must be non-negative")
    fraction = (decimal_value / Decimal("100")).quantize(Decimal("0.001"), rounding=ROUND_HALF_UP)
    return float(fraction)


def default_product_sort_order(external_plu: str, row_number: int) -> int:
    stripped = external_plu.strip()
    if stripped.isdigit():
        return int(stripped)
    return row_number


def product_sort_key(external_plu: str) -> tuple[int, int | str]:
    stripped = external_plu.strip()
    if stripped.isdigit():
        return (0, int(stripped))
    return (1, stripped.lower())


def product_image_public_path(product_id: str) -> str:
    return f"{PRODUCTS_STATIC_PREFIX}/{product_id}.png"


def normalize_taxonomy_name(value: str | None) -> str:
    normalized = _optional(value)
    if normalized is None:
        return ""
    compact = " ".join(normalized.replace("_", " ").split())
    ascii_compact = compact
    replacements = {
        "ä": "a",
        "Ä": "A",
        "ö": "o",
        "Ö": "O",
        "å": "a",
        "Å": "A",
    }
    for source, target in replacements.items():
        ascii_compact = ascii_compact.replace(source, target)
    return ascii_compact


def normalize_code(value: str) -> str:
    base = normalize_taxonomy_name(value).lower()
    return _NON_ALNUM.sub("-", base).strip("-")


def ensure_valid_allergens(allergens: list[str]) -> list[str]:
    invalid = sorted({code for code in allergens if code not in ALLERGEN_CODES})
    if invalid:
        raise ValueError(f"unsupported allergens: {', '.join(invalid)}")
    return sorted(dict.fromkeys(allergens))


def _normalize_category_name(raw_name: str | None) -> str:
    code = normalize_code(raw_name or FALLBACK_CATEGORY_NAME)
    if code in {"alkoholijuomat", "alkoholi"}:
        return "Alkoholijuomat"
    if code in {
        "ruoka-annokset",
        "ruoka-annokset-pizzat",
        "ruoka-annokset-wingsit",
        "ruoka-annokset-muut",
        "ruoka-lisukkeet-yms",
    }:
        return "Ruoka-annokset"
    if code in {"alkoholittomat-juomat", "juomat", "virvoitusjuomat"}:
        return "Alkoholittomat juomat"
    if code in {"jaatelot", "j-aatelot"}:
        return "Jaatelot"
    if code in {"majatalo", "muut-tuotteet"}:
        return FALLBACK_CATEGORY_NAME
    return normalize_taxonomy_name(raw_name) or FALLBACK_CATEGORY_NAME


def _normalize_subcategory_name(category_name: str, raw_name: str | None) -> str | None:
    if not raw_name:
        return None
    code = normalize_code(raw_name)
    if category_name == "Alkoholijuomat":
        mapping = {
            "oluet": "Oluet",
            "siiderit": "Siiderit",
            "lonkerot": "Lonkerot",
            "viinit": "Viinit",
            "viinat": "Viinat",
            "shotit": "Shotit",
            "cocktailit": "Cocktailit",
        }
        return mapping.get(code, normalize_taxonomy_name(raw_name))
    if category_name == "Ruoka-annokset":
        mapping = {
            "pizzat": "Pizzat",
            "wingsit": "Wingsit",
            "muut": "Muut",
            "sorminsyotavat": "Sorminsyotavat",
            "lisukkeet": "Lisukkeet",
            "dipit": "Dipit",
        }
        return mapping.get(code, normalize_taxonomy_name(raw_name))
    if category_name == "Alkoholittomat juomat":
        mapping = {
            "virvoitusjuomat": "Virvoitusjuomat",
            "energiajuomat": "Energiajuomat",
            "kahvit": "Kahvit",
            "mixerit": "Mixerit",
            "muut": "Muut",
        }
        return mapping.get(code, normalize_taxonomy_name(raw_name))
    if category_name == "Jaatelot":
        return "Jaatelot" if code == "jaatelot" else normalize_taxonomy_name(raw_name)
    if category_name == FALLBACK_CATEGORY_NAME and code == "majoitus":
        return "Majoitus"
    return normalize_taxonomy_name(raw_name)


def _infer_main_category(name: str, receipt_name: str) -> str:
    haystack = f"{name} {receipt_name}".lower()
    if any(keyword in haystack for keyword in ("pizza", "wings", "ransk", "poppers", "burger", "mozza", "sipul")):
        return "Ruoka-annokset"
    if any(keyword in haystack for keyword in ("kahvi", "cola", "pepsi", "vichy", "red bull", "battery", "sprite")):
        return "Alkoholittomat juomat"
    if any(keyword in haystack for keyword in ("olut", "beer", "siider", "lonkero", "viini", "vodka", "gin", "rommi")):
        return "Alkoholijuomat"
    return FALLBACK_CATEGORY_NAME


def _infer_subcategory(category_name: str, name: str, receipt_name: str, raw_parts: list[str]) -> str:
    haystack = normalize_taxonomy_name(f"{' '.join(raw_parts)} {name} {receipt_name}").lower()
    if category_name == "Alkoholijuomat":
        if any(keyword in haystack for keyword in ("crowmoor", "somersby", "siider", "cider")):
            return "Siiderit"
        if any(keyword in haystack for keyword in ("lonkero", "long drink", "longdrink")):
            return "Lonkerot"
        if any(keyword in haystack for keyword in ("riesling", "shiraz", "cabernet", "merlot", "rose", "rosee", "viini", "wine")):
            return "Viinit"
        if any(keyword in haystack for keyword in ("spritz", "cocktail", "breezer", "seltzer", "mojito")):
            return "Cocktailit"
        if re.search(r"\b2cl\b", haystack) or any(keyword in haystack for keyword in ("shotti", "shot", "salmari", "fisu", "fireball", "minttu")):
            return "Shotit"
        if any(keyword in haystack for keyword in _SPIRIT_KEYWORDS):
            return "Viinat"
        if any(keyword in haystack for keyword in ("olut", "beer", "lager", "ipa", "ale", "karhu", "lapin kulta", "sandels", "aura")):
            return "Oluet"
        return "Muut"
    if category_name == "Ruoka-annokset":
        if "wings" in haystack:
            return "Wingsit"
        if any(keyword in haystack for keyword in ("pizza", "calzone", "kebab", "kana bbq", "opera special", "pepperoni", "taco")):
            return "Pizzat"
        if any(keyword in haystack for keyword in ("dip", "dippi", "dressing")):
            return "Dipit"
        if any(keyword in haystack for keyword in ("ransk", "tayte", "pohja", "lisuke", "extra", "cheddar", "majoneesi")):
            return "Lisukkeet"
        if any(keyword in haystack for keyword in ("poppers", "mozzatikut", "mozza", "sipulirengas", "jalapeno", "nacho")):
            return "Sorminsyotavat"
        return "Muut"
    if category_name == "Alkoholittomat juomat":
        if "kahvi" in haystack:
            return "Kahvit"
        if any(keyword in haystack for keyword in ("red bull", "battery", "energy")):
            return "Energiajuomat"
        if any(keyword in haystack for keyword in ("mixeri", "mixer", "ginger beer", "tonic", "soda", "maito")):
            return "Mixerit"
        return "Virvoitusjuomat"
    if category_name == "Jaatelot":
        return "Jaatelot"
    if any(keyword in haystack for keyword in ("majatalo", "huone", "majoitus")):
        return "Majoitus"
    return FALLBACK_SUBCATEGORY_NAME


def _required(value: str | None, field_name: str) -> str:
    normalized = _optional(value)
    if normalized is None:
        raise ValueError(f"{field_name} is required")
    return normalized


def _optional(value: str | None) -> str | None:
    if value is None:
        return None
    normalized = value.strip()
    return normalized or None


def _decimal(value: str | None, field_name: str) -> Decimal:
    normalized = _required(value, field_name).replace(",", ".")
    try:
        return Decimal(normalized)
    except InvalidOperation as exc:
        raise ValueError(f"invalid {field_name}: {value}") from exc
