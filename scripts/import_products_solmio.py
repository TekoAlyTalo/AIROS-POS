from __future__ import annotations

import json
import sys
from pathlib import Path

from edge.catalog import DEFAULT_PRODUCTS_CSV_PATH
from edge.catalog_service import CatalogService
from edge.config import get_settings
from edge.db import create_session_factory


def main() -> int:
    csv_path = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else DEFAULT_PRODUCTS_CSV_PATH
    settings = get_settings()
    session_factory = create_session_factory(settings)
    with session_factory() as db:
        summary = CatalogService(db, settings).import_solmio_products_from_path(csv_path)
        db.commit()
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
