import unittest
from unittest.mock import AsyncMock, MagicMock

import httpx

from app.api.lobbies import get_lobby_service
from app.application.start_game import StartGame
from app.core import config
from app.core.exceptions import GameStartError, LobbyError
from app.clients.game_client import game_payload
from app.core.security import CurrentUser, get_current_user
from app.models.enums import LobbyStatus, PlayerRole
from app.models.game_map import MAP_IDS
from app.models.lobby import Lobby, Player
from app.repositories.lobby_repository import LobbyRepository, START_BEGUN
from app.schemas.lobby import LobbyResponse
from app.services.lobby_service import LobbyService
from main import app


def sample_lobby(status=LobbyStatus.WAITING):
    return Lobby(
        code="ABCDEF", host_id="host", creator_id="host", status=status,
        version=1, map_votes={map_id: 0 for map_id in MAP_IDS},
        players=[
            Player(user_id="host", display_name="Host", role=PlayerRole.HOST),
            Player(user_id="guest", display_name="Guest", role=PlayerRole.PLAYER, ready=True),
        ],
    )


class LobbyRouteTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.service = AsyncMock()
        self.service.get_lobby.return_value = sample_lobby()
        app.dependency_overrides[get_current_user] = lambda: CurrentUser("host", "", "token")
        app.dependency_overrides[get_lobby_service] = lambda: self.service
        self.client = httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        )

    async def asyncTearDown(self):
        await self.client.aclose()
        app.dependency_overrides.clear()

    async def test_maps_returns_catalog_without_querying_a_lobby(self):
        response = await self.client.get("/api/lobbies/maps")
        self.assertEqual(response.status_code, 200)
        self.assertEqual([item["id"] for item in response.json()], MAP_IDS)
        self.service.get_lobby.assert_not_awaited()

    async def test_lobby_code_route_returns_the_requested_lobby(self):
        response = await self.client.get("/api/lobbies/ABCDEF")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["code"], "ABCDEF")
        self.service.get_lobby.assert_awaited_once_with("ABCDEF", "host")


class LobbyRepositoryTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.redis = MagicMock()
        self.redis.register_script.side_effect = lambda script: AsyncMock(return_value=1)
        self.redis.expire = AsyncMock(return_value=True)
        self.repo = LobbyRepository(self.redis)

    async def test_get_exposes_banned_players_and_votes_in_response(self):
        batch = MagicMock()
        self.redis.pipeline.return_value.__aenter__.return_value = batch
        batch.execute = AsyncMock(return_value=[
            {"host_id": "host", "creator_id": "host", "status": "WAITING", "version": "3"},
            ["host", "guest"], {"host": "Host", "guest": "Guest"}, set(), {"guest"},
            {"guest": "science"}, {"expelled"},
        ])
        lobby = await self.repo.get("ABCDEF")
        response = LobbyResponse.model_validate(lobby, from_attributes=True)
        self.assertEqual(response.banned_players, ["expelled"])
        self.assertEqual(response.map_votes["science"], 1)
        self.assertTrue(response.players[1].ready)

class GameStartTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.repo = AsyncMock()
        self.repo.begin_start.return_value = START_BEGUN
        self.repo.get.return_value = sample_lobby()
        self.repo.finish_start.return_value = "CONFIRMED"
        self.game_client = AsyncMock()
        self.service = LobbyService(self.repo, AsyncMock(), StartGame(self.repo, self.game_client))
        self.user = CurrentUser("host", "", "token")

    async def test_start_sends_participants_and_finalizes_lobby(self):
        await self.service.start_game(" abcdef ", self.user)
        payload = game_payload(self.game_client.create_game.await_args.args[0])
        self.assertEqual(payload["lobbyId"], "ABCDEF")
        self.assertEqual(payload["creatorPlayerId"], "host")
        self.assertEqual(payload["participants"], [
            {"playerId": "host", "bot": False, "seat": 0},
            {"playerId": "guest", "bot": False, "seat": 2},
        ])
        self.repo.finish_start.assert_awaited_once_with(
            "ABCDEF", payload["mapId"], payload["gameId"]
        )
        self.repo.abort_start.assert_not_awaited()

    async def test_game_service_failure_releases_start_lock(self):
        self.game_client.create_game.side_effect = GameStartError(502, "Rejected before creation", definite=True)
        with self.assertRaises(LobbyError):
            await self.service.start_game("ABCDEF", self.user)
        self.repo.abort_start.assert_awaited_once_with(self.game_client.create_game.await_args.args[0])
        self.repo.finish_start.assert_not_awaited()


if __name__ == "__main__":
    unittest.main()
