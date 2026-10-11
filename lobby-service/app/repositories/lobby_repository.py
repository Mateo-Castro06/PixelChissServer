import time

import redis.asyncio as redis

from app.core import config
from app.models.enums import LobbyStatus, PlayerRole
from app.models.lobby import Lobby, Player

ADDED = "ADDED"
ALREADY_IN = "ALREADY_IN"
FULL = "FULL"
ALREADY_STARTED = "ALREADY_STARTED"
NOT_FOUND = "NOT_FOUND"

_ADD_PLAYER_LUA = """
local status = redis.call('HGET', KEYS[2], 'status')
if not status then return -2 end
if status ~= 'WAITING' then return -1 end
if redis.call('ZSCORE', KEYS[1], ARGV[1]) then return 2 end
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then return 0 end
redis.call('ZADD', KEYS[1], ARGV[3], ARGV[1])
redis.call('HSET', KEYS[3], ARGV[1], ARGV[4])
redis.call('HINCRBY', KEYS[2], 'version', 1)
return 1
"""
_LUA_RESULTS = {1: ADDED, 2: ALREADY_IN, 0: FULL, -1: ALREADY_STARTED, -2: NOT_FOUND}


def _lobby_key(code: str):
    return f"lobby:{code}"


def _players_key(code: str):
    return f"lobby:{code}:players"


def _names_key(code: str):
    return f"lobby:{code}:names"


class LobbyRepository:
    def __init__(self, r: redis.Redis):
        self.r = r
        self._add_player_script = r.register_script(_ADD_PLAYER_LUA)

    async def create(self, code: str, host_id: str, host_name: str):
        created = await self.r.hsetnx(_lobby_key(code), "host_id", host_id)
        if not created:
            return False
        await self.r.hset(_lobby_key(code), mapping={
            "creator_id": host_id,
            "status": LobbyStatus.WAITING.value,
            "version": 1,
        })
        await self.r.zadd(_players_key(code), {host_id: time.time()})
        await self.r.hset(_names_key(code), host_id, host_name)
        await self._renew_ttl(code)
        return True

    async def add_player(self, code: str, user_id: str, display_name: str):
        result = await self._add_player_script(
            keys=[_players_key(code), _lobby_key(code), _names_key(code)],
            args=[user_id, config.MAX_PLAYERS, time.time(), display_name],
        )
        if result == 1:
            await self._renew_ttl(code)
        return _LUA_RESULTS.get(result, NOT_FOUND)

    async def get(self, code: str):
        data = await self.r.hgetall(_lobby_key(code))
        if not data:
            return None
        members = await self.r.zrange(_players_key(code), 0, -1)
        names = await self.r.hgetall(_names_key(code))
        players = [
            Player(
                user_id=user_id,
                display_name=names.get(user_id, user_id),
                role=PlayerRole.HOST if user_id == data["host_id"] else PlayerRole.PLAYER,
            )
            for user_id in members
        ]
        return Lobby(
            code=code,
            host_id=data["host_id"],
            creator_id=data.get("creator_id", data["host_id"]),
            status=LobbyStatus(data["status"]),
            version=int(data.get("version", 1)),
            players=players,
        )

    async def _renew_ttl(self, code: str):
        for key in (_lobby_key(code), _players_key(code), _names_key(code)):
            await self.r.expire(key, config.LOBBY_TIMEOUT)