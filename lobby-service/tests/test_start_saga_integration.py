"""Opt in with isolated Redis and Game. Does not delete Redis or MongoDB data."""
import asyncio
import json
import os
import secrets
import unittest
from unittest.mock import patch

import httpx
from redis.asyncio import Redis

from app.application import ports
from app.application.start_game import StartGame
from app.clients.game_client import GameClient
from app.core.exceptions import GameStartError
from app.events.game_initialized import GROUP, GameInitializedConsumer
from app.models.enums import LobbyStatus
from app.repositories.lobby_repository import LobbyRepository

LOBBY_REDIS = os.getenv("SAGA_TEST_LOBBY_REDIS")
GAME_REDIS = os.getenv("SAGA_TEST_GAME_REDIS")
GAME_HTTP = os.getenv("SAGA_TEST_GAME_HTTP")
GAME_STREAM = os.getenv("SAGA_TEST_GAME_STREAM", "pixelchiss:game:integration")


@unittest.skipUnless(LOBBY_REDIS and GAME_REDIS and GAME_HTTP, "requires isolated Redis and Game")
class SagaIntegration(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.lobby_redis = Redis.from_url(LOBBY_REDIS, decode_responses=True)
        self.game_redis = Redis.from_url(GAME_REDIS, decode_responses=True)
        self.code = secrets.token_hex(3).upper()
        self.store = LobbyRepository(self.lobby_redis)
        self.client = GameClient()
        self.start = StartGame(self.store, self.client)

        async def discover(*, app_name, service, walker):
            self.assertEqual((app_name, service), ("GAME-SERVICE", "/internal/games"))
            return await walker(GAME_HTTP + service)

        self.enterContext(patch("app.clients.game_client.eureka_client.walk_nodes_async", discover))
        await self.store.create(self.code, "host", "Host")
        await self.store.add_bot(self.code, "host")

    async def asyncTearDown(self):
        await self.lobby_redis.aclose()
        await self.game_redis.aclose()

    async def reserve(self):
        result, plan = await self.start.reserve(self.code, "host")
        self.assertEqual(result, ports.START_BEGUN)
        return plan

    async def event(self, plan):
        for entry_id, fields in await self.game_redis.xrevrange(GAME_STREAM, count=100):
            if json.loads(fields["event"])["gameId"] == plan.game_id:
                return entry_id, fields
        self.fail("Game did not publish an integration event")

    async def make_due(self, plan):
        await self.lobby_redis.zadd("pixelchiss:lobby:start-due", {f"{self.code}:{plan.game_id}": 0})

    async def test_normal_and_duplicate_keep_single_creation(self):
        plan = await self.reserve()
        result = await self.start.start_reserved(plan)
        self.assertEqual((result.status, result.game_id), (LobbyStatus.STARTED, plan.game_id))
        ttl = await self.lobby_redis.ttl(f"lobby:{self.code}:players")
        self.assertGreaterEqual(ttl, 7195)
        entry_id, _ = await self.event(plan)
        await self.client.create_game(plan)
        self.assertEqual((await self.event(plan))[0], entry_id)
        self.assertEqual(sum(json.loads(fields["event"])["gameId"] == plan.game_id
                             for _, fields in await self.game_redis.xrange(GAME_STREAM)), 1)

    async def test_lost_http_response_resolved_by_real_stream(self):
        plan = await self.reserve()

        class LostResponse:
            async def create_game(inner, request):
                await self.client.create_game(request)
                raise GameStartError(503, "Response lost after commit")

        with self.assertRaises(GameStartError):
            await StartGame(self.store, LostResponse()).start_reserved(plan)
        self.assertEqual((await self.store.get(self.code)).status, LobbyStatus.STARTING)
        # A fresh repository and coordinator simulate a process restart without local state.
        fresh = StartGame(LobbyRepository(self.lobby_redis), self.client)
        consumer = GameInitializedConsumer(self.game_redis, GAME_STREAM, "integration-restart", fresh.confirm)
        await consumer.ensure_group()
        for _ in range(10):
            await consumer.poll()
            if (await self.store.get(self.code)).status == LobbyStatus.STARTED:
                break
        lobby = await self.store.get(self.code)
        self.assertEqual(lobby.game_id, plan.game_id)
        entry_id, fields = await self.event(plan)
        self.assertEqual(await consumer.process(entry_id, fields), "DUPLICATE")
        self.assertEqual((await self.store.get(self.code)).version, lobby.version)
        self.assertEqual(await self.game_redis.xpending_range(GAME_STREAM, GROUP, entry_id, entry_id, 1), [])

    async def test_interrupted_lobby_and_temporarily_unavailable_game(self):
        plan = await self.reserve()  # interruption before any HTTP dispatch

        class Unavailable:
            async def create_game(inner, request):
                raise GameStartError(503, "Game offline")

        await self.make_due(plan)
        await StartGame(LobbyRepository(self.lobby_redis), Unavailable()).recover_due()
        self.assertEqual((await self.store.get(self.code)).status, LobbyStatus.STARTING)
        await self.make_due(plan)
        await StartGame(LobbyRepository(self.lobby_redis), self.client).recover_due()
        self.assertEqual((await self.store.get(self.code)).game_id, plan.game_id)

    async def test_failed_confirmation_and_exclusive_retry_claim(self):
        plan = await self.reserve()
        with patch.object(self.store, "finish_start", side_effect=ConnectionError("Lobby Redis unreachable")):
            with self.assertRaises(ConnectionError):
                await self.start.start_reserved(plan)
        await self.make_due(plan)
        outcomes = await asyncio.gather(*(self.store.claim_start(f"{self.code}:{plan.game_id}") for _ in range(2)))
        self.assertEqual({outcome for outcome, _ in outcomes}, {"RETRY", "SKIPPED"})
        await self.make_due(plan)
        await self.start.recover_due()
        self.assertEqual((await self.store.get(self.code)).game_id, plan.game_id)

    async def test_definite_rejection_compensates_and_ignores_stale_confirmation(self):
        original = await self.reserve()
        with patch("app.clients.game_client.config.GAME_LOBBY_TOKEN", "x" * 32):
            with self.assertRaises(GameStartError) as error:
                await self.start.start_reserved(original)
        self.assertTrue(error.exception.definite)
        self.assertEqual(error.exception.status_code, 502)
        self.assertEqual((await self.store.get(self.code)).status, LobbyStatus.WAITING)
        second = await self.reserve()
        self.assertEqual(await self.start.confirm(self.code, original.map_id, original.game_id), "IGNORED")
        self.assertEqual((await self.start.start_reserved(second)).game_id, second.game_id)

    async def test_concurrent_starts_reserve_only_one(self):
        first, second = await asyncio.gather(self.start.reserve(self.code, "host"),
                                            self.start.reserve(self.code, "host"))
        self.assertEqual({first[0], second[0]}, {ports.START_BEGUN, ports.ALREADY_STARTED})

    async def test_pending_message_can_be_claimed_and_invalid_message_is_isolated(self):
        plan = await self.reserve()
        await self.client.create_game(plan)
        entry_id, _ = await self.event(plan)
        test_stream = GAME_STREAM + ":" + self.code
        consumer = GameInitializedConsumer(self.game_redis, test_stream, "replacement", self.start.confirm)
        await consumer.ensure_group()
        original = (await self.event(plan))[1]
        valid = await self.game_redis.xadd(test_stream, original)
        invalid = await self.game_redis.xadd(test_stream, {"event": "{}"})
        await self.game_redis.xreadgroup(GROUP, "interrupted", {test_stream: ">"}, count=2)
        with patch("app.events.game_initialized.MIN_IDLE_MS", 0):
            await consumer.poll()
        self.assertEqual((await self.store.get(self.code)).game_id, plan.game_id)
        self.assertEqual((await self.game_redis.xpending(test_stream, GROUP))["pending"], 0)
        failed = await self.game_redis.xrange(test_stream + ":failed")
        self.assertEqual([fields["sourceId"] for _, fields in failed], [invalid])
        self.assertNotEqual(valid, invalid)
        self.assertTrue(entry_id)
