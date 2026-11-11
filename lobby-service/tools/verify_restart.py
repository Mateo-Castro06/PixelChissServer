"""Three-stage proof for a dedicated Docker stack; see docs/second-cut/evidence.md."""
import argparse
import asyncio
import json
import os
import secrets
from dataclasses import asdict
from pathlib import Path

import httpx
from redis.asyncio import Redis

from app.application.ports import START_BEGUN
from app.application.start_game import StartGame
from app.clients.game_client import GameClient, game_payload
from app.domain.start import GameInitialization, Seat
from app.repositories.lobby_repository import LobbyRepository


async def main(phase, fixture):
    async with Redis.from_url(os.environ["SAGA_TEST_LOBBY_REDIS"], decode_responses=True) as lobby_redis, \
               Redis.from_url(os.environ["SAGA_TEST_GAME_REDIS"], decode_responses=True) as game_redis:
        store = LobbyRepository(lobby_redis)
        if phase == "reserve":
            code = secrets.token_hex(3).upper()
            await store.create(code, "test-restart-player", "Test")
            await store.add_bot(code, "test-restart-player")
            result, plan = await StartGame(store, GameClient()).reserve(code, "test-restart-player")
            assert result == START_BEGUN
            fixture.write_text(json.dumps(asdict(plan)))
            # In this test isolate event-driven recovery from the separate HTTP retry.
            now, _ = await lobby_redis.time()
            await lobby_redis.zadd("pixelchiss:lobby:start-due", {f"{code}:{plan.game_id}": now + 180})
            print("RESERVED")
            return
        data = json.loads(fixture.read_text())
        data["seats"] = tuple(Seat(**seat) for seat in data["seats"])
        plan = GameInitialization(**data)
        state_key = f"pixelchiss:game:state:{plan.game_id}"
        stream = os.getenv("SAGA_TEST_GAME_STREAM", "pixelchiss:game:integration")
        if phase == "publish":
            async with httpx.AsyncClient() as client:
                response = await client.post(os.environ["SAGA_TEST_GAME_HTTP"] + "/internal/games",
                                             json=game_payload(plan),
                                             headers={"X-Lobby-Token": os.environ["GAME_LOBBY_TOKEN"]})
                assert response.status_code == 200
            assert (await store.get(plan.lobby_id)).status == "STARTING"
            assert await game_redis.exists(state_key)
            print("PUBLISHED_WHILE_LOBBY_STOPPED")
        elif phase == "persisted":
            assert (await store.get(plan.lobby_id)).status == "STARTING"
            assert await game_redis.exists(state_key)
            assert any(json.loads(fields["event"])["gameId"] == plan.game_id
                       for _, fields in await game_redis.xrevrange(stream, count=100))
            print("AOF_RESTART_PRESERVED_SAGA_GAME_EVENT")
        else:
            for _ in range(20):
                room = await store.get(plan.lobby_id)
                if room.status == "STARTED":
                    assert room.game_id == plan.game_id and room.map_id == plan.map_id
                    assert await game_redis.exists(state_key)
                    assert await lobby_redis.zscore("pixelchiss:lobby:start-due",
                                                    f"{plan.lobby_id}:{plan.game_id}") is None
                    print("LIVE_LOBBY_CONSUMER_CONFIRMED_AFTER_RESTART")
                    return
                await asyncio.sleep(0.5)
            raise AssertionError("Live consumer did not confirm the pending saga")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=["reserve", "publish", "persisted", "confirmed"])
    parser.add_argument("fixture", type=Path)
    args = parser.parse_args()
    asyncio.run(main(args.phase, args.fixture))
