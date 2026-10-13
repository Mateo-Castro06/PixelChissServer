from dataclasses import dataclass, field
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
    voted_map_id: Optional[str] = None

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
    map_votes: dict[str, int] = field(default_factory=dict)
    banned_players: list[str] = field(default_factory=list)
    game_id: Optional[str] = None
