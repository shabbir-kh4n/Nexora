import pytest
from fastapi.testclient import TestClient

from app import runs
from app.main import app
from helpers import AUTH, RUN_BODY, RUN_ID, parse_sse

DELAY = "NEXORA_FAKE_EVENT_DELAY_SECONDS"
HEARTBEAT = "NEXORA_HEARTBEAT_INTERVAL_SECONDS"
INTERNAL_ERROR = {"error": {"code": "INTERNAL_ERROR", "message": "An internal error occurred."}}

INVALID = [
    (HEARTBEAT, value) for value in ("0", "0.0", "-1", "-0.5", "abc", "", "nan", "inf", "-inf")
] + [(DELAY, value) for value in ("-1", "-0.001", "abc", "", "nan", "inf")]


@pytest.mark.parametrize(("variable", "value"), INVALID)
def test_invalid_settings_are_rejected_before_a_run_is_registered(
    monkeypatch: pytest.MonkeyPatch, variable: str, value: str
) -> None:
    monkeypatch.setenv(variable, value)
    client = TestClient(app, raise_server_exceptions=False)

    response = client.post("/internal/v1/runs", json=RUN_BODY, headers=AUTH)

    assert response.status_code == 500
    assert response.json() == INTERNAL_ERROR
    assert value not in response.text or value == ""
    assert runs._active == {}


@pytest.mark.parametrize(("variable", "value"), INVALID)
def test_a_rejected_run_leaves_no_trace_and_the_same_id_works_once_fixed(
    monkeypatch: pytest.MonkeyPatch, variable: str, value: str
) -> None:
    client = TestClient(app, raise_server_exceptions=False)
    monkeypatch.setenv(variable, value)
    assert client.post("/internal/v1/runs", json=RUN_BODY, headers=AUTH).status_code == 500
    assert client.post(f"/internal/v1/runs/{RUN_ID}/cancel", headers=AUTH).status_code == 204

    monkeypatch.setenv(variable, "0" if variable == DELAY else "1")
    response = client.post("/internal/v1/runs", json=RUN_BODY, headers=AUTH)

    assert response.status_code == 200
    assert [event for event, _ in parse_sse(response.text)][-1] == "done"
    assert runs._active == {}


@pytest.mark.parametrize(("variable", "value"), INVALID)
def test_the_loader_names_the_variable_without_echoing_the_value(
    monkeypatch: pytest.MonkeyPatch, variable: str, value: str
) -> None:
    monkeypatch.setenv(variable, value)

    with pytest.raises(ValueError, match=variable) as raised:
        runs.load_settings()

    assert value == "" or value not in str(raised.value).replace(variable, "")


@pytest.mark.parametrize("value", ["0.05", "1", "15", "1e-3", "0.000001"])
def test_a_positive_heartbeat_interval_is_accepted(monkeypatch: pytest.MonkeyPatch, value: str) -> None:
    monkeypatch.setenv(HEARTBEAT, value)

    assert runs.load_settings().heartbeat_interval == float(value)
    response = TestClient(app).post("/internal/v1/runs", json=RUN_BODY, headers=AUTH)
    assert [event for event, _ in parse_sse(response.text)][-1] == "done"


@pytest.mark.parametrize("value", ["0", "0.0", "0.01", "1"])
def test_a_zero_or_positive_event_delay_is_accepted(monkeypatch: pytest.MonkeyPatch, value: str) -> None:
    monkeypatch.setenv(DELAY, value)

    assert runs.load_settings().event_delay == float(value)


def test_defaults_apply_when_nothing_is_configured(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(DELAY)

    assert runs.load_settings() == runs.Settings(event_delay=1.0, heartbeat_interval=15.0)


@pytest.mark.parametrize(("variable", "value"), INVALID)
def test_the_service_refuses_to_start_with_an_invalid_setting(
    monkeypatch: pytest.MonkeyPatch, variable: str, value: str
) -> None:
    monkeypatch.setenv(variable, value)

    with pytest.raises(ValueError, match=variable):
        with TestClient(app):
            pass


def test_the_service_starts_with_valid_settings(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(HEARTBEAT, "0.5")

    with TestClient(app) as client:
        assert client.get("/internal/v1/health").json() == {"status": "ok"}
