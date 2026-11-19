"""Pairs and independent trivia clocks through two JWT WebSockets on both maps.

Only fresh proof-pairs-* games in the explicitly isolated Redis are fixture-written.
Requires the same isolated environment as definitive_timeouts.py. No Godot is run.
"""
import asyncio
import json
import os
import subprocess
from pathlib import Path
import time
from uuid import uuid4
from urllib.error import URLError

import redis
from websockets.exceptions import InvalidStatusCode

from smoke_second_cut import GATEWAY, GAME, request
from definitive_timeouts import connect as connect_once


async def connect(game_id, tokens, actors):
    # Eureka/Gateway can retain the previous container address briefly after recreation.
    for attempt in range(80):
        try:
            return await connect_once(game_id, tokens, actors)
        except InvalidStatusCode as error:
            if error.status_code not in {502, 503} or attempt == 79:
                raise
            await asyncio.sleep(.5)


def fixture(store, game_id, mutation):
    assert game_id.startswith("proof-pairs-")
    key = "pixelchiss:game:state:" + game_id
    with store.pipeline() as tx:
        tx.watch(key)
        state = json.loads(tx.hget(key, "state"))
        mutation(state)
        state["version"] += 1
        tx.multi()
        tx.hset(key, mapping={"state": json.dumps(state), "version": str(state["version"])})
        tx.execute()


def choose(state):
    player = next(p for p in state["players"] if p["id"] == state["turn"]["playerId"])
    player["effects"] = [e for e in player["effects"] if e["code"] != "CHOOSE_DICE"]
    player["effects"].append({"code": "CHOOSE_DICE", "remainingTurns": 1,
                              "startsAtTurn": player["turnsStarted"], "maxValue": 6})


def place(state, piece_id, progress):
    piece = next(p for p in state["pieces"] if p["id"] == piece_id)
    seat = next(p["seat"] for p in state["players"] if p["id"] == piece["ownerId"])
    position = (seat * 17 + 5 + progress) % 68 if progress <= 68 else 68 + seat * 8 + progress - 69
    piece.update(status="CROWNED" if progress == 76 else "BOARD", progress=progress, position=position)


def create(store, actors, map_id, mode):
    game_id = "proof-pairs-" + uuid4().hex
    request(GAME, "/internal/games", method="POST", headers={"X-Lobby-Token": os.environ["PROOF_LOBBY_TOKEN"]}, body={
        "gameId": game_id, "lobbyId": game_id, "mapId": map_id, "creatorPlayerId": actors[0],
        "participants": [{"playerId": actor, "bot": False, "seat": seat} for seat, actor in enumerate(actors)],
    })
    def prepare(state):
        if mode == "pairs":
            place(state, "seat-0-piece-0", 10)
            place(state, "seat-0-piece-1", 69)
            place(state, "seat-1-piece-0", 51)  # rival at red start, special release capture
        else:
            for number in range(4):
                place(state, f"seat-0-piece-{number}", 2 if number == 0 else 0)
        choose(state)
    fixture(store, game_id, prepare)
    return game_id


async def close(clients):
    await asyncio.gather(*(client.socket.close() for client in clients))


async def roll(client, values):
    state = (await client.snapshot())["state"]
    return (await client.command(state, "ROLL_DICE", chosenDice=values))["state"]


async def confirm(client, state):
    return (await client.command(state, "DICE_ANIMATION_COMPLETED", challengeId=state["turn"]["rollId"]))["state"]


async def pairs(store, tokens, actors, map_id):
    game_id = create(store, actors, map_id, "pairs")
    clients = await connect(game_id, tokens, actors)
    try:
        state = await roll(clients[0], [3, 3])
        first_id = state["turn"]["rollId"]
        assert state["turn"]["homeRelease"] and state["turn"]["consumed"] == [True, True]
        assert state["turn"]["consecutiveDoubles"] == 1
        assert all(p["status"] == "BOARD" for p in state["pieces"] if p["ownerId"] == actors[0])
        assert next(p for p in state["pieces"] if p["id"] == "seat-1-piece-0")["status"] == "HOME"
        assert not (await clients[0].snapshot())["legalMoves"]
        await clients[0].command(state, "ROLL_DICE", error="ALREADY_ROLLED")
        state = await confirm(clients[0], state)
        assert not state["turn"]["rolled"] and state["turn"]["extraRollAvailable"]
        assert state["turn"]["movementStartedAt"] is None
        assert not (await clients[0].snapshot())["legalMoves"]
        # Both clients observe the resolved release; reconnect during the pair sequence.
        assert (await clients[1].snapshot())["state"]["turn"]["consecutiveDoubles"] == 1
        await close(clients)
        clients = await connect(game_id, tokens, actors)
        state = (await clients[0].snapshot())["state"]
        assert state["turn"]["rollId"] == first_id and state["turn"]["extraRollAvailable"]
        fixture(store, game_id, choose)
        state = await roll(clients[0], [2, 2])
        assert state["turn"]["rollId"] != first_id
        assert not state["turn"]["homeRelease"] and state["turn"]["consecutiveDoubles"] == 2
        await clients[0].command(state, "DICE_ANIMATION_COMPLETED", challengeId=first_id, error="STALE_ROLL")
        state = await confirm(clients[0], state)
        assert state["turn"]["deadline"] - state["turn"]["movementStartedAt"] == 30_000
        state = (await clients[0].command(state, "MOVE_REQUEST", pieceId="seat-0-piece-0", dieIndex=2,
                                         dieValue=4, fromPosition=15, targetPosition=19))["state"]
        assert state["turn"]["extraRollAvailable"] and not state["turn"]["rolled"]
        fixture(store, game_id, choose)
        state = await roll(clients[0], [4, 4])
        assert state["turn"]["consecutiveDoubles"] == 3 and state["turn"]["thirdDoubleReward"]
        state = await confirm(clients[0], state)
        assert state["turn"]["crownChoiceRequired"]
        state = (await clients[0].command(state, "CROWN_REQUEST", pieceId="seat-0-piece-1"))["state"]
        assert state["turn"]["playerId"] == actors[1] and state["turn"]["consecutiveDoubles"] == 0
        assert next(p for p in state["pieces"] if p["id"] == "seat-0-piece-1")["status"] == "CROWNED"
        assert all(p["status"] != "HOME" for p in state["pieces"] if p["ownerId"] == actors[0])
        assert (await clients[1].snapshot())["state"]["turn"]["playerId"] == actors[1]
        print(json.dumps({"map": map_id, "twoJWTClients": True, "releaseConsumesPair": True,
                          "extraRoll": True, "pairMovementWithoutHome": True, "thirdPairCrown": True,
                          "noFourthRoll": True, "reconnectCounters": True}), flush=True)
    finally:
        await close(clients)


async def trivia(store, tokens, actors, map_id, timeout):
    game_id = create(store, actors, map_id, "trivia")
    clients = await connect(game_id, tokens, actors)
    try:
        state = await roll(clients[0], [1, 2])
        state = await confirm(clients[0], state)
        await asyncio.sleep(1)
        state = (await clients[0].command(state, "MOVE_REQUEST", pieceId="seat-0-piece-0", dieIndex=0,
                                         dieValue=1, fromPosition=7, targetPosition=8))["state"]
        question = state["trivia"]
        remaining = state["turn"]["movementRemainingMillis"]
        assert 27_000 < remaining < 30_000
        assert 14_000 < question["deadline"] - time.time()*1000 <= 15_000
        assert state["turn"]["pausedMovementRollId"] == state["turn"]["rollId"]
        await close(clients)
        clients = await connect(game_id, tokens, actors)
        restored = (await clients[0].snapshot())["state"]
        assert restored["trivia"]["deadline"] == question["deadline"]
        assert restored["turn"]["movementRemainingMillis"] == remaining
        if timeout:
            async def resolved(client):
                async with asyncio.timeout(20):
                    while True:
                        message = json.loads(await client.socket.recv())
                        if any(e["type"] == "TRIVIA_RESOLVED" for e in message.get("events", [])):
                            return message
            results = await asyncio.gather(*(resolved(c) for c in clients))
            assert results[0]["version"] == results[1]["version"]
            result = next(e for e in results[0]["events"] if e["type"] == "TRIVIA_RESOLVED")
            assert result["data"]["correct"] is False
            state = results[0]["state"]
            await clients[0].command(state, "TRIVIA_ANSWER", challengeId=question["id"], answerIndex=0,
                                     error="INVALID_CHALLENGE")
        else:
            await asyncio.sleep(2)
            state = (await clients[0].command(restored, "TRIVIA_ANSWER", challengeId=question["id"], answerIndex=0))["state"]
        assert state["trivia"] is None and state["turn"]["movementRemainingMillis"] is None
        assert state["turn"]["playerId"] == actors[0]
        # The saved balance starts from resolution, never from the old movement deadline.
        assert remaining - 1500 < state["turn"]["deadline"] - time.time()*1000 <= remaining
        peer = (await clients[1].snapshot())["state"]
        assert peer["turn"]["deadline"] == state["turn"]["deadline"]
        print(json.dumps({"map": map_id, "triviaTimeout": timeout, "independent15Seconds": True,
                          "movementPaused": True, "remainingPreserved": True, "reconnectTimers": True,
                          "twoJWTClients": True, "lateRejected": timeout}), flush=True)
    finally:
        await close(clients)


async def restart_paused(store, tokens, actors):
    cases = []
    for map_id in ("classic", "science"):
        game_id = create(store, actors, map_id, "trivia")
        clients = await connect(game_id, tokens, actors)
        try:
            state = await confirm(clients[0], await roll(clients[0], [1, 2]))
            state = (await clients[0].command(state, "MOVE_REQUEST", pieceId="seat-0-piece-0", dieIndex=0,
                                             dieValue=1, fromPosition=7, targetPosition=8))["state"]
            cases.append((map_id, game_id, state["trivia"]["deadline"], state["turn"]["movementRemainingMillis"]))
        finally:
            await close(clients)
    repo = Path(__file__).resolve().parents[2]
    subprocess.run(["docker", "compose", "-p", "pixelchiss-definitive", "-f", str(repo / "infrastructure/tests/compose.databases.yml"),
                    "-f", "/tmp/pixelchiss-definitive-runtime/runtime.json", "restart", "game"], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(40):
        try:
            request(GAME, "/actuator/health")
            break
        except Exception:
            await asyncio.sleep(.5)
    else:
        raise AssertionError("Game failed to recover paused trivia")
    async def verify(case):
        map_id, game_id, deadline, remaining = case
        clients = await connect(game_id, tokens, actors)
        try:
            state = (await clients[0].snapshot())["state"]
            if state["trivia"] is not None:
                assert state["trivia"]["deadline"] == deadline
                assert state["turn"]["movementRemainingMillis"] == remaining
                async with asyncio.timeout(20):
                    while True:
                        message = json.loads(await clients[0].socket.recv())
                        if any(e["type"] == "TRIVIA_RESOLVED" for e in message.get("events", [])):
                            state = message["state"]
                            break
            assert state["trivia"] is None
            assert state["turn"]["deadline"] == deadline + remaining
            assert state["turn"]["movementRemainingMillis"] is None
            assert (await clients[1].snapshot())["state"]["turn"]["deadline"] == deadline + remaining
            print(json.dumps({"map": map_id, "pausedTriviaProcessRestart": True,
                              "triviaDeadlinePreserved": True, "movementResumeFromTriviaDeadline": True,
                              "twoJWTClients": True}), flush=True)
        finally:
            await close(clients)
    await asyncio.gather(*(verify(case) for case in cases))


def main():
    if os.environ.get("PROOF_ISOLATED") != "1" or os.environ.get("PROOF_REDIS_URL") != "redis://127.0.0.1:16379/0":
        raise SystemExit("Explicit isolated Redis on 16379 required")
    store = redis.Redis.from_url(os.environ["PROOF_REDIS_URL"], decode_responses=True)
    tokens = [request(GATEWAY, "/api/refresh", method="POST", body={"refreshToken": os.environ[key]})["accessToken"]
              for key in ("PROOF_REFRESH_TOKEN", "PROOF_SECOND_REFRESH_TOKEN")]
    actors = [str(request(GATEWAY, "/api/auth/me", token=token)["id"]) for token in tokens]
    assert len(set(actors)) == 2
    for attempt in range(240):
        try:
            request(GAME, "/actuator/health")
            request(GATEWAY, "/api/game/board", token=tokens[0])
            break
        except (URLError, AssertionError):
            if attempt == 239:
                raise
            time.sleep(.5)
    async def run():
        for map_id in ("classic", "science"):
            await pairs(store, tokens, actors, map_id)
            await trivia(store, tokens, actors, map_id, False)
        await asyncio.gather(*(trivia(store, tokens, actors, map_id, True) for map_id in ("classic", "science")))
        if os.environ.get("PROOF_RESTART_PAUSED") == "1":
            await restart_paused(store, tokens, actors)
    asyncio.run(run())


if __name__ == "__main__":
    main()
