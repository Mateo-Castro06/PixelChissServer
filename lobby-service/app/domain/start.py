from dataclasses import dataclass
from typing import Callable

from app.models.lobby import Lobby

SEATS_BY_COUNT = {2: (0, 2), 3: (0, 1, 2), 4: (0, 1, 2, 3)}


@dataclass(frozen=True)
class Seat:
    player_id: str
    bot: bool
    number: int


@dataclass(frozen=True)
class GameInitialization:
    """Immutable plan. Once stored, retries use this exact plan."""
    game_id: str
    lobby_id: str
    map_id: str
    creator_id: str
    seats: tuple[Seat, ...]

    @classmethod
    def select(cls, lobby: Lobby, game_id: str, choose: Callable[[list[str]], str]):
        best = max(lobby.map_votes.values())
        winners = [m for m, votes in lobby.map_votes.items() if votes == best]
        members = {player.user_id for player in lobby.players}
        creator = lobby.creator_id if lobby.creator_id in members else lobby.host_id
        return cls(game_id, lobby.code, choose(winners), creator, tuple(
            Seat(player.user_id, player.is_bot, seat)
            for player, seat in zip(lobby.players, SEATS_BY_COUNT[len(lobby.players)], strict=True)
        ))
