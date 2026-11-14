from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health_returns_ok_without_credentials() -> None:
    response = client.get("/internal/v1/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_unknown_internal_path_is_not_found() -> None:
    assert client.get("/internal/v1/missing").status_code == 404
