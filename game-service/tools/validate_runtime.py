"""Real JAR probes. Writes only under Game; retains all newly created game states.

Needs Python websockets/cryptography, Java 21, running Mongo/Redis/Eureka,
and the already compiled Auth issuer. Never reads Auth's private key.
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid
import zipfile

import websockets
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / ".build" / "runtime-validation"
WORK.mkdir(parents=True, exist_ok=True)
RUN = uuid.uuid4().hex[:12]
LOBBY_TOKEN = uuid.uuid4().hex + uuid.uuid4().hex
RESULTS = {"runId": RUN, "checks": [], "retainedGames": []}


def record(name, **data):
    RESULTS["checks"].append({"check": name, **data})
    print(json.dumps(RESULTS["checks"][-1]), flush=True)


def http(url, token=None, body=None, lobby=False):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if lobby:
        headers["X-Lobby-Token"] = LOBBY_TOKEN
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        raw = error.read()
        return error.code, json.loads(raw) if raw and raw[:1] in [b"{", b"["] else {}


def redis_state(prefix, game_id):
    args = ["HGET", prefix + ":state:" + game_id, "state"]
    wire = b"*3\r\n" + b"".join(b"$" + str(len(a.encode())).encode() + b"\r\n" + a.encode() + b"\r\n" for a in args)
    with socket.create_connection(("localhost", 6379), timeout=5) as sock:
        sock.sendall(wire)
        stream = sock.makefile("rb")
        header = stream.readline()
        assert header.startswith(b"$") and int(header[1:]) >= 0, "Missing validation aggregate"
        return json.loads(stream.read(int(header[1:])))


def create(base, prefix, label, bots=False):
    game_id = "validation-" + RUN + "-" + label
    body = {"gameId": game_id, "lobbyId": "validation-" + RUN, "mapId": "classic",
            "participants": [{"playerId": "900001", "bot": bots, "seat": 0}, {"playerId": "900002", "bot": bots, "seat": 1}],
            "creatorPlayerId": "900002"}
    status, state = http(base + "/internal/games", body=body, lobby=True)
    assert status == 200, (status, state)
    assert state["turn"]["playerId"] == "900002" and state["turn"]["number"] == 1
    assert len(state["pieces"]) == 8
    assert all(sum(p["ownerId"] == actor for p in state["pieces"]) == 4 for actor in ["900001", "900002"])
    RESULTS["retainedGames"].append({"gameId": game_id, "redisPrefix": prefix})
    record("initialization_and_first_player", gameId=game_id, creatorPlayerId="900002", pieces=8)
    invalid = dict(body, gameId=game_id + "-invalid", creatorPlayerId="outsider")
    code, error = http(base + "/internal/games", body=invalid, lobby=True)
    assert code == 400 and error["code"] == "INVALID_CREATOR"
    return game_id


def wait_health(base, process=None):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        if process and process.poll() is not None:
            raise AssertionError("Game process exited; see runtime log")
        try:
            code, data = http(base + "/actuator/health")
            if code == 200 and data["status"] == "UP":
                return
        except (OSError, ValueError):
            pass
        time.sleep(0.5)
    raise AssertionError("Game health timeout")


def registry(port):
    # Allow the registry's read cache to reflect a successful registration.
    until = time.monotonic() + 45
    while time.monotonic() < until:
        status, data = http("http://localhost:8761/eureka/apps/GAME-SERVICE")
        instances = data.get("application", {}).get("instance", [])
        if isinstance(instances, dict):
            instances = [instances]
        match = next((i for i in instances if i["port"]["$"] == port and i["status"] == "UP"), None)
        if match:
            record("eureka_registration", instanceId=match["instanceId"], status="UP", port=port)
            return
        time.sleep(0.5)
    raise AssertionError("No UP Game instance at requested port in Eureka")


def automation(base, prefix):
    timer_id = create(base, prefix, "timer")
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        state = redis_state(prefix, timer_id)
        if state["turn"]["number"] >= 2:
            assert state["turn"]["playerId"] == "900001"
            record("automatic_turn_timeout", gameId=timer_id, turnNumber=state["turn"]["number"], version=state["version"])
            break
        time.sleep(0.25)
    else:
        raise AssertionError("Server did not expire human turn")
    bot_id = create(base, prefix, "bots", bots=True)
    deadline = time.monotonic() + 45
    rolled, moved = False, False
    while time.monotonic() < deadline:
        state = redis_state(prefix, bot_id)
        rolled |= state["turn"]["rolled"]
        moved |= any(p["status"] != "HOME" for p in state["pieces"])
        if rolled and moved and state["turn"]["number"] >= 2:
            record("automatic_bots", gameId=bot_id, diceGenerated=True, pieceMoved=True,
                   turnNumber=state["turn"]["number"], version=state["version"])
            return
        time.sleep(0.1)
    raise AssertionError("Bot runtime did not roll/move/advance within validation window")


def clockwise_turns(base, prefix):
    game_id = "validation-" + RUN + "-clockwise"
    body = {"gameId": game_id, "lobbyId": "validation-" + RUN, "mapId": "classic",
            "creatorPlayerId": "900003",
            "participants": [{"playerId": "900004", "bot": False, "seat": 3},
                             {"playerId": "900002", "bot": False, "seat": 1},
                             {"playerId": "900003", "bot": False, "seat": 2},
                             {"playerId": "900001", "bot": False, "seat": 0}]}
    status, state = http(base + "/internal/games", body=body, lobby=True)
    assert status == 200, (status, state)
    RESULTS["retainedGames"].append({"gameId": game_id, "redisPrefix": prefix})
    expected = ["900003", "900004", "900001", "900002", "900003"]
    observed = [state["turn"]["playerId"]]
    assert observed[0] == expected[0]
    deadline = time.monotonic() + 35
    while len(observed) < len(expected) and time.monotonic() < deadline:
        state = redis_state(prefix, game_id)
        turn_number = state["turn"]["number"]
        if turn_number > len(observed):
            assert turn_number == len(observed) + 1, "Missed a timed turn transition"
            observed.append(state["turn"]["playerId"])
            assert observed[-1] == expected[len(observed) - 1], observed
        time.sleep(0.25)
    assert observed == expected, observed
    record("clockwise_timed_rotation", gameId=game_id, creatorPlayerId="900003",
           observedPlayers=observed, participantArrayShuffled=True, automaticTimeouts=4)


def fixture():
    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    private_path, public_path, token_path = [WORK / n for n in ["issuer-private.pem", "issuer-public.pem", "issuer-tokens.properties"]]
    private_path.touch(mode=0o600, exist_ok=True)
    private_path.chmod(0o600)
    private_path.write_bytes(private.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    public_path.write_bytes(private.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
    token_path.touch(mode=0o600, exist_ok=True)
    token_path.chmod(0o600)
    libs = WORK / "issuer-libs"
    libs.mkdir(exist_ok=True)
    with zipfile.ZipFile(ROOT / "target/game-service-0.0.1-SNAPSHOT.jar") as jar:
        for name in jar.namelist():
            if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
                (libs / Path(name).name).write_bytes(jar.read(name))
    extra = list((ROOT / ".build/m2/io/jsonwebtoken").glob("*/*/*.jar"))
    extra += list((ROOT / ".build/m2/org/jetbrains/kotlin").glob("kotlin-stdlib*/*/*.jar"))
    for jar in extra:
        if not jar.name.endswith(("-sources.jar", "-javadoc.jar")):
            link = libs / jar.name
            if not link.exists():
                link.symlink_to(jar)
    cp = os.pathsep.join([str(ROOT / "target/classes"), str(ROOT.parent / "auth-service/target/classes"), str(libs / "*")])
    subprocess.run(["javac", "-cp", cp, "-d", str(WORK), str(ROOT / "tools/AuthIssuerProbe.java")], check=True)
    subprocess.run(["java", "-cp", str(WORK) + os.pathsep + cp, "AuthIssuerProbe", str(private_path), str(public_path), str(token_path)], check=True)
    tokens = dict(line.strip().split("=", 1) for line in token_path.read_text().splitlines() if line and not line.startswith("#"))
    record("auth_compiled_issuer_compatibility", issuer="existing Auth JwtService", productionToken=False)
    return public_path, tokens


def recovered_game(prefix, game_id, baseline_version):
    samples = []
    until = time.monotonic() + 30
    while time.monotonic() < until:
        state = redis_state(prefix, game_id)
        samples.append({"version": state["version"], "turn": state["turn"]["number"],
                        "effects": [e for p in state["players"] for e in p["effects"]]})
        time.sleep(0.5)
    assert samples[-1]["version"] > baseline_version + 4, "Previously stalled bot game did not resume"
    assert all(a["version"] <= b["version"] for a, b in zip(samples, samples[1:]))
    (WORK / ("recovery-" + RUN + ".json")).write_text(json.dumps(samples, indent=2))
    record("existing_validation_game_recovered", gameId=game_id, previousVersion=baseline_version,
           recoveredVersion=samples[-1]["version"], observedSeconds=30, stateReset=False)


async def websocket_probe(base, prefix, tokens):
    token = tokens["900001"]
    status, board = http(base + "/api/game/board", token=token)
    assert status == 200 and board["cellCount"] == 96
    cells = board["cells"]
    assert len(cells) == 96 and {c["id"] for c in cells} == set(range(96))
    assert sum(c["safe"] for c in cells) == 12 and sum(c["kind"] == "EXIT" for c in cells) == 16
    assert {c["id"] for c in cells if c["kind"] == "CROWN"} == {83, 87, 91, 95}
    for seat, routes in board["routes"].items():
        assert len(routes) == 4
        for piece, route in enumerate(routes):
            assert len(route) == len(set(route)) == 69 and set(route) <= set(range(96))
            assert route[0] == 64 + int(seat) * 4 + piece and route[-1] == 83 + int(seat) * 4
    record("board_http", cells=96, safe=12, exits=16, crowns=[83, 87, 91, 95], routes=16)
    assert http(base + "/api/game/board")[0] == 401
    async with websockets.connect(base.replace("http://", "ws://") + "/ws/game") as anonymous:
        await anonymous.send(json.dumps({"type": "JOIN_GAME", "gameId": "unauthenticated"}))
        error = json.loads(await asyncio.wait_for(anonymous.recv(), timeout=5))
        assert error["type"] == "ERROR" and error["code"] == "UNAUTHENTICATED"
    record("websocket_authentication_required", errorCode="UNAUTHENTICATED")
    game_id = create(base, prefix, "websocket")
    assert http(base + "/api/game/games/" + game_id, token=tokens["900003"])[0] == 403
    sockets = {}
    received = {a: [] for a in ["900001", "900002"]}

    async def until(actor, kind, request_id=None):
        for _ in range(100):
            message = json.loads(await asyncio.wait_for(sockets[actor].recv(), timeout=8))
            received[actor].append(message)
            if message["type"] == kind and (request_id is None or message.get("requestId") == request_id):
                return message
        raise AssertionError("Expected WebSocket reply missing")

    async def send(actor, data):
        await sockets[actor].send(json.dumps(data))

    try:
        for actor in received:
            sockets[actor] = await websockets.connect(base.replace("http://", "ws://") + "/ws/game")
            await send(actor, {"type": "AUTH", "token": tokens[actor]})
            assert (await until(actor, "AUTHENTICATED"))["playerId"] == actor
            await send(actor, {"type": "JOIN_GAME", "gameId": game_id})
            await until(actor, "GAME_STATE")
        for attempt in range(40):
            state = redis_state(prefix, game_id)
            actor = state["turn"]["playerId"]
            other = "900002" if actor == "900001" else "900001"
            action = {"type": "ROLL_DICE", "gameId": game_id, "requestId": "runtime-roll-" + str(attempt), "expectedVersion": state["version"]}
            await send(actor, action)
            ack = await until(actor, "ACTION_ACK", action["requestId"])
            publication = await until(other, "GAME_UPDATED", action["requestId"])
            assert any(e["type"] == "DICE_ROLLED" for e in publication["events"])
            await send(actor, {"type": "SYNC_REQUEST", "gameId": game_id})
            sync = await until(actor, "GAME_STATE")
            if not sync["legalMoves"]:
                continue
            move = sync["legalMoves"][0]
            action = {"type": "MOVE_REQUEST", "gameId": game_id, "requestId": "runtime-move", "expectedVersion": sync["state"]["version"],
                      **{key: move[key] for key in ["pieceId", "dieIndex", "dieValue"]}, "fromPosition": move["from"], "targetPosition": move["to"]}
            await send(actor, action)
            ack = await until(actor, "ACTION_ACK", "runtime-move")
            event = await until(other, "GAME_UPDATED", "runtime-move")
            assert any(e["type"] == "PIECE_MOVED" for e in event["events"])
            await send(actor, action)
            duplicate = await until(actor, "ACTION_ACK", "runtime-move")
            assert duplicate["duplicate"] and duplicate["committedVersion"] == ack["committedVersion"]
            await send(other, {"type": "SYNC_REQUEST", "gameId": game_id})
            sync_other = await until(other, "GAME_STATE")
            assert sync_other["state"]["version"] >= ack["committedVersion"]
            record("websocket_real_jar", authenticatedSessions=2, diceEvent=True, movementEvent=True, duplicateRejected=True,
                   synchronizedVersion=sync_other["state"]["version"])
            return
        raise AssertionError("No release doubles within runtime dice attempts")
    finally:
        for ws in sockets.values():
            await ws.close()


def start(port, prefix, public_key, name="game-service", long_turn=False):
    env = dict(os.environ, MONGODB_URI="mongodb://localhost:27017/pixelchiss", REDIS_URL="redis://localhost:6379/0",
               EUREKA_URL="http://localhost:8761/eureka/", JWT_PUBLIC_KEY_PATH=str(public_key), GAME_LOBBY_TOKEN=LOBBY_TOKEN,
               GAME_TURN_MILLIS="60000" if long_turn else "6000", GAME_DISCONNECT_GRACE_MILLIS="60000")
    log = open(WORK / (name + "-" + str(port) + "-" + RUN + ".log"), "w")
    process = subprocess.Popen(["java", "-Djava.io.tmpdir=" + str(WORK), "-jar", str(ROOT / "target/game-service-0.0.1-SNAPSHOT.jar"),
                                "--server.port=" + str(port), "--spring.application.name=" + name,
                                "--game.redis-prefix=" + prefix], env=env, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    return process, log


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--external-base")
    parser.add_argument("--prefix")
    parser.add_argument("--lobby-token-file")
    parser.add_argument("--tokens-file", help="Isolated AuthIssuerProbe output, for external WebSocket validation")
    parser.add_argument("--recover-game", help="Previously created validation game to observe without resetting state")
    parser.add_argument("--baseline-version", type=int)
    args = parser.parse_args()
    global LOBBY_TOKEN
    processes = []
    try:
        if args.external_base:
            LOBBY_TOKEN = Path(args.lobby_token_file).read_text().strip()
            wait_health(args.external_base)
            if args.tokens_file:
                tokens = dict(line.strip().split("=", 1) for line in Path(args.tokens_file).read_text().splitlines()
                              if line and not line.startswith("#"))
                asyncio.run(websocket_probe(args.external_base, args.prefix, tokens))
            else:
                registry(int(args.external_base.rsplit(":", 1)[1]))
                automation(args.external_base, args.prefix)
                if args.recover_game:
                    recovered_game(args.prefix, args.recover_game, args.baseline_version)
        else:
            prefix = "pixelchiss:game:validation:" + RUN
            process, log = start(8084, prefix, ROOT.parent / "auth-service/keys/public_key.pem")
            processes.append((process, log))
            wait_health("http://localhost:8084", process)
            record("real_auth_public_key_startup", health="UP", port=8084)
            registry(8084)
            automation("http://localhost:8084", prefix)
            clockwise_turns("http://localhost:8084", prefix)
            public_key, tokens = fixture()
            prefix = "pixelchiss:game:validation:ws:" + RUN
            process, log = start(18084, prefix, public_key, name="game-service-validation", long_turn=True)
            processes.append((process, log))
            wait_health("http://localhost:18084", process)
            asyncio.run(websocket_probe("http://localhost:18084", prefix, tokens))
        RESULTS["status"] = "PASS"
    except Exception as error:
        RESULTS["status"] = "FAIL"
        RESULTS["error"] = type(error).__name__ + ": " + str(error)
        raise
    finally:
        for process, log in reversed(processes):
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            log.close()
        output = WORK / (("docker-" if args.external_base else "host-") + RUN + ".json")
        output.write_text(json.dumps(RESULTS, indent=2))
        print("Validation report: " + str(output), flush=True)


if __name__ == "__main__":
    main()
