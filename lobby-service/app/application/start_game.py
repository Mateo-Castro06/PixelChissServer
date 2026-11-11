import logging
import secrets

from app.application import ports
from app.core.exceptions import GameStartError, LobbyError
from app.domain.start import GameInitialization, SEATS_BY_COUNT

log = logging.getLogger(__name__)


class StartGame:
    """Lobby is the saga coordinator; Game owns its local creation transaction."""

    def __init__(self, store: ports.StartStore, game: ports.GameCreation):
        self.store = store
        self.game = game

    async def reserve(self, code: str, actor: str):
        while True:
            lobby = await self.store.get(code)
            plan = None
            if lobby and len(lobby.players) in SEATS_BY_COUNT:
                plan = GameInitialization.select(
                    lobby, f"game-{code}-{secrets.token_hex(4)}", secrets.choice
                )
            result = await self.store.begin_start(code, actor, lobby, plan)
            if result != ports.RETRY_SNAPSHOT:
                return result, plan

    async def start_reserved(self, plan: GameInitialization):
        try:
            await self.game.create_game(plan)
        except GameStartError as error:
            if error.definite:
                await self.store.abort_start(plan)
            # Otherwise the durable retry or the Stream confirmation resolves it.
            raise
        outcome = await self.confirm(plan.lobby_id, plan.map_id, plan.game_id)
        if outcome == "IGNORED":
            raise LobbyError(503, "El servicio de juego no está disponible")
        return await self.store.get(plan.lobby_id)

    async def confirm(self, lobby_id: str, map_id: str, game_id: str) -> str:
        return await self.store.finish_start(lobby_id, map_id, game_id)

    async def recover_due(self):
        for member in await self.store.due_starts():
            outcome, plan = await self.store.claim_start(member)
            if outcome == "MANUAL":
                log.error("Intento de inicio requiere revisión: %s", member)
            if plan is None:
                continue
            try:
                await self.game.create_game(plan)
                await self.confirm(plan.lobby_id, plan.map_id, plan.game_id)
            except GameStartError:
                # A rejection on a retry cannot prove what an earlier dispatch did.
                log.warning("Reintento de inicio sin confirmación: %s", member)
