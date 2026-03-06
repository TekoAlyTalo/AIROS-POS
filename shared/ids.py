from uuid import NAMESPACE_URL, uuid4, uuid5


def new_uuid() -> str:
    return str(uuid4())


def stable_uuid(seed: str) -> str:
    return str(uuid5(NAMESPACE_URL, seed))
