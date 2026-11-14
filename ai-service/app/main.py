# Stateless and internal-only: no PostgreSQL or Redis access, no persistence.

from fastapi import FastAPI

app = FastAPI(title="Nexora AI Service")


# Exempt from shared-secret authentication so container healthchecks need no credentials.
@app.get("/internal/v1/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
