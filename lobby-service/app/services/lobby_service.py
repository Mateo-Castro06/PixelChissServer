import secrets

from app.clients.auth_client import AuthClient
from app.clients.game_client import GameClient
from app.core import config
from app.core.exceptions import (NotEnoughPlayersError, NotAllReadyError, BannedError, PlayerNotFoundError, BotNotFoundError, GameAlreadyStartedError,
                                 InvalidMapError, LobbyError, LobbyFullError,
                                 LobbyNotFoundError, NotHostError, NotInLobbyError)
from app.core.security import CurrentUser
from app.models.game_map import MAP_IDS
from app.repositories import lobby_repository as repo_results
from app.repositories.lobby_repository import LobbyRepository

ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
CODE_LENGTH = 6


def _normalize(code: str):
    return code.strip().upper()


def _new_code():
    return "".join(secrets.choice(ALPHABET) for _ in range(CODE_LENGTH))

def _pick_map(map_votes: dict[str, int]):
    best = max(map_votes.values())
    return secrets.choice([m for m, votes in map_votes.items() if votes == best])


def _game_payload(lobby, map_id: str, game_id: str):
    seats = config.SEATS_BY_COUNT[len(lobby.players)]
    ids = [p.user_id for p in lobby.players]
    creator = lobby.creator_id if lobby.creator_id in ids else lobby.host_id
    return {
        "gameId": game_id,
        "lobbyId": lobby.code,
        "mapId": map_id,
        "creatorPlayerId": creator,
        "participants": [{"playerId": p.user_id, "bot": p.is_bot, "seat": s}
                         for p, s in zip(lobby.players, seats)],
    }

class LobbyService:
    def __init__(self, repo: LobbyRepository, auth_client: AuthClient, game_client: GameClient):
        self.repo = repo
        self.auth_client = auth_client
        self.game_client = game_client

    async def create_lobby(self, user: CurrentUser):
        name = await self.auth_client.get_display_name(user)
        for _ in range(10):
            code = _new_code()
            if await self.repo.create(code, user.id, name):
                return await self.repo.get(code)
        raise LobbyError(500, "No se pudo generar un código único para la sala")

    async def join_lobby(self, code: str, user: CurrentUser):
        lobby = await self._find(code)
        if user.id in [p.user_id for p in lobby.players]:
            return lobby
        name = await self.auth_client.get_display_name(user)
        result = await self.repo.add_player(lobby.code, user.id, name)
        if result == repo_results.FULL:
            raise LobbyFullError()
        if result == repo_results.ALREADY_STARTED:
            raise GameAlreadyStartedError()
        if result == repo_results.BANNED:
            raise BannedError()
        if result == repo_results.NOT_FOUND:
            raise LobbyNotFoundError()
        return await self.repo.get(lobby.code)

    async def get_lobby(self, code: str, user_id: str):
        lobby = await self._find(code)
        if user_id not in [p.user_id for p in lobby.players]:
            raise NotInLobbyError()
        return lobby

    async def leave_lobby(self, code: str, user: CurrentUser):
        result = await self.repo.leave(_normalize(code), user.id)
        self._raise_if_failed(result)

    async def set_ready(self, code: str, user: CurrentUser, ready: bool):
        code = _normalize(code)
        result = await self.repo.set_ready(code, user.id, ready)
        self._raise_if_failed(result)
        return await self.repo.get(code)

    async def add_bot(self, code: str, user: CurrentUser):
        code = _normalize(code)
        result = await self.repo.add_bot(code, user.id)
        self._raise_if_failed(result)
        return await self.repo.get(code)

    async def remove_bot(self, code: str, user: CurrentUser, bot_id: str):
        code = _normalize(code)
        result = await self.repo.remove_bot(code, user.id, bot_id)
        self._raise_if_failed(result)
        return await self.repo.get(code)

    async def kick_player(self, code: str, user: CurrentUser, target_id: str):
        code = _normalize(code)
        result = await self.repo.kick(code, user.id, target_id)
        self._raise_if_failed(result)
        return await self.repo.get(code)

    async def vote_map(self, code: str, user: CurrentUser, map_id: str):
        if map_id not in MAP_IDS:
            raise InvalidMapError()
        code = _normalize(code)
        result = await self.repo.vote(code, user.id, map_id)
        self._raise_if_failed(result)
        return await self.repo.get(code)
    
    async def _find(self, code: str):
        lobby = await self.repo.get(_normalize(code))
        if lobby is None:
            raise LobbyNotFoundError()
        return lobby

    async def start_game(self, code: str, user: CurrentUser):
        code = _normalize(code)
        self._raise_if_failed(await self.repo.begin_start(code, user.id))
        lobby = await self.repo.get(code)
        map_id = _pick_map(lobby.map_votes)
        game_id = f"game-{code}-{secrets.token_hex(4)}"
        try:
            await self.game_client.create_game(_game_payload(lobby, map_id, game_id))
        except Exception:
            await self.repo.abort_start(code)
            raise
        await self.repo.finish_start(code, map_id, game_id)
        return await self.repo.get(code)
    
    def _raise_if_failed(self, result: str):
        """Traduce los resultados de error del repositorio a su excepción."""
        if result == repo_results.NOT_FOUND:
            raise LobbyNotFoundError()
        if result == repo_results.ALREADY_STARTED:
            raise GameAlreadyStartedError()
        if result == repo_results.NOT_IN_LOBBY:
            raise NotInLobbyError()
        if result == repo_results.NOT_HOST:
            raise NotHostError()
        if result == repo_results.FULL:
            raise LobbyFullError()
        if result == repo_results.BOT_NOT_FOUND:
            raise BotNotFoundError()
        if result == repo_results.PLAYER_NOT_FOUND:
            raise PlayerNotFoundError()
        if result == repo_results.IS_BOT:
            raise LobbyError(400, "Para quitar un bot usa DELETE /api/lobbies/{code}/bots/{bot_id}")
        if result == repo_results.SELF_KICK:
            raise LobbyError(400, "No puedes expulsarte a ti mismo; usa salir de la sala")
        if result == repo_results.NOT_ENOUGH_PLAYERS:
            raise NotEnoughPlayersError()
        if result == repo_results.NOT_ALL_READY:
            raise NotAllReadyError()