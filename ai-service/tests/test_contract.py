from collections.abc import Callable
from typing import Any

import pytest
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator
from openapi_spec_validator import validate

from app.main import app
from helpers import (
    ACCEPTED_RUN_IDS,
    AUTH,
    REJECTED_RUN_IDS,
    RUN_BODY,
    load_fixture,
    load_spec,
    parse_sse,
)
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
        ("public", "run-snapshot-failed", "RunSnapshot"),
    ],
)
def test_request_and_snapshot_fixtures_conform(spec_name: str, fixture: str, schema: str) -> None:
    _validator(load_spec(spec_name), schema).validate(load_fixture(fixture))


def test_event_schemas_are_identical_in_both_contracts() -> None:
    internal = load_spec("internal")["components"]["schemas"]
    public = load_spec("public")["components"]["schemas"]
    shared = [
        "EventBase", "StepEvent", "TokenEvent", "DoneEvent", "HeartbeatEvent",
        "ErrorEvent", "EventEnvelope", "ErrorCode", "ErrorResponse", "RunId",
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
    assert set(schema["properties"]) == {
        "run_id", "status", "output", "steps", "created_at", "updated_at", "error",
    }  # fmt: skip
    assert schema["required"] == ["run_id", "status", "output", "steps", "created_at", "updated_at"]


@pytest.mark.parametrize("status", ["QUEUED", "RUNNING", "COMPLETED", "CANCELLED"])
def test_snapshot_error_is_only_allowed_when_the_run_failed(status: str) -> None:
    validator = _validator(load_spec("public"), "RunSnapshot")
    failed = load_fixture("run-snapshot-failed")

    assert validator.is_valid(failed)
    assert not validator.is_valid({**failed, "status": status})
    assert validator.is_valid({k: v for k, v in failed.items() if k != "error"} | {"status": status})


def test_a_failed_snapshot_requires_the_error_object() -> None:
    validator = _validator(load_spec("public"), "RunSnapshot")
    failed = load_fixture("run-snapshot-failed")

    assert validator.is_valid(failed)
    assert not validator.is_valid({k: v for k, v in failed.items() if k != "error"})


def test_snapshot_error_object_is_strict() -> None:
    validator = _validator(load_spec("public"), "RunSnapshot")
    failed = load_fixture("run-snapshot-failed")
    bad_errors = [
        {},
        {"code": "NOT_A_CODE", "message": "x"},
        {"code": "UPSTREAM_UNAVAILABLE"},
        {"message": "x"},
        {"code": "UPSTREAM_UNAVAILABLE", "message": "x", "detail": "trace"},
        "UPSTREAM_UNAVAILABLE",
        None,
    ]

    for error in bad_errors:
        assert not validator.is_valid({**failed, "error": error}), error


@pytest.mark.parametrize("name", ["run-snapshot-failed", "error-event", "error-response", "run-not-found-response"])
def test_error_fixtures_use_generic_user_safe_messages(name: str) -> None:
    fixture = load_fixture(name)
    error = fixture.get("error") or fixture["data"]

    for forbidden in ("://", "Exception", "Traceback", "localhost", "127.0.0.1", "secret", "File \""):
        assert forbidden not in error["message"], forbidden


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


@pytest.mark.parametrize("fixture", ["run-snapshot", "run-snapshot-cancelled", "run-snapshot-failed"])
def test_every_snapshot_fixture_matches_the_failed_only_error_rule(fixture: str) -> None:
    snapshot = load_fixture(fixture)

    assert ("error" in snapshot) == (snapshot["status"] == "FAILED")


def test_the_snapshot_reuses_the_shared_error_schema_and_code_list() -> None:
    schemas = load_spec("public")["components"]["schemas"]

    assert schemas["RunSnapshot"]["properties"]["error"]["$ref"] == "#/components/schemas/ErrorResponse/properties/error"
    assert schemas["ErrorResponse"]["properties"]["error"]["properties"]["code"] == {
        "$ref": "#/components/schemas/ErrorCode"
    }


RUN_ID_REF = {"$ref": "#/components/schemas/RunId"}


def test_run_id_locations_all_use_the_shared_schema() -> None:
    internal, public = load_spec("internal"), load_spec("public")

    assert public["components"]["parameters"]["RunId"]["schema"] == RUN_ID_REF
    assert public["components"]["schemas"]["RunSnapshot"]["properties"]["run_id"] == RUN_ID_REF
    assert internal["paths"]["/internal/v1/runs/{runId}/cancel"]["post"]["parameters"][0]["schema"] == RUN_ID_REF
    assert internal["components"]["schemas"]["RunRequest"]["properties"]["run_id"] == RUN_ID_REF
    for spec in (internal, public):
        assert spec["components"]["schemas"]["EventBase"]["properties"]["run_id"] == RUN_ID_REF


def test_the_run_id_schema_is_the_canonical_lower_case_uuid() -> None:
    schema = load_spec("public")["components"]["schemas"]["RunId"]

    assert schema["format"] == "uuid"
    assert schema["minLength"] == schema["maxLength"] == 36
    assert schema["pattern"] == "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"


def _run_id_validators() -> dict[str, tuple[Draft202012Validator, Callable[[str], Any]]]:
    internal, public = load_spec("internal"), load_spec("public")
    snapshot = load_fixture("run-snapshot")
    event = load_fixture("step-event")
    return {
        "internal run request": (_validator(internal, "RunRequest"), lambda v: load_fixture("internal-run-request") | {"run_id": v}),
        "internal event": (_validator(internal, "EventEnvelope"), lambda v: event | {"run_id": v}),
        "public run id (path parameter)": (_validator(public, "RunId"), lambda v: v),
        "public run snapshot": (_validator(public, "RunSnapshot"), lambda v: snapshot | {"run_id": v}),
        "public event": (_validator(public, "EventEnvelope"), lambda v: event | {"run_id": v}),
    }


@pytest.mark.parametrize("location", list(_run_id_validators()))
@pytest.mark.parametrize("run_id", ACCEPTED_RUN_IDS)
def test_every_run_id_location_accepts_canonical_ids(location: str, run_id: str) -> None:
    validator, build = _run_id_validators()[location]

    assert validator.is_valid(build(run_id))


@pytest.mark.parametrize("location", list(_run_id_validators()))
@pytest.mark.parametrize("run_id", REJECTED_RUN_IDS)
def test_every_run_id_location_rejects_non_canonical_ids(location: str, run_id: str) -> None:
    validator, build = _run_id_validators()[location]

    assert not validator.is_valid(build(run_id))


def test_failed_snapshot_with_a_non_canonical_run_id_is_still_rejected() -> None:
    validator = _validator(load_spec("public"), "RunSnapshot")
    failed = load_fixture("run-snapshot-failed")

    assert validator.is_valid(failed)
    assert not validator.is_valid(failed | {"run_id": failed["run_id"].upper()})
