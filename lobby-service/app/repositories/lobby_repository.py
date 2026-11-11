import json
import time
from dataclasses import asdict
from uuid import uuid4

import redis.asyncio as redis

from app.application.ports import (
    ADDED, ALREADY_IN, FULL, ALREADY_STARTED, NOT_FOUND, NOT_IN_LOBBY, LEFT,
    LOBBY_DELETED, CHANGED, UNCHANGED, NOT_HOST, BOT_ADDED, BOT_REMOVED, BOT_NOT_FOUND,
    BANNED, KICKED, PLAYER_NOT_FOUND, IS_BOT, SELF_KICK, NOT_ENOUGH_PLAYERS,
    NOT_ALL_READY, START_BEGUN, RETRY_SNAPSHOT,
)
from app.core import config
from app.domain.start import GameInitialization, Seat
from app.models.enums import LobbyStatus, PlayerRole
from app.models.game_map import MAP_IDS
from app.models.lobby import Lobby, Player
from app.observability.metrics import START, START_DUE

_SAGA_INDEX = "pixelchiss:lobby:start-due"
_SAGA_DELAY = 30
_SAGA_ATTEMPTS = 7
_SAGA_MIN_TTL = 300

_CREATE_LUA = """
if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
redis.call('HSET', KEYS[1], 'host_id', ARGV[1], 'creator_id', ARGV[1],
  'status', 'WAITING', 'version', '1', 'generation', ARGV[5])
redis.call('ZADD', KEYS[2], ARGV[3], ARGV[1])
redis.call('HSET', KEYS[3], ARGV[1], ARGV[2])
for _, key in ipairs(KEYS) do redis.call('EXPIRE', key, ARGV[4]) end
return 1
"""

_ADD_PLAYER_LUA = """
local status = redis.call('HGET', KEYS[2], 'status')
if not status then return -2 end
if status ~= 'WAITING' then return -1 end
if redis.call('SISMEMBER', KEYS[4], ARGV[1]) == 1 then return -3 end
if redis.call('ZSCORE', KEYS[1], ARGV[1]) then return 2 end
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then return 0 end
redis.call('ZADD', KEYS[1], ARGV[3], ARGV[1])
redis.call('HSET', KEYS[3], ARGV[1], ARGV[4])
redis.call('HINCRBY', KEYS[2], 'version', 1)
return 1
"""
_ADD_PLAYER_RESULTS = {1: ADDED, 2: ALREADY_IN, 0: FULL, -1: ALREADY_STARTED, -2: NOT_FOUND, -3: BANNED}

_LEAVE_LUA = """
local status = redis.call('HGET', KEYS[2], 'status')
if not status then return -2 end
if status ~= 'WAITING' then return -1 end
if not redis.call('ZSCORE', KEYS[1], ARGV[1]) then return -3 end
redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('HDEL', KEYS[3], ARGV[1])
redis.call('SREM', KEYS[4], ARGV[1])
redis.call('HDEL', KEYS[6], ARGV[1])
redis.call('SREM', KEYS[7], ARGV[1])
local next_host = nil
for _, id in ipairs(redis.call('ZRANGE', KEYS[1], 0, -1)) do
  if redis.call('SISMEMBER', KEYS[5], id) == 0 then
    next_host = id
    break
  end
end
if not next_host then
  redis.call('DEL', KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5], KEYS[6], KEYS[7])
  return 2
end
if redis.call('HGET', KEYS[2], 'host_id') == ARGV[1] then
  redis.call('HSET', KEYS[2], 'host_id', next_host)
end
redis.call('HINCRBY', KEYS[2], 'version', 1)
return 1
"""
_LEAVE_RESULTS = {1: LEFT, 2: LOBBY_DELETED, -3: NOT_IN_LOBBY, -1: ALREADY_STARTED, -2: NOT_FOUND}

_SET_READY_LUA = """
local status = redis.call('HGET', KEYS[2], 'status')
if not status then return -2 end
if status ~= 'WAITING' then return -1 end
if not redis.call('ZSCORE', KEYS[1], ARGV[1]) then return -3 end
local changed
if ARGV[2] == '1' then
  changed = redis.call('SADD', KEYS[3], ARGV[1])
else
  changed = redis.call('SREM', KEYS[3], ARGV[1])
end
if changed == 1 then
  redis.call('HINCRBY', KEYS[2], 'version', 1)
  return 1
end
return 0
"""
_SET_READY_RESULTS = {1: CHANGED, 0: UNCHANGED, -3: NOT_IN_LOBBY, -1: ALREADY_STARTED, -2: NOT_FOUND}

_ADD_BOT_LUA = """
local status = redis.call('HGET', KEYS[2], 'status')
if not status then return -2 end
if status ~= 'WAITING' then return -1 end
if not redis.call('ZSCORE', KEYS[1], ARGV[1]) then return -3 end
if redis.call('HGET', KEYS[2], 'host_id') ~= ARGV[1] then return -4 end
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then return 0 end
local n = 1
while redis.call('ZSCORE', KEYS[1], 'bot-' .. ARGV[3] .. '-' .. n) do
  n = n + 1
end
local id = 'bot-' .. ARGV[3] .. '-' .. n
redis.call('ZADD', KEYS[1], ARGV[4], id)
redis.call('HSET', KEYS[3], id, 'Bot ' .. n)
redis.call('SADD', KEYS[4], id)
redis.call('HINCRBY', KEYS[2], 'version', 1)
return 1
"""
_ADD_BOT_RESULTS = {1: BOT_ADDED, 0: FULL, -4: NOT_HOST, -3: NOT_IN_LOBBY, -1: ALREADY_STARTED, -2: NOT_FOUND}

_REMOVE_BOT_LUA = """
local status = redis.call('HGET', KEYS[2], 'status')
if not status then return -2 end
if status ~= 'WAITING' then return -1 end
if not redis.call('ZSCORE', KEYS[1], ARGV[1]) then return -3 end
if redis.call('HGET', KEYS[2], 'host_id') ~= ARGV[1] then return -4 end
if redis.call('SISMEMBER', KEYS[4], ARGV[2]) == 0 then return -5 end
redis.call('ZREM', KEYS[1], ARGV[2])
redis.call('HDEL', KEYS[3], ARGV[2])
redis.call('SREM', KEYS[4], ARGV[2])
redis.call('HINCRBY', KEYS[2], 'version', 1)
return 1
"""
_REMOVE_BOT_RESULTS = {1: BOT_REMOVED, -5: BOT_NOT_FOUND, -4: NOT_HOST, -3: NOT_IN_LOBBY, -1: ALREADY_STARTED, -2: NOT_FOUND}

_VOTE_LUA = """
local status = redis.call('HGET', KEYS[2], 'status')
if not status then return -2 end
if status ~= 'WAITING' then return -1 end
if not redis.call('ZSCORE', KEYS[1], ARGV[1]) then return -3 end
if redis.call('HGET', KEYS[3], ARGV[1]) == ARGV[2] then return 0 end
redis.call('HSET', KEYS[3], ARGV[1], ARGV[2])
redis.call('HINCRBY', KEYS[2], 'version', 1)
return 1
"""
_VOTE_RESULTS = {1: CHANGED, 0: UNCHANGED, -3: NOT_IN_LOBBY, -1: ALREADY_STARTED, -2: NOT_FOUND}

_KICK_LUA = """
local status = redis.call('HGET', KEYS[2], 'status')
if not status then return -2 end
if status ~= 'WAITING' then return -1 end
if not redis.call('ZSCORE', KEYS[1], ARGV[1]) then return -3 end
if redis.call('HGET', KEYS[2], 'host_id') ~= ARGV[1] then return -4 end
if ARGV[1] == ARGV[2] then return -6 end
if redis.call('SISMEMBER', KEYS[5], ARGV[2]) == 1 then return -7 end
if not redis.call('ZSCORE', KEYS[1], ARGV[2]) then return -5 end
redis.call('ZREM', KEYS[1], ARGV[2])
redis.call('HDEL', KEYS[3], ARGV[2])
redis.call('SREM', KEYS[4], ARGV[2])
redis.call('HDEL', KEYS[6], ARGV[2])
redis.call('SADD', KEYS[7], ARGV[2])
redis.call('HINCRBY', KEYS[2], 'version', 1)
return 1
"""
_KICK_RESULTS = {1: KICKED, -5: PLAYER_NOT_FOUND, -6: SELF_KICK, -7: IS_BOT, -4: NOT_HOST,
                 -3: NOT_IN_LOBBY, -1: ALREADY_STARTED, -2: NOT_FOUND}

_BEGIN_START_LUA = """
local status = redis.call('HGET', KEYS[2], 'status')
if not status then return -2 end
if status ~= 'WAITING' then return -1 end
if not redis.call('ZSCORE', KEYS[1], ARGV[1]) then return -3 end
if redis.call('HGET', KEYS[2], 'host_id') ~= ARGV[1] then return -4 end
local members = redis.call('ZRANGE', KEYS[1], 0, -1)
if #members < tonumber(ARGV[2]) then return -5 end
for _, id in ipairs(members) do
  if id ~= ARGV[1] and redis.call('SISMEMBER', KEYS[3], id) == 0 and redis.call('SISMEMBER', KEYS[4], id) == 0 then
    return -6
  end
end
if (redis.call('HGET', KEYS[2], 'version') or '1') ~= ARGV[3]
    or (redis.call('HGET', KEYS[2], 'generation') or '') ~= ARGV[4]
    or ARGV[5] == '' then return -7 end
local now = redis.call('TIME')
redis.call('ZADD', KEYS[8], tonumber(now[1]) + tonumber(ARGV[7]), ARGV[5])
redis.call('HSET', KEYS[2], 'status', 'STARTING', 'start_member', ARGV[5],
  'start_plan', ARGV[6], 'start_dispatches', '1', 'start_state', 'PENDING')
redis.call('HINCRBY', KEYS[2], 'version', 1)
for i = 1, 7 do
  local ttl = redis.call('TTL', KEYS[i])
  if ttl >= 0 and ttl < tonumber(ARGV[8]) then redis.call('EXPIRE', KEYS[i], ARGV[8]) end
end
return 1
"""
_BEGIN_START_RESULTS = {1: START_BEGUN, -6: NOT_ALL_READY, -5: NOT_ENOUGH_PLAYERS, -4: NOT_HOST,
                        -3: NOT_IN_LOBBY, -1: ALREADY_STARTED, -2: NOT_FOUND, -7: RETRY_SNAPSHOT}

_FINISH_START_LUA = """
local status = redis.call('HGET', KEYS[1], 'status')
if status == 'STARTED' and redis.call('HGET', KEYS[1], 'game_id') == ARGV[2]
    and redis.call('HGET', KEYS[1], 'map_id') == ARGV[1] then return 'DUPLICATE' end
if status ~= 'STARTING' or redis.call('HGET', KEYS[1], 'start_member') ~= ARGV[3] then
  return 'IGNORED'
end
local plan = cjson.decode(redis.call('HGET', KEYS[1], 'start_plan'))
if plan.map_id ~= ARGV[1] then return 'IGNORED' end
redis.call('ZREM', KEYS[8], ARGV[3])
redis.call('HSET', KEYS[1], 'status', 'STARTED', 'map_id', ARGV[1], 'game_id', ARGV[2])
redis.call('HDEL', KEYS[1], 'start_member', 'start_plan', 'start_dispatches', 'start_state')
redis.call('HINCRBY', KEYS[1], 'version', 1)
for i = 1, 7 do redis.call('EXPIRE', KEYS[i], ARGV[4]) end
return 'CONFIRMED'
"""

_ABORT_START_LUA = """
if redis.call('HGET', KEYS[1], 'status') ~= 'STARTING'
    or redis.call('HGET', KEYS[1], 'start_member') ~= ARGV[1]
    or redis.call('HGET', KEYS[1], 'start_dispatches') ~= '1' then return 0 end
redis.call('ZREM', KEYS[2], ARGV[1])
redis.call('HSET', KEYS[1], 'status', 'WAITING')
redis.call('HDEL', KEYS[1], 'start_member', 'start_plan', 'start_dispatches', 'start_state')
redis.call('HINCRBY', KEYS[1], 'version', 1)
return 1
"""

_CLAIM_START_LUA = """
local due = redis.call('ZSCORE', KEYS[2], ARGV[1])
if not due or tonumber(due) > tonumber(redis.call('TIME')[1]) then return {'SKIPPED'} end
if redis.call('HGET', KEYS[1], 'status') ~= 'STARTING'
    or redis.call('HGET', KEYS[1], 'start_member') ~= ARGV[1] then
  redis.call('ZREM', KEYS[2], ARGV[1])
  return {'IGNORED'}
end
local count = tonumber(redis.call('HGET', KEYS[1], 'start_dispatches'))
if count >= tonumber(ARGV[3]) then
  redis.call('ZREM', KEYS[2], ARGV[1])
  redis.call('HSET', KEYS[1], 'start_state', 'MANUAL')
  return {'MANUAL'}
end
local now = redis.call('TIME')
redis.call('ZADD', KEYS[2], tonumber(now[1]) + tonumber(ARGV[2]), ARGV[1])
redis.call('HINCRBY', KEYS[1], 'start_dispatches', 1)
return {'RETRY', redis.call('HGET', KEYS[1], 'start_plan')}
"""

def _lobby_key(code: str):
    return f"lobby:{code}"

def _players_key(code: str):
    return f"lobby:{code}:players"

def _names_key(code: str):
    return f"lobby:{code}:names"

def _ready_key(code: str):
    return f"lobby:{code}:ready"

def _bots_key(code: str):
    return f"lobby:{code}:bots"

def _votes_key(code: str):
    return f"lobby:{code}:votes"

def _banned_key(code: str):
    return f"lobby:{code}:banned"

def _keys(code: str):
    return [_lobby_key(code), _players_key(code), _names_key(code), _ready_key(code),
            _bots_key(code), _votes_key(code), _banned_key(code)]


class LobbyRepository:
    def __init__(self, r: redis.Redis):
        self.r = r
        self._create_script = r.register_script(_CREATE_LUA)
        self._claim_start_script = r.register_script(_CLAIM_START_LUA)
        self._add_player_script = r.register_script(_ADD_PLAYER_LUA)
        self._leave_script = r.register_script(_LEAVE_LUA)
        self._set_ready_script = r.register_script(_SET_READY_LUA)
        self._add_bot_script = r.register_script(_ADD_BOT_LUA)
        self._remove_bot_script = r.register_script(_REMOVE_BOT_LUA)
        self._vote_script = r.register_script(_VOTE_LUA)
        self._kick_script = r.register_script(_KICK_LUA)
        self._begin_start_script = r.register_script(_BEGIN_START_LUA)
        self._finish_start_script = r.register_script(_FINISH_START_LUA)
        self._abort_start_script = r.register_script(_ABORT_START_LUA)


    async def create(self, code: str, host_id: str, host_name: str):
        return bool(await self._create_script(
            keys=_keys(code), args=[host_id, host_name, time.time(), config.LOBBY_TIMEOUT, uuid4().hex]
        ))

    async def add_player(self, code: str, user_id: str, display_name: str):
        result = await self._add_player_script(
            keys=[_players_key(code), _lobby_key(code), _names_key(code), _banned_key(code)],
            args=[user_id, config.MAX_PLAYERS, time.time(), display_name],
        )
        if result == 1:
            await self._refresh_lobby_expiration(code)
        return _ADD_PLAYER_RESULTS.get(result, NOT_FOUND)

    async def leave(self, code: str, user_id: str):
        result = await self._leave_script(
            keys=[_players_key(code), _lobby_key(code), _names_key(code), _ready_key(code), _bots_key(code), _votes_key(code), _banned_key(code)],
            args=[user_id],
        )
        if result == 1:
            await self._refresh_lobby_expiration(code)
        return _LEAVE_RESULTS.get(result, NOT_FOUND)

    async def set_ready(self, code: str, user_id: str, ready: bool):
        result = await self._set_ready_script(
            keys=[_players_key(code), _lobby_key(code), _ready_key(code)],
            args=[user_id, "1" if ready else "0"],
        )
        if result == 1:
            await self._refresh_lobby_expiration(code)
        return _SET_READY_RESULTS.get(result, NOT_FOUND)

    async def add_bot(self, code: str, host_id: str):
        result = await self._add_bot_script(
            keys=[_players_key(code), _lobby_key(code), _names_key(code), _bots_key(code)],
            args=[host_id, config.MAX_PLAYERS, code, time.time()],
        )
        if result == 1:
            await self._refresh_lobby_expiration(code)
        return _ADD_BOT_RESULTS.get(result, NOT_FOUND)

    async def remove_bot(self, code: str, host_id: str, bot_id: str):
        result = await self._remove_bot_script(
            keys=[_players_key(code), _lobby_key(code), _names_key(code), _bots_key(code)],
            args=[host_id, bot_id],
        )
        if result == 1:
            await self._refresh_lobby_expiration(code)
        return _REMOVE_BOT_RESULTS.get(result, NOT_FOUND)

    async def vote(self, code: str, user_id: str, map_id: str):
        result = await self._vote_script(
            keys=[_players_key(code), _lobby_key(code), _votes_key(code)],
            args=[user_id, map_id],
        )
        if result == 1:
            await self._refresh_lobby_expiration(code)
        return _VOTE_RESULTS.get(result, NOT_FOUND)
    
    async def get(self, code: str):
        async with self.r.pipeline(transaction=True) as batch:
            batch.hgetall(_lobby_key(code))
            batch.zrange(_players_key(code), 0, -1)
            batch.hgetall(_names_key(code))
            batch.smembers(_ready_key(code))
            batch.smembers(_bots_key(code))
            batch.hgetall(_votes_key(code))
            batch.smembers(_banned_key(code))
            data, members, names, ready_ids, bot_ids, votes, banned = await batch.execute()
        if not data or "status" not in data:
            return None
        players = [
            Player(
                user_id=user_id,
                display_name=names.get(user_id, user_id),
                role=PlayerRole.HOST if user_id == data["host_id"] else PlayerRole.PLAYER,
                ready=user_id in bot_ids or user_id in ready_ids,
                is_bot=user_id in bot_ids,
                voted_map_id=votes.get(user_id),
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
            map_id=data.get("map_id"),
            map_votes={map_id: list(votes.values()).count(map_id) for map_id in MAP_IDS},
            banned_players=sorted(banned),
            game_id=data.get("game_id"),
            generation=data.get("generation", ""),
        )

    async def _refresh_lobby_expiration(self, code: str):
        for key in (_lobby_key(code), _players_key(code), _names_key(code), _ready_key(code), _bots_key(code), _votes_key(code), _banned_key(code)):
            await self.r.expire(key, config.LOBBY_TIMEOUT)

    async def kick(self, code: str, host_id: str, target_id: str):
        result = await self._kick_script(
            keys=[_players_key(code), _lobby_key(code), _names_key(code), _ready_key(code),
                _bots_key(code), _votes_key(code), _banned_key(code)],
            args=[host_id, target_id],
        )
        if result == 1:
            await self._refresh_lobby_expiration(code)
        return _KICK_RESULTS.get(result, NOT_FOUND)

    async def begin_start(self, code: str, host_id: str, snapshot: Lobby | None,
                          plan: GameInitialization | None):
        keys = [_players_key(code), _lobby_key(code), _ready_key(code), _bots_key(code),
                _names_key(code), _votes_key(code), _banned_key(code), _SAGA_INDEX]
        result = await self._begin_start_script(
            keys=keys,
            args=[host_id, config.MIN_PLAYERS, snapshot.version if snapshot else -1,
                  snapshot.generation if snapshot else "", f"{code}:{plan.game_id}" if plan else "",
                  json.dumps(asdict(plan)) if plan else "", _SAGA_DELAY, _SAGA_MIN_TTL],
        )
        outcome = _BEGIN_START_RESULTS.get(result, NOT_FOUND)
        START.labels("reserve", outcome).inc()
        return outcome

    async def finish_start(self, code: str, map_id: str, game_id: str):
        result = await self._finish_start_script(
            keys=[*_keys(code), _SAGA_INDEX],
            args=[map_id, game_id, f"{code}:{game_id}", config.LOBBY_TIMEOUT],
        )

        START.labels("finish", result).inc()
        return result

    async def abort_start(self, plan: GameInitialization):
        result = bool(await self._abort_start_script(
            keys=[_lobby_key(plan.lobby_id), _SAGA_INDEX],
            args=[f"{plan.lobby_id}:{plan.game_id}"],
        ))

        START.labels("compensate", "APPLIED" if result else "IGNORED").inc()
        return result

    async def due_starts(self):
        START_DUE.set(await self.r.zcard(_SAGA_INDEX))
        now, _ = await self.r.time()
        return await self.r.zrangebyscore(_SAGA_INDEX, "-inf", now, start=0, num=16)

    async def claim_start(self, member: str):
        code, _, _ = member.partition(":")
        outcome, *data = await self._claim_start_script(
            keys=[_lobby_key(code), _SAGA_INDEX], args=[member, _SAGA_DELAY, _SAGA_ATTEMPTS]
        )
        START.labels("recovery", outcome).inc()
        if not data:
            return outcome, None
        payload = json.loads(data[0])
        payload["seats"] = tuple(Seat(**seat) for seat in payload["seats"])
        return outcome, GameInitialization(**payload)
