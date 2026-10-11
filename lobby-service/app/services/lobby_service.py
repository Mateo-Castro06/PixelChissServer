import secrets

from app.clients.auth_client import AuthClient
from app.core.exceptions import (GameAlreadyStartedError, LobbyError, LobbyFullError,
                                 LobbyNotFoundError, NotInLobbyError)
from app.core.security import CurrentUser
from app.repositories import lobby_repository as repo_results
from app.repositories.lobby_repository import LobbyRepository

ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
CODE_LENGTH = 6


def _new_code():
    return "".join(secrets.choice(ALPHABET) for _ in range(CODE_LENGTH))


class LobbyService:
    def __init__(self, repo: LobbyRepository, auth_client: AuthClient):
        self.repo = repo
        self.auth_client = auth_client

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
        if result == repo_results.NOT_FOUND:
            raise LobbyNotFoundError()
        return await self.repo.get(lobby.code)

    async def get_lobby(self, code: str, user_id: str):
        lobby = await self._find(code)
        if user_id not in [p.user_id for p in lobby.players]:
            raise NotInLobbyError()
        return lobby

    async def _find(self, code: str):
        lobby = await self.repo.get(code.strip().upper())
        if lobby is None:
            raise LobbyNotFoundError()
        return lobby