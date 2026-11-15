import pytest
from fastapi.testclient import TestClient

from app import runs
from app.main import app
from helpers import AUTH, RUN_BODY, RUN_ID

client = TestClient(app)
CANCEL_URL = f"/internal/v1/runs/{RUN_ID}/cancel"
AUTH_FAILED = {"error": {"code": "INTERNAL_AUTH_FAILED", "message": "Internal authentication failed."}}


@pytest.mark.parametrize(
    "headers",
    [{}, {"X-Nexora-Internal-Secret": "wrong"}, {"X-Nexora-Internal-Secret": ""}],
)
def test_run_without_a_valid_secret_is_rejected_and_starts_nothing(headers: dict[str, str]) -> None:
    response = client.post("/internal/v1/runs", json=RUN_BODY, headers=headers)

    assert response.status_code == 401
    assert response.json() == AUTH_FAILED
    assert runs._active == {}


@pytest.mark.parametrize("headers", [{}, {"X-Nexora-Internal-Secret": "wrong"}])
def test_cancel_without_a_valid_secret_is_rejected(headers: dict[str, str]) -> None:
    response = client.post(CANCEL_URL, headers=headers)

    assert response.status_code == 401
    assert response.json() == AUTH_FAILED


@pytest.mark.parametrize("header_value", ["test-secret", "", None])
def test_every_request_is_rejected_when_no_secret_is_configured(
    monkeypatch: pytest.MonkeyPatch, header_value: str | None
) -> None:
    monkeypatch.delenv("NEXORA_INTERNAL_SHARED_SECRET")
    headers = {} if header_value is None else {"X-Nexora-Internal-Secret": header_value}

    assert client.post("/internal/v1/runs", json=RUN_BODY, headers=headers).status_code == 401
    assert client.post(CANCEL_URL, headers=headers).status_code == 401


def test_an_empty_configured_secret_accepts_nothing(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("NEXORA_INTERNAL_SHARED_SECRET", "")

    assert client.post("/internal/v1/runs", json=RUN_BODY, headers={"X-Nexora-Internal-Secret": ""}).status_code == 401


def test_authentication_is_checked_before_the_request_body_is_validated() -> None:
    response = client.post("/internal/v1/runs", json={"question": ""})

    assert response.status_code == 401


@pytest.mark.parametrize("content", [b"{not json", b"", b"\xff\xfe"])
def test_authentication_is_checked_before_the_body_is_parsed(content: bytes) -> None:
    json_type = {"Content-Type": "application/json"}

    unauthenticated = client.post("/internal/v1/runs", content=content, headers=json_type)
    wrong_secret = client.post(
        "/internal/v1/runs", content=content, headers=json_type | {"X-Nexora-Internal-Secret": "wrong"}
    )
    authenticated = client.post("/internal/v1/runs", content=content, headers=json_type | AUTH)

    assert unauthenticated.status_code == 401 and unauthenticated.json() == AUTH_FAILED
    assert wrong_secret.status_code == 401
    assert authenticated.status_code == 400
    assert runs._active == {}


def test_a_malformed_cancel_id_is_authenticated_first() -> None:
    assert client.post("/internal/v1/runs/not-a-uuid/cancel").status_code == 401


def test_a_valid_secret_is_accepted() -> None:
    assert client.post(CANCEL_URL, headers=AUTH).status_code == 204


def test_health_stays_open_with_or_without_a_configured_secret(monkeypatch: pytest.MonkeyPatch) -> None:
    assert client.get("/internal/v1/health").status_code == 200
    monkeypatch.delenv("NEXORA_INTERNAL_SHARED_SECRET")
    assert client.get("/internal/v1/health").json() == {"status": "ok"}
