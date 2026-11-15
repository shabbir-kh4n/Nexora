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
    ("run-not-found-response", "ErrorResponse"),
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
        ("public", "run-snapshot-cancelled", "RunSnapshot"),
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


def test_snapshot_rejects_malformed_snapshots() -> None:
    validator = _validator(load_spec("public"), "RunSnapshot")
    snapshot = load_fixture("run-snapshot")
    bad_snapshots = [
        {k: v for k, v in snapshot.items() if k != "steps"},
        {k: v for k, v in snapshot.items() if k != "updated_at"},
        {**snapshot, "status": "PAUSED"},
        {**snapshot, "steps": [{"message": "ok", "extra": 1}]},
        {**snapshot, "steps": [{}]},
        {**snapshot, "steps": "none"},
        {**snapshot, "created_at": "2026-11-15T10:00:00+05:30"},
        {**snapshot, "sources": []},
        {**snapshot, "verification": {}},
    ]

    for bad in bad_snapshots:
        assert not validator.is_valid(bad), bad


def test_get_and_cancel_return_the_same_snapshot_schema_with_the_error_envelope() -> None:
    paths = load_spec("public")["paths"]
    get = paths["/api/v1/runs/{runId}"]["get"]["responses"]
    cancel = paths["/api/v1/runs/{runId}/cancel"]["post"]["responses"]
    snapshot_ref = {"$ref": "#/components/schemas/RunSnapshot"}
    error_ref = {"$ref": "#/components/responses/Error"}

    assert get["200"]["content"]["application/json"]["schema"] == snapshot_ref
    assert cancel["200"]["content"]["application/json"]["schema"] == snapshot_ref
    for responses in (get, cancel):
        assert responses["400"] == error_ref and responses["404"] == error_ref


def test_snapshot_lifecycle_is_the_approved_s1_set() -> None:
    schema = load_spec("public")["components"]["schemas"]["RunSnapshot"]

    assert schema["properties"]["status"]["enum"] == ["QUEUED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED"]
    assert set(schema["properties"]) == {"run_id", "status", "output", "steps", "created_at", "updated_at"}


def test_a_snapshot_can_be_built_from_the_live_event_stream() -> None:
    frames = parse_sse(client.post("/internal/v1/runs", json=RUN_BODY, headers=AUTH).text)
    envelopes = [envelope for _, envelope in frames]

    snapshot = {
        "run_id": RUN_BODY["run_id"],
        "status": "COMPLETED" if envelopes[-1]["type"] == "done" else "FAILED",
        "output": "".join(e["data"]["text"] for e in envelopes if e["type"] == "token"),
        "steps": [{"message": e["data"]["message"]} for e in envelopes if e["type"] == "step"],
        "created_at": envelopes[0]["timestamp"],
        "updated_at": envelopes[-1]["timestamp"],
    }

    _validator(load_spec("public"), "RunSnapshot").validate(snapshot)
    assert snapshot["output"] == "This is a simulated answer."
    assert len(snapshot["steps"]) == 2
