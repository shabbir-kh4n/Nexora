import asyncio
import json
import logging
import os
import time
import uuid
from collections.abc import AsyncIterator
from datetime import UTC, datetime
from typing import Annotated, Any

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field

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

# Process-local on purpose: cancel state is transient, so the service runs as a single worker.
_active: dict[uuid.UUID, asyncio.Event] = {}


class RunRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    run_id: uuid.UUID
    question: Annotated[str, Field(min_length=1, max_length=2000)]


def _frame(run_id: uuid.UUID, event_type: str, data: dict[str, Any]) -> str:
    envelope = {
        "version": "1",
        "event_id": str(uuid.uuid4()),
        "run_id": str(run_id),
        "type": event_type,
        "timestamp": datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
        "data": data,
    }
    return f"event: {event_type}\ndata: {json.dumps(envelope, separators=(',', ':'))}\n\n"


async def _run_events(run_id: uuid.UUID, cancelled: asyncio.Event) -> AsyncIterator[str]:
    delay = float(os.environ.get("NEXORA_FAKE_EVENT_DELAY_SECONDS", "1.0"))
    heartbeat_every = float(os.environ.get("NEXORA_HEARTBEAT_INTERVAL_SECONDS", "15"))
    last_sent = time.monotonic()
    try:
        for event_type, data in _SCRIPT:
            deadline = time.monotonic() + delay
            while True:
                wait = min(deadline, last_sent + heartbeat_every) - time.monotonic()
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
                if now >= last_sent + heartbeat_every:
                    last_sent = now
                    yield _frame(run_id, "heartbeat", {})
            last_sent = time.monotonic()
            yield _frame(run_id, event_type, data)
    except Exception:
        # The response has already started, so failure is reported as the terminal error event.
        logger.exception("Run %s failed", run_id)
        yield _frame(run_id, "error", {"code": "INTERNAL_ERROR", "message": INTERNAL_ERROR_MESSAGE})
    finally:
        _active.pop(run_id, None)


@router.post("/runs")
async def create_run(request: RunRequest) -> StreamingResponse:
    if request.run_id in _active:
        raise ApiError(400, "INVALID_REQUEST", "The request is invalid.")
    cancelled = asyncio.Event()
    # Registered before the response starts so a cancel cannot miss a run that is about to stream.
    _active[request.run_id] = cancelled
    return StreamingResponse(
        _run_events(request.run_id, cancelled),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@router.post("/runs/{run_id}/cancel", status_code=204)
async def cancel_run(run_id: uuid.UUID) -> None:
    cancelled = _active.get(run_id)
    if cancelled is not None:
        cancelled.set()
