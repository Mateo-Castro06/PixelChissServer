import json
import logging
import re
from datetime import datetime

from redis.exceptions import ResponseError

from app.application.ports import ConfirmStart
from app.observability.metrics import EVENT, PENDING

log = logging.getLogger(__name__)
GROUP = "lobby-game-initialization-v1"
MIN_IDLE_MS = 5000
MAX_DELIVERIES = 5


def decode(data: str) -> tuple[str, str, str]:
    event = json.loads(data)
    if not isinstance(event, dict):
        raise ValueError("Invalid event envelope")
    game = event["gameId"]
    lobby = event["payload"]["lobbyId"]
    map_id = event["payload"]["mapId"]
    timestamp = datetime.fromisoformat(event["timestamp"])
    if (event["eventType"] != "GameInitialized" or event["schemaVersion"] != 1
            or event["producer"] != "game-service" or event["correlationId"] != game
            or event["eventId"] != f"game-initialized:{game}" or timestamp.tzinfo is None
            or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", game)
            or not isinstance(lobby, str) or not 1 <= len(lobby) <= 80
            or not isinstance(map_id, str) or not map_id):
        raise ValueError("Unsupported event")
    return lobby, map_id, game


class GameInitializedConsumer:
    def __init__(self, redis, stream: str, consumer: str, confirm: ConfirmStart):
        self.redis = redis
        self.stream = stream
        self.consumer = consumer
        self.confirm = confirm
        self.cursor = "0-0"

    async def ensure_group(self):
        try:
            await self.redis.xgroup_create(self.stream, GROUP, id="0-0", mkstream=True)
        except ResponseError as error:
            if not str(error).startswith("BUSYGROUP"):
                raise

    async def poll(self):
        claimed = await self.redis.xautoclaim(
            self.stream, GROUP, self.consumer, MIN_IDLE_MS, self.cursor, count=16,
        )
        self.cursor, messages = claimed[:2]
        for entry_id, fields in messages:
            await self.process(entry_id, fields)
        for _, entries in await self.redis.xreadgroup(
            GROUP, self.consumer, {self.stream: ">"}, count=16, block=1000,
        ):
            for entry_id, fields in entries:
                await self.process(entry_id, fields)

        PENDING.set((await self.redis.xpending(self.stream, GROUP))["pending"])

    async def process(self, entry_id, fields):
        try:
            lobby_id, map_id, game_id = decode(fields["event"])
        except (ValueError, KeyError, TypeError, OverflowError):
            await self.dead_letter(entry_id, "INVALID_CONTRACT")
            return

        try:
            result = await self.confirm(lobby_id, map_id, game_id)
        except Exception as error:
            # An unconfirmed operation is retried even if the previous response was lost.
            EVENT.labels("retry").inc()
            log.warning("Confirmación de evento pendiente (%s)", type(error).__name__)
            pending = await self.redis.xpending_range(self.stream, GROUP, entry_id, entry_id, 1)
            if pending and pending[0]["times_delivered"] >= MAX_DELIVERIES:
                await self.dead_letter(entry_id, "RETRY_EXHAUSTED")
            return

        await self.redis.xack(self.stream, GROUP, entry_id)
        EVENT.labels(result.lower()).inc()
        return result

    async def dead_letter(self, entry_id, reason):
        # Preserve the source ID for diagnosis, without copying untrusted payloads.
        await self.redis.xadd(self.stream + ":failed", {"sourceId": entry_id, "reason": reason},
                              maxlen=1000, approximate=True)
        await self.redis.xack(self.stream, GROUP, entry_id)
        EVENT.labels(reason.lower()).inc()
        log.warning("Evento de inicio derivado a fallidos (%s)", reason)
