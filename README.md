# Nexora

**Agentic AI Assistant**

Agentic assistant with live web research, tool calling, evidence-backed answers and user-controlled memory.

> **Status: implementation NOT STARTED.** This repository currently contains only this README, a `.gitignore` and tooling safeguards. Everything below describes the planned and approved direction, not working software. No dependencies are installed by this repository, and the planned Docker-based environment has not been verified.

## Purpose
Nexora answers ordinary questions directly and researches current questions on the live web. It cites its sources, checks its own claims against the retrieved evidence, streams progress to the browser, keeps conversation history, and keeps user-controlled memory. Research runs continue in the background if the browser disconnects.

## Planned architecture
- A React single-page app talks only to the Java backend.
- The Java backend owns authentication, PostgreSQL, run lifecycle, memory, summaries and background jobs, and is the only Redis client.
- A stateless Python AI service runs the LangGraph workflow, calls the model and search providers, and verifies claims. It streams SSE events to Java, which relays them to the browser. Python never accesses PostgreSQL or Redis.

## Planned stack
Version numbers are approved targets, not verified or installed.
- **Frontend:** React, TypeScript, Vite (Node.js 22 LTS, npm)
- **Backend:** Java 21, Spring Boot 4.1.x (Spring MVC, virtual threads, Spring RestClient), Maven
- **AI service:** Python 3.13.x, FastAPI, LangGraph, uv, official OpenAI Python SDK (Responses API)
- **Search:** Tavily (primary), Brave (fallback)
- **Data:** PostgreSQL 18.7 and Redis, run in Docker Compose
- **Communication:** REST between services; SSE streaming (POST with Fetch streaming in the browser)
- **Contracts:** contract-first OpenAPI, with separate public and internal contracts
- **Testing:** layered automated tests (JUnit 5, Mockito, pytest, Playwright, integration and contract tests)

## Planned layout
- `backend/`  (Java, Spring Boot)
- `ai-service/`  (Python, FastAPI, LangGraph)
- `frontend/`  (React, TypeScript)
- `docs/`  (non-Markdown only: OpenAPI documents, diagrams)

None of these directories exist yet, and the layout is not final.

## Development prerequisites
Planned: Java 21, Maven, Python 3.13 with uv, Node.js 22 LTS with npm, and Docker with Docker Compose. Compatibility with the target versions has not been verified.

## Configuration
Configuration will use environment variables. A `.env.example` listing the variable names will be added when the first module needs configuration; it does not exist yet. Never commit secrets.

## Planned local development
Local development is planned around Docker Compose for PostgreSQL and Redis, with the three application services run locally or in Compose. The Compose setup does not exist yet, and Docker is not confirmed operational. Setup instructions will be added as modules are implemented.

## Documentation
Detailed architecture, API and data-model notes, AI behaviour, risks and acceptance criteria, and the roadmap are kept outside this repository, in the local project documentation folder.

## Known limitations
Nothing is built yet. No features, tests or deployments exist.
