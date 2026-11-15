import asyncio
import uuid
from urllib.parse import quote

import httpx
import pytest
from fastapi.testclient import TestClient

from app import runs
from app.main import app
from helpers import (
    ACCEPTED_RUN_IDS as ACCEPTED,
    AUTH,
    REJECTED_RUN_IDS as REJECTED,
    RUN_ID,
    load_spec,
    parse_sse,
    schema_validator,
)

client = TestClient(app)

def _body(run_id: str) -> dict[str, str]:
    return {"run_id": run_id, "question": "What changed?"}


@pytest.mark.parametrize("run_id", ACCEPTED)
def test_canonical_run_ids_are_accepted_and_echoed_unchanged(run_id: str) -> None:
    response = client.post("/internal/v1/runs", json=_body(run_id), headers=AUTH)

    assert response.status_code == 200
    assert {envelope["run_id"] for _, envelope in parse_sse(response.text)} == {run_id}
    assert client.post(f"/internal/v1/runs/{run_id}/cancel", headers=AUTH).status_code == 204


@pytest.mark.parametrize("run_id", REJECTED)
def test_non_canonical_run_ids_are_rejected_in_the_request_body(run_id: str) -> None:
    response = client.post("/internal/v1/runs", json=_body(run_id), headers=AUTH)

    assert response.status_code == 400
    assert response.json() == {"error": {"code": "INVALID_REQUEST", "message": "The request is invalid."}}
    assert runs._active == {}


@pytest.mark.parametrize("run_id", [value for value in REJECTED if value])
def test_non_canonical_run_ids_are_rejected_in_the_cancel_path(run_id: str) -> None:
    response = client.post(f"/internal/v1/runs/{quote(run_id, safe='')}/cancel", headers=AUTH)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_REQUEST"


@pytest.mark.parametrize("run_id", ACCEPTED + REJECTED)
def test_the_contract_pattern_and_the_service_agree(run_id: str) -> None:
    contract = schema_validator(load_spec("internal"), "RunRequest")
    status = client.post("/internal/v1/runs", json=_body(run_id), headers=AUTH).status_code

    assert contract.is_valid(_body(run_id)) == (status == 200)


def test_ids_are_never_matched_case_insensitively(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("NEXORA_FAKE_EVENT_DELAY_SECONDS", "0.1")

    async def scenario() -> list[httpx.Response]:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as http:

            async def cancel_with_uppercase() -> httpx.Response:
                await asyncio.sleep(0.25)
                return await http.post(f"/internal/v1/runs/{RUN_ID.upper()}/cancel", headers=AUTH)

            return await asyncio.gather(
                http.post("/internal/v1/runs", json=_body(RUN_ID), headers=AUTH), cancel_with_uppercase()
            )

    run, cancel = asyncio.run(scenario())

    assert cancel.status_code == 400
    assert [event for event, _ in parse_sse(run.text)][-1] == "done"
