import pytest
from fastapi.testclient import TestClient

from app import runs
from app.main import app
from helpers import AUTH, RUN_BODY, RUN_ID, load_spec, parse_sse, schema_validator

INTERNAL_ERROR = {"error": {"code": "INTERNAL_ERROR", "message": "An internal error occurred."}}
LEAKY_DETAIL = "internal-detail-that-must-not-leak"


class _BrokenRegistry(dict):
    def get(self, *_: object) -> None:
        raise RuntimeError(LEAKY_DETAIL)


def test_unexpected_exception_returns_the_envelope_without_internal_details(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(runs, "_active", _BrokenRegistry())
    client = TestClient(app, raise_server_exceptions=False)

    response = client.post(f"/internal/v1/runs/{RUN_ID}/cancel", headers=AUTH)

    assert response.status_code == 500
    assert response.json() == INTERNAL_ERROR
    assert LEAKY_DETAIL not in response.text and "Traceback" not in response.text
    schema_validator(load_spec("internal"), "ErrorResponse").validate(response.json())


def test_failure_inside_the_stream_ends_it_with_an_internal_error_event(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(runs, "_SCRIPT", [("step", {"message": "ok"}), ("token", {"text": object()})])

    response = TestClient(app).post("/internal/v1/runs", json=RUN_BODY, headers=AUTH)

    frames = parse_sse(response.text)
    assert [event for event, _ in frames] == ["step", "error"]
    assert frames[-1][1]["data"] == INTERNAL_ERROR["error"]
    assert "object" not in response.text and "Traceback" not in response.text
    schema_validator(load_spec("internal"), "EventEnvelope").validate(frames[-1][1])
    assert runs._active == {}
