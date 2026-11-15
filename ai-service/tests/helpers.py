import json
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator

OPENAPI_DIR = Path(__file__).resolve().parents[2] / "docs" / "openapi"
FIXTURES_DIR = OPENAPI_DIR / "fixtures"

RUN_ID = "3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11"
AUTH = {"X-Nexora-Internal-Secret": "test-secret"}
RUN_BODY = {"run_id": RUN_ID, "question": "What changed?"}


def schema_validator(spec: dict[str, Any], schema_name: str) -> Draft202012Validator:
    # Local $refs resolve against the spec's own components, carried in the wrapper document.
    return Draft202012Validator(
        {"$ref": f"#/components/schemas/{schema_name}", "components": spec["components"]}
    )


def load_spec(name: str) -> dict[str, Any]:
    return yaml.safe_load((OPENAPI_DIR / f"{name}.yaml").read_text(encoding="utf-8"))


def load_fixture(name: str) -> dict[str, Any]:
    return json.loads((FIXTURES_DIR / f"{name}.json").read_text(encoding="utf-8"))


def parse_sse(body: str) -> list[tuple[str, dict[str, Any]]]:
    frames = []
    for block in body.split("\n\n"):
        if not block.strip():
            continue
        lines = block.split("\n")
        assert len(lines) == 2, f"unexpected frame: {block!r}"
        assert lines[0].startswith("event: ") and lines[1].startswith("data: ")
        frames.append((lines[0].removeprefix("event: "), json.loads(lines[1].removeprefix("data: "))))
    return frames
