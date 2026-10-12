from dataclasses import dataclass
from typing import Optional

from app.core import config
from app.models.enums import LobbyStatus, PlayerRole

@dataclass
class Player:
    user_id: str
    display_name: str
    role: PlayerRole
    ready: bool = False 
    is_bot: bool = False

@dataclass
class Lobby:
    code: str
    host_id: str
    creator_id: str
    status: LobbyStatus
    version: int
    players: list[Player]
    max_players: int = config.MAX_PLAYERS
    map_id: Optional[str] = None