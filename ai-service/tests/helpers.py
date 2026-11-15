import asyncio
import json
import uuid
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator

OPENAPI_DIR = Path(__file__).resolve().parents[2] / "docs" / "openapi"
FIXTURES_DIR = OPENAPI_DIR / "fixtures"

RUN_ID = "3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11"
AUTH = {"X-Nexora-Internal-Secret": "test-secret"}
RUN_BODY = {"run_id": RUN_ID, "question": "What changed?"}

# Produced by java.util.UUID.randomUUID().toString().
JAVA_GENERATED_RUN_IDS = [
    "d7b4cd53-35b4-41c3-850c-9ba6e1356a18",
    "6827f742-039a-4c12-b2b6-c21020c7fadd",
    "882ff11c-ca92-4159-9668-0c4097ffc3d6",
]
ACCEPTED_RUN_IDS = [RUN_ID, "00000000-0000-0000-0000-000000000000", str(uuid.uuid4()), *JAVA_GENERATED_RUN_IDS]
REJECTED_RUN_IDS = [
    RUN_ID.upper(),
    "3F2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11",
    f"{{{RUN_ID}}}",
    RUN_ID.replace("-", ""),
    f"urn:uuid:{RUN_ID}",
    f" {RUN_ID}",
    f"{RUN_ID} ",
    f"{RUN_ID}\n",
    f"-{RUN_ID}",
    RUN_ID[:-1],
    RUN_ID + "0",
    RUN_ID.replace("3f2b8c1e", "3f2b8c1g"),
    RUN_ID.replace("-", "--", 1),
    "3f2b8c1e5-d4a-4c7e-9b1a-2e6f0a8d7c11",
    "٣f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11",
    "",
    "not-a-uuid",
]


async def drive(response: Any, disconnect_after: float | None = None, block_start: bool = False) -> list[str]:
    # Uvicorn reports ASGI 2.3, so Starlette watches `receive` to notice a disconnect.
    scope = {"type": "http", "asgi": {"version": "3.0", "spec_version": "2.3"}}
    events: list[str] = []

    async def receive() -> dict[str, Any]:
        await asyncio.sleep(3600 if disconnect_after is None else disconnect_after)
        return {"type": "http.disconnect"}

    async def send(message: dict[str, Any]) -> None:
        if message["type"] == "http.response.start" and block_start:
            await asyncio.sleep(3600)
        elif message["type"] == "http.response.body" and message.get("body"):
            events.append(message["body"].decode().split("\n")[0].removeprefix("event: "))

    await response(scope, receive, send)
    return events


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
