import pytest
from fastapi.testclient import TestClient
from openapi_spec_validator import validate

from app.main import app
from helpers import AUTH, RUN_BODY, load_fixture, load_spec, parse_sse
from helpers import schema_validator as _validator

client = TestClient(app)

FIXTURE_SCHEMAS = [
    ("step-event", "EventEnvelope"),
    ("token-event", "EventEnvelope"),
    ("done-event", "EventEnvelope"),
    ("heartbeat-event", "EventEnvelope"),
    ("error-event", "EventEnvelope"),
    ("error-response", "ErrorResponse"),
]


@pytest.mark.parametrize("name", ["internal", "public"])
def test_openapi_document_is_valid(name: str) -> None:
    validate(load_spec(name))


@pytest.mark.parametrize("spec_name", ["internal", "public"])
@pytest.mark.parametrize(("fixture", "schema"), FIXTURE_SCHEMAS)
def test_fixture_conforms_to_the_contract(spec_name: str, fixture: str, schema: str) -> None:
    _validator(load_spec(spec_name), schema).validate(load_fixture(fixture))


@pytest.mark.parametrize(
    ("spec_name", "fixture", "schema"),
    [
        ("internal", "internal-run-request", "RunRequest"),
        ("public", "public-run-request", "RunRequest"),
        ("public", "run-snapshot", "RunSnapshot"),
    ],
)
def test_request_and_snapshot_fixtures_conform(spec_name: str, fixture: str, schema: str) -> None:
    _validator(load_spec(spec_name), schema).validate(load_fixture(fixture))


def test_event_schemas_are_identical_in_both_contracts() -> None:
    internal = load_spec("internal")["components"]["schemas"]
    public = load_spec("public")["components"]["schemas"]
    shared = [
        "EventBase", "StepEvent", "TokenEvent", "DoneEvent", "HeartbeatEvent",
        "ErrorEvent", "EventEnvelope", "ErrorCode", "ErrorResponse",
    ]  # fmt: skip

    for name in shared:
        assert internal[name] == public[name], name


def test_envelope_rejects_malformed_events() -> None:
    validator = _validator(load_spec("internal"), "EventEnvelope")
    step = load_fixture("step-event")
    bad_events = [
        {k: v for k, v in step.items() if k != "run_id"},
        {**step, "type": "message_created"},
        {**step, "version": "2"},
        {**step, "data": {}},
        {**step, "extra": 1},
        {**load_fixture("done-event"), "data": {"unexpected": True}},
        {**load_fixture("error-event"), "data": {"code": "NOT_A_CODE", "message": "x"}},
    ]

    for event in bad_events:
        assert not validator.is_valid(event), event


def test_live_stream_events_conform_to_the_envelope() -> None:
    validator = _validator(load_spec("internal"), "EventEnvelope")

    frames = parse_sse(client.post("/internal/v1/runs", json=RUN_BODY, headers=AUTH).text)

    for event_type, envelope in frames:
        validator.validate(envelope)
        assert envelope["type"] == event_type


def test_live_error_responses_conform_to_the_error_schema() -> None:
    validator = _validator(load_spec("internal"), "ErrorResponse")

    for response in (
        client.post("/internal/v1/runs", json=RUN_BODY),
        client.post("/internal/v1/runs", json={"question": ""}, headers=AUTH),
    ):
        validator.validate(response.json())
