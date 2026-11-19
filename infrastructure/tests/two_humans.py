"""Exercise two real JWT identities through Gateway on both maps in an isolated stack."""
import asyncio
import json
import os
from uuid import uuid4

import websockets

from smoke_second_cut import GATEWAY, request

WS = "ws" + GATEWAY.removeprefix("http") + "/api/game/ws"


class PlayerSocket:
    def __init__(self, socket, game_id):
        self.socket = socket
        self.game_id = game_id

    async def receive(self, predicate):
        async with asyncio.timeout(8):
            while True:
                message = json.loads(await self.socket.recv())
                if predicate(message):
                    return message

    async def send(self, kind, **fields):
        await self.socket.send(json.dumps({"type": kind, **fields}))

    async def authenticate(self, token, actor):
        await self.send("AUTH", token=token)
        result = await self.receive(lambda m: m["type"] in {"AUTHENTICATED", "ERROR"})
        assert (result["type"], result.get("playerId")) == ("AUTHENTICATED", actor)

    async def snapshot(self, command="SYNC_REQUEST"):
        await self.send(command, gameId=self.game_id)
        result = await self.receive(lambda m: m["type"] in {"GAME_STATE", "ERROR"})
        assert result["type"] == "GAME_STATE", result.get("code")
        return result

    async def command(self, state, kind, *, error=None, **fields):
        for _ in range(5):
            rid = uuid4().hex
            await self.send(kind, gameId=self.game_id, expectedVersion=state["version"], requestId=rid, **fields)
            result = await self.receive(lambda m: m.get("requestId") == rid and m["type"] in {"ACTION_ACK","ERROR"})
            if result["type"] == "ERROR" and result["code"] in {"STALE_VERSION", "CONCURRENT_UPDATE"}:
                state = (await self.snapshot())["state"]
                continue
            assert (result["type"], result.get("code")) == (("ERROR", error) if error else ("ACTION_ACK", None)), result.get("code")
            return result
        raise AssertionError("Too many concurrent updates")


async def game_probe(game_id, tokens, actors, map_id):
    async with websockets.connect(WS, max_queue=1024) as a, websockets.connect(WS, max_queue=1024) as b:
        clients = [PlayerSocket(ws, game_id) for ws in (a, b)]
        for client, token, actor in zip(clients, tokens, actors, strict=True):
            await client.authenticate(token, actor)
            await client.snapshot("JOIN_GAME")
        state = (await clients[0].snapshot())["state"]
        assert state["mapId"] == map_id
        assert [(p["id"],p["seat"],p["bot"]) for p in state["players"]] == [(actors[0],0,False),(actors[1],2,False)]
        assert state["turn"]["playerId"] == actors[0]
        await clients[1].command(state, "ROLL_DICE", error="NOT_YOUR_TURN")
        moved = set()
        rolled = set()
        for _ in range(160):
            index = actors.index(state["turn"]["playerId"])
            actor = actors[index]
            client, peer = clients[index], clients[1-index]
            if not state["turn"]["rolled"]:
                result = await client.command(state, "ROLL_DICE")
                rolled.add(actor)
                state = result["state"]
            if state["turn"].get("animationStatus") == "WAITING":
                state = (await client.command(state, "DICE_ANIMATION_COMPLETED", challengeId=state["turn"]["rollId"]))["state"]
                if state["turn"]["playerId"] == actor and not state["turn"]["rolled"]:
                    continue
            if state["turn"].get("crownChoiceRequired"):
                state = (await client.command(state, "CROWN_REQUEST", pieceId=state["turn"]["crownEligible"][0]))["state"]
            if state["turn"]["playerId"] != actor:
                continue  # no valid move; server already rotated
            if actor not in moved:
                synced = await client.snapshot()
                state = synced["state"]
                if synced["legalMoves"]:
                    move = synced["legalMoves"][0]
                    result = await client.command(state, "MOVE_REQUEST", pieceId=move["pieceId"],
                                                  dieIndex=move["dieIndex"], dieValue=move["dieValue"],
                                                  fromPosition=move["from"], targetPosition=move["to"])
                    state = result["state"]
                    assert move["from"] is not None
                    assert not move["release"]
                    await peer.receive(lambda m: m["type"]=="GAME_UPDATED" and m["version"]==result["committedVersion"]
                                       and any(e["type"]=="PIECE_MOVED" for e in m["events"]))
                    moved.add(actor)
            if state.get("trivia"):
                state = (await client.command(state, "TRIVIA_ANSWER", challengeId=state["trivia"]["id"], answerIndex=0))["state"]
            if state.get("card"):
                state = (await client.command(state, "CARD_TARGET", challengeId=state["card"]["id"], **state["card"]["choices"][0]))["state"]
            if moved == set(actors):
                break
            if state["turn"]["playerId"] == actor and state["turn"]["rolled"]:
                state = (await client.command(state, "END_TURN"))["state"]
                assert state["turn"]["playerId"] == actors[1-index]
        assert moved == rolled == set(actors), "Both humans must roll and move"
        # Captures/cards can legally leave a player with no BOARD pieces.
        # Reconnection must preserve every piece, whatever its valid status is.
        expected_pieces = state["pieces"]
    for token, actor in zip(tokens, actors, strict=True):
        async with websockets.connect(WS) as socket:
            client = PlayerSocket(socket, game_id)
            await client.authenticate(token, actor)
            state = (await client.snapshot())["state"]
            assert state["gameId"] == game_id
            assert state["pieces"] == expected_pieces, "Reconnection changed persisted pieces"



def main():
    if os.getenv("PROOF_ISOLATED") != "1":
        raise SystemExit("Run only against the isolated stack with PROOF_ISOLATED=1")
    refresh = [os.environ["PROOF_REFRESH_TOKEN"], os.environ["PROOF_SECOND_REFRESH_TOKEN"]]
    tokens = [request(GATEWAY, "/api/refresh", method="POST", body={"refreshToken": token})["accessToken"] for token in refresh]
    actors = [str(request(GATEWAY, "/api/auth/me", token=token)["id"]) for token in tokens]
    assert len(set(actors)) == 2
    board = request(GATEWAY, "/api/game/board", token=tokens[0])
    assert board["layout"] == "v4-100" and board["cellCount"] == 100
    assert {cell["id"] for cell in board["cells"]} == set(range(100))
    assert set(board["safe"]) == {0,5,12,17,22,29,34,39,46,51,56,63}
    for map_id in ("classic", "science"):
        room = request(GATEWAY, "/api/lobbies", method="POST", token=tokens[0], status=201)
        base = "/api/lobbies/" + room["code"]
        request(GATEWAY, base + "/join", method="POST", token=tokens[1])
        request(GATEWAY, base + "/ready", method="PUT", token=tokens[1], body={"ready": True})
        request(GATEWAY, base + "/vote", method="PUT", token=tokens[0], body={"map_id": map_id})
        started = request(GATEWAY, base + "/start", method="POST", token=tokens[0])
        assert started["status"] == "STARTED" and started["map_id"] == map_id
        asyncio.run(game_probe(started["game_id"], tokens, actors, map_id))
        print(json.dumps({"map":map_id, "humans":2, "seats":[0,2], "outOfTurnRejected":True,
                          "bothRolledMovedAndReconnected":True, "peerMoveObserved":True}), flush=True)


if __name__ == "__main__":
    main()
