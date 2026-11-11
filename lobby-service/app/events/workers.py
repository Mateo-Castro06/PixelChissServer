import asyncio
import logging
import os
from uuid import uuid4

from redis.asyncio import Redis

from app.application.ports import ConfirmStart
from app.application.start_game import StartGame
from app.clients.game_client import GameClient
from app.core.redis import get_redis
from app.events.game_initialized import GameInitializedConsumer
from app.repositories.lobby_repository import LobbyRepository

log = logging.getLogger(__name__)


async def consume(confirm: ConfirmStart):
    url = os.getenv("GAME_EVENTS_REDIS_URL", "redis://localhost:6379/0")
    stream = os.getenv("GAME_EVENTS_STREAM", "pixelchiss:game:integration")
    async with Redis.from_url(url, decode_responses=True) as connection:
        consumer = GameInitializedConsumer(connection, stream, uuid4().hex, confirm)
        while True:
            try:
                await consumer.ensure_group()
                while True:
                    await consumer.poll()
            except Exception as error:
                log.warning("Lectura del bus interrumpida (%s)", type(error).__name__)
                await asyncio.sleep(2)


async def recover(start):
    while True:
        try:
            await start.recover_due()
        except Exception as error:
            log.warning("Recuperación de inicio interrumpida (%s)", type(error).__name__)
        await asyncio.sleep(2)


def launch():
    start = StartGame(LobbyRepository(get_redis()), GameClient())
    return [asyncio.create_task(consume(start.confirm)), asyncio.create_task(recover(start))]
