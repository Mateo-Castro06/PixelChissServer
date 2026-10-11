from fastapi import Request
from fastapi.responses import JSONResponse

class LobbyError(Exception):
    def __init__(self, status_code: int, detail: str):
        self.status_code = status_code
        self.detail = detail

async def lobby_error_handler(request: Request, exc: LobbyError):
    return JSONResponse(
        status_code=exc.status_code,
        content={"detail": exc.detail},
    )