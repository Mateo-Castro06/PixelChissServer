import logging
from urllib.error import HTTPError

import py_eureka_client.eureka_client as eureka_client

from app.core import config
from app.core.exceptions import LobbyError

logger = logging.getLogger(__name__)


class GameClient:
    async def create_game(self, payload: dict):
        if len(config.GAME_LOBBY_TOKEN) < 32:
            raise LobbyError(503, "El inicio de partida no está configurado (falta GAME_LOBBY_TOKEN)")
        try:
            return await eureka_client.do_service_async(
                app_name=config.GAME_SERVICE_NAME,
                service="/internal/games",
                method="POST",
                return_type="json",
                data=payload,
                timeout=config.GAME_TIMEOUT,
                headers={"Content-Type": "application/json", "X-Lobby-Token": config.GAME_LOBBY_TOKEN},
            )
        except HTTPError as e:
            logger.error("El Game rechazó el inicio (%s): %s", e.code, e.read())
            raise LobbyError(502, f"El servicio de juego rechazó el inicio de la partida ({e.code})") from e
        except Exception as e:
            logger.error("No se pudo contactar al Game", exc_info=True)
            raise LobbyError(503, "El servicio de juego no está disponible") from e