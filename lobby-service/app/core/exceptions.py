from app.core import config

from fastapi import Request
from fastapi.responses import JSONResponse

class LobbyError(Exception):
    def __init__(self, status_code: int, detail: str):
        self.status_code = status_code
        self.detail = detail

class LobbyNotFoundError(LobbyError):
    def __init__(self):
        super().__init__(404, "Lobby no encontrado")

class LobbyFullError(LobbyError):
    def __init__(self):
        super().__init__(409,f"Lobby lleno (maximo {config.MAX_PLAYERS} jugadores)")

class GameAlreadyStartedError(LobbyError):
    def __init__(self):
        super().__init__(409, "El juego ya ha comenzado")

class NotInLobbyError(LobbyError):
    def __init__(self):
        super().__init__(403, "No perteneces a este lobby")

class NotHostError(LobbyError):
    def __init__(self):
        super().__init__(403, "Solo el anfitrion puede realizar esta accion")


class BotNotFoundError(LobbyError):
    def __init__(self):
        super().__init__(404, "Bot no encontrado")

class InvalidMapError(LobbyError):
    def __init__(self):
        super().__init__(400, "El mapa no existe")

class BannedError(LobbyError):
    def __init__(self):
        super().__init__(403, "Fuiste expulsado de esta sala")

class PlayerNotFoundError(LobbyError):
    def __init__(self):
        super().__init__(404, "Jugador no encontrado en la sala")
               
async def lobby_error_handler(request: Request, exc: LobbyError):
    return JSONResponse(
        status_code=exc.status_code,
        content={"detail": exc.detail},
    )