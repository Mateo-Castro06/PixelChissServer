"""Real 10/30-second deadlines, two JWT clients and process recovery; isolated stack only.

Requires PROOF_ISOLATED=1, two synthetic refresh tokens, PROOF_LOBBY_TOKEN and
PROOF_REDIS_URL. Fixtures only modify fresh proof-deadline-* games in isolated Redis.
Never point this probe at development. It restarts only pixelchiss-definitive/game.
"""
import asyncio
import json
import os
import subprocess
import time
from pathlib import Path
from uuid import uuid4

import redis
import websockets
from smoke_second_cut import GATEWAY, GAME, request
from two_humans import PlayerSocket, WS

REPO = Path(__file__).resolve().parents[2]


def prepare(store, game_id, actors, map_id):
    request(GAME, "/internal/games", method="POST", headers={"X-Lobby-Token": os.environ["PROOF_LOBBY_TOKEN"]}, body={
        "gameId": game_id, "lobbyId": game_id, "mapId": map_id, "creatorPlayerId": actors[0],
        "participants": [{"playerId": actor, "bot": False, "seat": seat} for seat, actor in enumerate(actors)],
    })
    key = "pixelchiss:game:state:" + game_id
    with store.pipeline() as tx:
        tx.watch(key)
        state = json.loads(tx.hget(key, "state"))
        for seat, number, position, progress in ((0, 0, 6, 1), (0, 1, 20, 15), (1, 0, 8, 54)):
            piece = next(p for p in state["pieces"] if p["id"] == f"seat-{seat}-piece-{number}")
            piece.update(status="BOARD", position=position, progress=progress)
        state["players"][0]["effects"].append({"code": "CHOOSE_DICE", "remainingTurns": 1, "startsAtTurn": 1, "maxValue": 6})
        state["version"] += 1
        tx.multi()
        tx.hset(key, mapping={"state": json.dumps(state), "version": str(state["version"])})
        tx.execute()


async def connect(game_id, tokens, actors):
    sockets = [await websockets.connect(WS, max_queue=1024) for _ in actors]
    clients = [PlayerSocket(ws, game_id) for ws in sockets]
    for client, token, actor in zip(clients, tokens, actors, strict=True):
        await client.authenticate(token, actor)
        await client.snapshot("JOIN_GAME")
    return clients


async def wait_expiry(client, timeout=50):
    async with asyncio.timeout(timeout):
        while True:
            message = json.loads(await client.socket.recv())
            if message.get("type") == "GAME_UPDATED" and any(e["type"] == "TURN_EXPIRED" for e in message["events"]):
                return message


async def bot_trivia(tokens, actors):
    store = redis.Redis.from_url(os.environ["PROOF_REDIS_URL"], decode_responses=True)
    for map_id in ("classic", "science"):
        game_id = "proof-bot-trivia-" + uuid4().hex
        request(GAME, "/internal/games", method="POST", headers={"X-Lobby-Token": os.environ["PROOF_LOBBY_TOKEN"]}, body={
            "gameId": game_id, "lobbyId": game_id, "mapId": map_id, "creatorPlayerId": actors[0],
            "participants": [{"playerId": actor, "bot": False, "seat": seat} for seat, actor in enumerate(actors)]
                            + [{"playerId": "proof-bot", "bot": True, "seat": 2}],
        })
        clients = await connect(game_id, tokens, actors)
        try:
            key = "pixelchiss:game:state:" + game_id
            # Isolated fixture: leave only one authorized die and one active bot piece.
            with store.pipeline() as tx:
                tx.watch(key)
                state = json.loads(tx.hget(key, "state"))
                piece = next(p for p in state["pieces"] if p["id"] == "seat-2-piece-0")
                piece.update(status="BOARD", position=55, progress=16)
                state["turn"].update(playerId="proof-bot", rolled=True, dice=[4, 4], consumed=[False, True],
                                     doubles=False, animationStatus="COMPLETED", movementStartedAt=int(time.time()*1000),
                                     deadline=int(time.time()*1000)+30000)
                state["version"] += 1
                tx.multi()
                tx.hset(key, mapping={"state": json.dumps(state), "version": str(state["version"])})
                tx.execute()
            async def observe(client):
                seen_question = False
                async with asyncio.timeout(15):
                    while True:
                        message = json.loads(await client.socket.recv())
                        for event in message.get("events", []):
                            if event["type"] == "TRIVIA_QUESTION":
                                assert "correctIndex" not in json.dumps(event)
                                seen_question = True
                            if event["type"] == "TRIVIA_RESOLVED":
                                assert seen_question and isinstance(event["data"]["correct"], bool)
                                return
            await asyncio.gather(*(observe(c) for c in clients))
            print(json.dumps({"map": map_id, "botTriviaOverBothWebSockets": True, "normalEvaluation": True}), flush=True)
        finally:
            for client in clients:
                await client.socket.close()


async def run(tokens, actors):
    store = redis.Redis.from_url(os.environ["PROOF_REDIS_URL"], decode_responses=True)
    cases = []
    for map_id in ("classic", "science"):
        for phase in ("animation", "movement"):
            game_id = "proof-deadline-" + uuid4().hex
            prepare(store, game_id, actors, map_id)
            clients = await connect(game_id, tokens, actors)
            state = (await clients[0].snapshot())["state"]
            state = (await clients[0].command(state, "ROLL_DICE", chosenDice=[2, 3]))["state"]
            assert state["turn"]["movementStartedAt"] is None
            assert state["turn"]["animationDeadline"] - state["turn"]["rollPublishedAt"] == 10000
            roll_id = state["turn"]["rollId"]
            if phase == "movement":
                await clients[1].command(state, "DICE_ANIMATION_COMPLETED", challengeId=roll_id, error="NOT_YOUR_TURN")
                state = (await clients[0].command(state, "DICE_ANIMATION_COMPLETED", challengeId=roll_id))["state"]
                assert state["turn"]["deadline"] - state["turn"]["movementStartedAt"] == 30000
                deadline = state["turn"]["deadline"]
                state = (await clients[0].command(state, "DICE_ANIMATION_COMPLETED", challengeId=roll_id))["state"]
                assert deadline == state["turn"]["deadline"]
                state = (await clients[0].command(state, "MOVE_REQUEST", pieceId="seat-0-piece-1", dieIndex=1,
                                                   dieValue=3, fromPosition=20, targetPosition=23))["state"]
            cases.append((map_id, phase, game_id, roll_id, state["turn"]["deadline"], clients))
    for *_, clients in cases:
        for client in clients:
            await client.socket.close()
    subprocess.run(["docker", "compose", "-p", "pixelchiss-definitive", "-f", str(REPO / "infrastructure/tests/compose.databases.yml"),
                    "-f", "/tmp/pixelchiss-definitive-runtime/runtime.json", "restart", "game"], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    # HTTP through Gateway can briefly retain a stale discovery connection after restart.
    for _ in range(40):
        try:
            request(GAME, "/actuator/health")
            break
        except Exception:
            await asyncio.sleep(1)
    else:
        raise AssertionError("Game failed to recover")

    async def verify(case):
        map_id, phase, game_id, roll_id, deadline, _ = case
        clients = await connect(game_id, tokens, actors)
        try:
            snapshots = [(await c.snapshot())["state"] for c in clients]
            state = snapshots[0]
            if state["turn"]["playerId"] == actors[0]:
                assert state["turn"]["deadline"] == deadline
                messages = await asyncio.gather(*(wait_expiry(c) for c in clients))
                assert messages[0]["version"] == messages[1]["version"]
                expiry = next(e for e in messages[0]["events"] if e["type"] == "TURN_EXPIRED")
                assert expiry["data"]["reason"] == ("MOVEMENT_TIMEOUT" if phase == "movement" else "ANIMATION_TIMEOUT")
                assert expiry["data"]["discardedDice"] == ([0] if phase == "movement" else [0, 1])
                assert any(e["type"] == "PIECE_BLOWN" and e["data"]["pieceId"] == "seat-0-piece-0" for e in messages[0]["events"]) == (phase == "movement")
                state = messages[0]["state"]
            assert state["turn"]["playerId"] == actors[1]
            assert state["turn"]["dice"] == []
            own = {p["id"]: p for p in state["pieces"] if p["ownerId"] == actors[0]}
            assert own["seat-0-piece-1"]["position"] == (23 if phase == "movement" else 20)
            assert own["seat-0-piece-0"]["status"] == ("HOME" if phase == "movement" else "BOARD")
            assert own["seat-0-piece-2"]["status"] == "HOME"  # no automatic movement
            await clients[0].command(state, "DICE_ANIMATION_COMPLETED", challengeId=roll_id, error="NOT_YOUR_TURN")
            await clients[0].command(state, "MOVE_REQUEST", pieceId="seat-0-piece-1", dieIndex=0,
                                     dieValue=2, fromPosition=23, targetPosition=25, error="NOT_YOUR_TURN")
            print(json.dumps({"map": map_id, "phase": phase, "restartRecovered": True, "bothJWTClients": True,
                              "lateActionsRejected": True, "acceptedMovePreserved": phase == "movement",
                              "blowApplied": phase == "movement", "gameId": game_id}), flush=True)
        finally:
            for client in clients:
                await client.socket.close()
    await asyncio.gather(*(verify(case) for case in cases))


def main():
    if os.environ.get("PROOF_ISOLATED") != "1" or os.environ.get("PROOF_REDIS_URL") != "redis://127.0.0.1:16379/0":
        raise SystemExit("Explicit isolated stack on port 16379 required")
    tokens = [request(GATEWAY, "/api/refresh", method="POST", body={"refreshToken": os.environ[key]})["accessToken"]
              for key in ("PROOF_REFRESH_TOKEN", "PROOF_SECOND_REFRESH_TOKEN")]
    actors = [str(request(GATEWAY, "/api/auth/me", token=token)["id"]) for token in tokens]
    assert len(set(actors)) == 2
    for _ in range(40):
        try:
            request(GAME, "/actuator/health")
            assert request(GATEWAY, "/api/game/board", token=tokens[0])["layout"] == "v5-100"
            break
        except Exception:
            time.sleep(1)
    else:
        raise AssertionError("Isolated Game/Gateway not ready")
    async def all_probes():
        await bot_trivia(tokens, actors)
        await run(tokens, actors)
    asyncio.run(all_probes())


if __name__ == "__main__":
    main()
