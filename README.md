# Nexora

**Agentic AI Assistant**

Agentic assistant with live web research, tool calling, evidence-backed answers and user-controlled memory.

> **Status: application implementation NOT STARTED.** The S0 foundation scaffold (empty service skeletons and a Compose file) is configured, but no application feature, test of behaviour or acceptance criterion exists; the only endpoints are the two health checks. Everything below about the product describes the planned and approved direction, not working software.

## Purpose
Nexora answers ordinary questions directly and researches current questions on the live web. It cites its sources, checks its own claims against the retrieved evidence, streams progress to the browser, keeps conversation history, and keeps user-controlled memory. Research runs continue in the background if the browser disconnects.

## Planned architecture
- A React single-page app talks only to the Java backend.
- The Java backend owns authentication, PostgreSQL, run lifecycle, memory, summaries and background jobs, and is the only Redis client.
- A stateless Python AI service runs the LangGraph workflow, calls the model and search providers, and verifies claims. It streams SSE events to Java, which relays them to the browser. Python never accesses PostgreSQL or Redis.

## Planned stack
Version numbers are approved targets unless stated otherwise.
- **Frontend:** React, TypeScript, Vite (Node.js 22 LTS, npm)
- **Backend:** Java 21, Spring Boot 4.1.x (Spring MVC, virtual threads, Spring RestClient), Maven
- **AI service:** Python 3.13.x, FastAPI, LangGraph, uv, official OpenAI Python SDK (Responses API)
- **Search:** Tavily (primary), Brave (fallback)
- **Data:** PostgreSQL 18.7 and Redis, run in Docker Compose
- **Communication:** REST between services; SSE streaming (POST with Fetch streaming in the browser)
- **Contracts:** contract-first OpenAPI, with separate public and internal contracts
- **Testing:** layered automated tests (JUnit 5, Mockito, pytest, Playwright, integration and contract tests)

## Layout
- `backend/`  Java 21, Spring Boot 4.1.2, Maven, Flyway (no migrations or database connection yet). Health endpoint only.
- `ai-service/`  Python 3.13, uv, FastAPI, Uvicorn, pytest. Health endpoint only.
- `frontend/`  React, TypeScript, Vite, npm. Placeholder entry point only.
- `infrastructure/`  Docker Compose file (PostgreSQL, Redis) and `.env.example`.
- `docs/`  Not created yet (non-Markdown only: OpenAPI documents, diagrams).

The layout is not final. LangGraph, the OpenAI SDK and `eventsource-parser` are not yet dependencies.

## Development prerequisites
Java 21, Maven, Python 3.13 with uv, Node.js 22 LTS with npm, and Docker with Docker Compose.

## Configuration
Configuration uses environment variables. `infrastructure/.env.example` lists the variable names. Copy it to `infrastructure/.env` (ignored by Git) and fill in the blank values. Never commit secrets.

## Local development
Run each command from the folder named. Services listen on 127.0.0.1 by default.
- Backend (`backend/`): `mvn test`; run with `mvn package` and then `java -jar target/nexora-backend-0.0.1-SNAPSHOT.jar`. Override the bind address and port with `NEXORA_BACKEND_HOST` and `NEXORA_BACKEND_PORT`. Health: `GET http://127.0.0.1:8080/health`.
- AI service (`ai-service/`): `uv sync`, then `uv run pytest`. Run it on the loopback address, taking host and port from the environment:
  - Bash: `uv run uvicorn app.main:app --host "${NEXORA_AI_SERVICE_HOST:-127.0.0.1}" --port "${NEXORA_AI_SERVICE_PORT:-8000}"`
  - PowerShell: `uv run uvicorn app.main:app --host ($env:NEXORA_AI_SERVICE_HOST ?? "127.0.0.1") --port ($env:NEXORA_AI_SERVICE_PORT ?? "8000")`
  - Health: `GET http://127.0.0.1:8000/internal/v1/health`
- Frontend (`frontend/`): `npm install`, then `npm run build`; `npm run dev` serves on localhost port 5173.
- Compose: copy `infrastructure/.env.example` to `infrastructure/.env`, fill in the blanks, then `docker compose -f infrastructure/compose.yaml up -d` starts PostgreSQL on host port 5433 and Redis. No code uses them yet.

Pass each service only its own variables (see the headings in `infrastructure/.env.example`). Binding to a non-loopback address is only for containers whose published ports stay on 127.0.0.1.

## Documentation
Detailed architecture, API and data-model notes, AI behaviour, risks and acceptance criteria, and the roadmap are kept outside this repository, in the local project documentation folder.

## Known limitations
No features, behaviour tests or deployments exist; the only endpoints are the two health checks. The scaffold builds, but nothing here is production-ready.
