from __future__ import annotations

import ast
from pathlib import Path


def main() -> None:
    for path in Path(".").rglob("*.py"):
        ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    print("syntax-ok")


if __name__ == "__main__":
    main()
