import asyncio
import json
import logging
import math
import os
import time
import uuid
from collections.abc import AsyncIterator
from datetime import UTC, datetime
from typing import Annotated, Any, NamedTuple

from fastapi import APIRouter, Path
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field, StringConstraints
from starlette.types import Receive, Scope, Send

from app.errors import ApiError, INTERNAL_ERROR_MESSAGE
from app.security import AuthenticatedRoute

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/internal/v1", route_class=AuthenticatedRoute)

_SCRIPT: list[tuple[str, dict[str, Any]]] = [
    ("step", {"message": "Planning the research (simulated)"}),
    ("step", {"message": "Composing the answer (simulated)"}),
    *(("token", {"text": text}) for text in ("This ", "is ", "a ", "simulated ", "answer.")),
    ("done", {}),
]

# The form Java's UUID.toString() produces. Other spellings are rejected so ids compare as plain strings.
CANONICAL_UUID = r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
RunId = Annotated[str, StringConstraints(pattern=CANONICAL_UUID)]

# Process-local on purpose: cancel state is transient, so the service runs as a single worker.
_active: dict[str, asyncio.Event] = {}


class RunRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    run_id: RunId
    question: Annotated[str, Field(min_length=1, max_length=2000)]


class Settings(NamedTuple):
    event_delay: float
    heartbeat_interval: float


def _read_seconds(name: str, default: str, allow_zero: bool) -> float:
    try:
        value = float(os.environ.get(name, default))
    except ValueError:
        raise ValueError(f"{name} must be a number of seconds") from None
    if not math.isfinite(value) or value < 0 or (value == 0 and not allow_zero):
        raise ValueError(f"{name} must be {'zero or more' if allow_zero else 'greater than zero'}")
    return value


def load_settings() -> Settings:
    return Settings(
        event_delay=_read_seconds("NEXORA_FAKE_EVENT_DELAY_SECONDS", "1.0", allow_zero=True),
        heartbeat_interval=_read_seconds("NEXORA_HEARTBEAT_INTERVAL_SECONDS", "15", allow_zero=False),
    )


def _frame(run_id: str, event_type: str, data: dict[str, Any]) -> str:
    envelope = {
        "version": "1",
        "event_id": str(uuid.uuid4()),
        "run_id": run_id,
        "type": event_type,
        "timestamp": datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
        "data": data,
    }
    return f"event: {event_type}\ndata: {json.dumps(envelope, separators=(',', ':'))}\n\n"


async def _run_events(run_id: str, cancelled: asyncio.Event, settings: Settings) -> AsyncIterator[str]:
    try:
        last_sent = time.monotonic()
        for event_type, data in _SCRIPT:
            deadline = time.monotonic() + settings.event_delay
            while True:
                wait = min(deadline, last_sent + settings.heartbeat_interval) - time.monotonic()
                if wait > 0:
                    try:
                        await asyncio.wait_for(cancelled.wait(), wait)
                    except TimeoutError:
                        pass
                if cancelled.is_set():
                    return
                now = time.monotonic()
                if now >= deadline:
                    break
                if now >= last_sent + settings.heartbeat_interval:
                    last_sent = now
                    yield _frame(run_id, "heartbeat", {})
            last_sent = time.monotonic()
            yield _frame(run_id, event_type, data)
    except Exception:
        # The response has already started, so failure is reported as the terminal error event.
        logger.exception("Run %s failed", run_id)
        yield _frame(run_id, "error", {"code": "INTERNAL_ERROR", "message": INTERNAL_ERROR_MESSAGE})


class _RunResponse(StreamingResponse):
    def __init__(self, run_id: str, cancelled: asyncio.Event, settings: Settings) -> None:
        super().__init__(
            _run_events(run_id, cancelled, settings),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )
        self._run_id = run_id

    # Cleanup lives here because a generator that never started cannot clean up after itself,
    # which happens when the client disconnects before the first chunk is sent.
    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        try:
            await super().__call__(scope, receive, send)
        finally:
            _active.pop(self._run_id, None)


@router.post("/runs")
async def create_run(request: RunRequest) -> StreamingResponse:
    # Validated before registering the run, so a bad setting can never leave an entry behind.
    settings = load_settings()
    if request.run_id in _active:
        raise ApiError(400, "INVALID_REQUEST", "The request is invalid.")
    cancelled = asyncio.Event()
    # Registered before the response starts so a cancel cannot miss a run that is about to stream.
    _active[request.run_id] = cancelled
    return _RunResponse(request.run_id, cancelled, settings)


@router.post("/runs/{run_id}/cancel", status_code=204)
async def cancel_run(run_id: Annotated[str, Path(pattern=CANONICAL_UUID)]) -> None:
    cancelled = _active.get(run_id)
    if cancelled is not None:
        cancelled.set()
