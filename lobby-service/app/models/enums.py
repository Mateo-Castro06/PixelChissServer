from enum import StrEnum

class LobbyStatus(StrEnum):
    WAITING = "WAITING"
    STARTING = "STARTING"
    STARTED = "STARTED"

class PlayerRole(StrEnum):
    HOST = "HOST"
    PLAYER = "PLAYER"