import asyncio
import uuid

import httpx
import pytest
from fastapi.responses import StreamingResponse
from fastapi.testclient import TestClient

from app import runs
from app.main import app
from helpers import AUTH, RUN_BODY, RUN_ID, drive, parse_sse

client = TestClient(app)
SCRIPT_LENGTH = len(runs._SCRIPT)
CANCEL_URL = f"/internal/v1/runs/{RUN_ID}/cancel"


def test_run_streams_the_script_and_ends_with_a_single_done() -> None:
    response = client.post("/internal/v1/runs", json=RUN_BODY, headers=AUTH)

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    frames = parse_sse(response.text)
    types = [event for event, _ in frames]
    assert len(frames) == SCRIPT_LENGTH
    assert types[-1] == "done"
    assert types.count("done") == 1 and "error" not in types
    assert all(envelope["run_id"] == RUN_ID for _, envelope in frames)
    assert len({envelope["event_id"] for _, envelope in frames}) == SCRIPT_LENGTH
    assert runs._active == {}


def test_heartbeat_is_a_real_event_sent_while_idle(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("NEXORA_FAKE_EVENT_DELAY_SECONDS", "0.12")
    monkeypatch.setenv("NEXORA_HEARTBEAT_INTERVAL_SECONDS", "0.05")

    frames = parse_sse(client.post("/internal/v1/runs", json=RUN_BODY, headers=AUTH).text)

    types = [event for event, _ in frames]
    assert "heartbeat" in types
    assert types[-1] == "done"
    assert [e for e in types if e != "heartbeat"] == [e for e, _ in runs._SCRIPT]


def test_cancel_of_an_unknown_run_is_idempotent() -> None:
    assert client.post(CANCEL_URL, headers=AUTH).status_code == 204
    assert client.post(CANCEL_URL, headers=AUTH).status_code == 204


def test_cancel_after_completion_changes_nothing() -> None:
    client.post("/internal/v1/runs", json=RUN_BODY, headers=AUTH)

    assert client.post(CANCEL_URL, headers=AUTH).status_code == 204
    assert runs._active == {}


def test_cancel_with_a_malformed_run_id_is_invalid() -> None:
    response = client.post("/internal/v1/runs/not-a-uuid/cancel", headers=AUTH)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_REQUEST"


@pytest.mark.parametrize(
    "body",
    [
        {"run_id": RUN_ID, "question": ""},
        {"run_id": RUN_ID, "question": "x" * 2001},
        {"question": "no run id"},
        {"run_id": "not-a-uuid", "question": "q"},
        {"run_id": RUN_ID, "question": "q", "scenario": "fail"},
    ],
)
def test_invalid_run_requests_are_rejected_with_the_error_envelope(body: dict[str, str]) -> None:
    response = client.post("/internal/v1/runs", json=body, headers=AUTH)

    assert response.status_code == 400
    assert response.json() == {"error": {"code": "INVALID_REQUEST", "message": "The request is invalid."}}


def test_question_length_limits_are_inclusive() -> None:
    for question in ("x", "x" * 2000):
        body = {"run_id": str(uuid.uuid4()), "question": question}
        assert client.post("/internal/v1/runs", json=body, headers=AUTH).status_code == 200


async def _cancel_midway() ->tuple[httpx.Response, httpx.Response]:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as http:

        async def cancel() -> httpx.Response:
            await asyncio.sleep(0.45)
            return await http.post(CANCEL_URL, headers=AUTH)

        return await asyncio.gather(http.post("/internal/v1/runs", json=RUN_BODY, headers=AUTH), cancel())


def test_cancel_stops_the_run_without_a_terminal_event_and_is_repeatable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("NEXORA_FAKE_EVENT_DELAY_SECONDS", "0.3")

    run, cancel = asyncio.run(_cancel_midway())

    types = [event for event, _ in parse_sse(run.text)]
    assert cancel.status_code == 204
    assert 0 < len(types) < SCRIPT_LENGTH
    assert "done" not in types and "error" not in types
    assert runs._active == {}
    assert client.post(CANCEL_URL, headers=AUTH).status_code == 204


def test_a_run_id_that_is_already_streaming_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("NEXORA_FAKE_EVENT_DELAY_SECONDS", "0.3")

    async def scenario() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as http:
            first = asyncio.create_task(http.post("/internal/v1/runs", json=RUN_BODY, headers=AUTH))
            await asyncio.sleep(0.1)
            duplicate = await http.post("/internal/v1/runs", json=RUN_BODY, headers=AUTH)
            await http.post(CANCEL_URL, headers=AUTH)
            await first
            return duplicate

    duplicate = asyncio.run(scenario())

    assert duplicate.status_code == 400
    assert duplicate.json()["error"]["code"] == "INVALID_REQUEST"
    assert runs._active == {}


async def _new_run() -> StreamingResponse:
    return await runs.create_run(runs.RunRequest(run_id=RUN_ID, question="q"))


def test_cancel_before_the_first_event_emits_nothing_and_frees_the_run(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("NEXORA_FAKE_EVENT_DELAY_SECONDS", "0.3")

    async def scenario() -> list[str]:
        response = await _new_run()
        await runs.cancel_run(RUN_ID)
        return await drive(response)

    assert asyncio.run(scenario()) == []
    assert runs._active == {}


def test_client_disconnect_mid_stream_stops_the_run_and_frees_it(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("NEXORA_FAKE_EVENT_DELAY_SECONDS", "0.3")

    async def scenario() -> list[str]:
        return await drive(await _new_run(), disconnect_after=0.5)

    events = asyncio.run(scenario())

    assert 0 < len(events) < SCRIPT_LENGTH
    assert "done" not in events
    assert runs._active == {}


def test_client_disconnect_before_streaming_starts_frees_the_run() -> None:
    async def scenario() -> list[str]:
        return await drive(await _new_run(), disconnect_after=0.1, block_start=True)

    assert asyncio.run(scenario()) == []
    assert runs._active == {}
    assert client.post("/internal/v1/runs", json=RUN_BODY, headers=AUTH).status_code == 200


def test_cancelling_unknown_runs_does_not_grow_state() -> None:
    for _ in range(50):
        assert client.post(f"/internal/v1/runs/{uuid.uuid4()}/cancel", headers=AUTH).status_code == 204

    assert runs._active == {}


def test_two_cancels_while_streaming_both_succeed(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("NEXORA_FAKE_EVENT_DELAY_SECONDS", "0.3")

    async def scenario() -> list[httpx.Response]:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as http:

            async def cancel(after: float) -> httpx.Response:
                await asyncio.sleep(after)
                return await http.post(CANCEL_URL, headers=AUTH)

            return await asyncio.gather(
                http.post("/internal/v1/runs", json=RUN_BODY, headers=AUTH), cancel(0.45), cancel(0.5)
            )

    run, first, second = asyncio.run(scenario())

    assert first.status_code == second.status_code == 204
    assert "done" not in [event for event, _ in parse_sse(run.text)]
    assert runs._active == {}
