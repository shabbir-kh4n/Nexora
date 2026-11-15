# Stateless and internal-only: no PostgreSQL or Redis access, no persistence.

from fastapi import FastAPI

from app.errors import register_error_handlers
from app.runs import router as runs_router

app = FastAPI(title="Nexora AI Service")
register_error_handlers(app)
app.include_router(runs_router)


# Exempt from shared-secret authentication so container healthchecks need no credentials.
@app.get("/internal/v1/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
