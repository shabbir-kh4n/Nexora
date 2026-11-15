# Stateless and internal-only: no PostgreSQL or Redis access, no persistence.

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.errors import register_error_handlers
from app.runs import load_settings
from app.runs import router as runs_router


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    # An invalid setting stops the service at startup instead of failing every run.
    load_settings()
    yield


app = FastAPI(title="Nexora AI Service", lifespan=lifespan)
register_error_handlers(app)
app.include_router(runs_router)


# Exempt from shared-secret authentication so container healthchecks need no credentials.
@app.get("/internal/v1/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
