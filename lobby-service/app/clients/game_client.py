import asyncio

import httpx
import py_eureka_client.eureka_client as eureka_client

from app.core import config
from app.core.exceptions import GameStartError
from app.domain.start import GameInitialization

# Only errors produced before Game reaches its create transaction.
_PRECOMMIT = {
    "INVALID_REQUEST", "INVALID_GAME_ID", "INVALID_PLAYERS", "INVALID_PARTICIPANTS",
    "INVALID_SEATS", "MISSING_CREATOR", "INVALID_CREATOR", "INVALID_FIRST_PLAYER",
    "UNKNOWN_MAP", "FORBIDDEN", "INTEGRATION_DISABLED", "CATALOG_UNAVAILABLE",
}


def game_payload(plan: GameInitialization) -> dict:
    return {
        "gameId": plan.game_id,
        "lobbyId": plan.lobby_id,
        "mapId": plan.map_id,
        "creatorPlayerId": plan.creator_id,
        "participants": [
            {"playerId": seat.player_id, "bot": seat.bot, "seat": seat.number}
            for seat in plan.seats
        ],
    }


class GameClient:
    async def create_game(self, plan: GameInitialization):
        if len(config.GAME_LOBBY_TOKEN) < 32:
            raise GameStartError(503, "El inicio de partida no está configurado (falta GAME_LOBBY_TOKEN)",
                                 definite=True)

        async def send(url):
            # Wrap transport errors here: Eureka must not resend a mutation before
            # the saga records that its outcome may be unknown.
            try:
                async with httpx.AsyncClient(timeout=config.GAME_TIMEOUT) as client:
                    return await client.post(url, json=game_payload(plan),
                                             headers={"X-Lobby-Token": config.GAME_LOBBY_TOKEN})
            except httpx.HTTPError as error:
                raise unavailable() from error

        try:
            async with asyncio.timeout(config.GAME_TIMEOUT):
                response = await eureka_client.walk_nodes_async(
                    app_name=config.GAME_SERVICE_NAME, service="/internal/games", walker=send
                )
        except GameStartError:
            raise
        except Exception as error:
            raise unavailable() from error

        try:
            body = response.json()
        except ValueError:
            body = None
        if response.status_code != 200:
            definite = (response.status_code in (400, 403, 503) and isinstance(body, dict)
                        and body.get("code") in _PRECOMMIT)
            raise GameStartError(502, f"El servicio de juego rechazó el inicio de la partida ({response.status_code})",
                                 definite=definite)
        if not isinstance(body, dict) or body.get("gameId") != plan.game_id or body.get("mapId") != plan.map_id:
            raise unavailable()


def unavailable():
    return GameStartError(503, "El servicio de juego no está disponible")
