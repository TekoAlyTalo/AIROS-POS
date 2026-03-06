from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator, FormatChecker


SCHEMA_NAME = "airos.pos.sync.event.v1"


class EventValidationError(ValueError):
    """Raised when an event violates schema or money invariants."""


def clean_nones(obj: Any) -> Any:
    if isinstance(obj, dict):
        return {
            key: clean_nones(value)
            for key, value in obj.items()
            if value is not None
        }
    if isinstance(obj, list):
        return [clean_nones(item) for item in obj if item is not None]
    return obj


@lru_cache(maxsize=1)
def _validator() -> Draft202012Validator:
    schema_path = Path(__file__).resolve().parent / "schemas" / "airos.pos.sync.event.v1.schema.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    return Draft202012Validator(schema=schema, format_checker=FormatChecker())


def validate_sync_event(event: dict[str, Any]) -> None:
    validator = _validator()
    errors = sorted(validator.iter_errors(event), key=lambda item: item.json_path)
    if errors:
        message = "; ".join(error.message for error in errors)
        raise EventValidationError(message)

    payload_event_type = event["payload"]["event_type"]
    if event["event_type"] != payload_event_type:
        raise EventValidationError("event_type must match payload.event_type")

    if event["schema"] != SCHEMA_NAME:
        raise EventValidationError(f"unsupported schema: {event['schema']}")

    payload = event["payload"]
    if payload_event_type == "pos.table_group.created":
        table_ids = payload["table_ids"]
        if payload["primary_table_id"] not in table_ids:
            raise EventValidationError("primary_table_id must be included in table_ids")

    if payload_event_type == "pos.check.items_added":
        for item in payload["items"]:
            if item["pricing_model"] == "SINGLE_VAT" and len(item["components"]) != 1:
                raise EventValidationError("SINGLE_VAT items must include exactly one component")
            if item["pricing_model"] == "COMPOSITE_VAT" and len(item["components"]) < 2:
                raise EventValidationError("COMPOSITE_VAT items must include at least two components")
