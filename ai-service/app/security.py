import hmac
import os
from collections.abc import Callable, Coroutine
from typing import Any

from fastapi import Request, Response
from fastapi.routing import APIRoute

from app.errors import ApiError


def require_internal_secret(provided: str | None) -> None:
    configured = os.environ.get("NEXORA_INTERNAL_SHARED_SECRET", "")
    # Fail closed: with no configured secret nothing is accepted, not even an empty header.
    if not configured or provided is None:
        raise ApiError(401, "INTERNAL_AUTH_FAILED", "Internal authentication failed.")
    if not hmac.compare_digest(provided.encode(), configured.encode()):
        raise ApiError(401, "INTERNAL_AUTH_FAILED", "Internal authentication failed.")


class AuthenticatedRoute(APIRoute):
    # A router dependency runs only after FastAPI has parsed the body, so malformed JSON would
    # get a 400 before authentication. Checking here keeps every rejection ahead of the body.
    def get_route_handler(self) -> Callable[[Request], Coroutine[Any, Any, Response]]:
        handler = super().get_route_handler()

        async def authenticated(request: Request) -> Response:
            require_internal_secret(request.headers.get("x-nexora-internal-secret"))
            return await handler(request)

        return authenticated
