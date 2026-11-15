import pytest


@pytest.fixture(autouse=True)
def internal_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("NEXORA_INTERNAL_SHARED_SECRET", "test-secret")
    monkeypatch.setenv("NEXORA_FAKE_EVENT_DELAY_SECONDS", "0")
