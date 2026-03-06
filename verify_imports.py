from __future__ import annotations


def main() -> None:
    import fastapi  # noqa: F401
    import jsonschema  # noqa: F401
    import pydantic  # noqa: F401
    import sqlalchemy  # noqa: F401

    from edge.app import create_app as create_edge_app
    from hub.app import create_app as create_hub_app

    create_edge_app
    create_hub_app
    print("imports-ok")


if __name__ == "__main__":
    main()
