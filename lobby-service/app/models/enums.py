from enum import StrEnum

class LobbyStatus(StrEnum):
    WAITING = "WAITING"
    STARTED = "STARTED"

class PlayerRole(StrEnum):
    HOST = "HOST"
    PLAYER = "PLAYER"