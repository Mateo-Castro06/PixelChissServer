from app.core import config


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

class NotEnoughPlayersError(LobbyError):
    def __init__(self):
        super().__init__(409, f"Se necesitan al menos {config.MIN_PLAYERS} jugadores para iniciar")

class NotAllReadyError(LobbyError):
    def __init__(self):
        super().__init__(409, "Debes esperar a que todos los jugadores estén listos")

class GameStartError(LobbyError):
    def __init__(self, status_code: int, detail: str, *, definite: bool = False):
        super().__init__(status_code, detail)
        self.definite = definite
