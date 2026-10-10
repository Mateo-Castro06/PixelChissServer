"""Real Gateway -> Eureka -> Game probes. Retains validation games; stops only owned processes."""
import asyncio
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
from types import SimpleNamespace
import urllib.error
import urllib.request
import uuid

import websockets

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
GAME = ROOT.parent / "game-service"
RUN = uuid.uuid4().hex[:12]
WORK = ROOT / ".build" / "gateway-runtime" / RUN
WORK.mkdir(parents=True)
spec = importlib.util.spec_from_file_location("game_probes", GAME / "tools/validate_runtime.py")
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
probe.WORK, probe.RUN = WORK, RUN
PREFIX = "pixelchiss:game:gateway-validation:" + RUN
GATEWAY = "http://localhost:18080"
GAME_BASE = "http://localhost:18084"
WS_URL = "ws://localhost:18080/api/game/ws"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def raw(base, path, method="GET", body=None, token=None):
    headers = {}
    if token: headers["Authorization"] = "Bearer " + token
    if body is not None: headers["Content-Type"] = "application/json"
    request = urllib.request.Request(base + path, method=method, headers=headers,
                                    data=None if body is None else json.dumps(body).encode())
    try:
        response = urllib.request.build_opener(NoRedirect).open(request, timeout=8)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.code, response.read().decode(), response.headers


def start_gateway(port, public_key):
    env = dict(os.environ, JWT_PUBLIC_KEY_PATH=str(public_key), EUREKA_URL="http://localhost:8761/eureka/")
    log = open(WORK / ("gateway-" + str(port) + ".log"), "w")
    process = subprocess.Popen(["java", "-Djava.io.tmpdir=" + str(WORK), "-jar",
                                str(ROOT / "target/api-gateway-0.0.1-SNAPSHOT.jar"), "--server.port=" + str(port),
                                "--eureka.client.register-with-eureka=false"], env=env, cwd=ROOT,
                               stdout=log, stderr=subprocess.STDOUT)
    return process, log


def rest_and_docs(tokens):
    status, board = probe.http(GATEWAY + "/api/game/board", token=tokens["900001"])
    assert status == 200 and board["cellCount"] == 96
    assert probe.http(GATEWAY + "/api/game/board")[0] == 401
    assert probe.http(GATEWAY + "/api/game/board", token="invalid")[0] == 401
    for path in ["/api/game/api/game/board", "/internal/games", "/api/game/internal/games"]:
        assert raw(GATEWAY, path, token=tokens["900001"])[0] == 404
    assert raw(GATEWAY, "/api/game/internal/games", "POST", {}, tokens["900001"])[0] == 404
    game_id = probe.create(GAME_BASE, PREFIX, "rest")
    assert probe.http(GATEWAY + "/api/game/games/" + game_id, token=tokens["900001"])[0] == 200
    assert probe.http(GATEWAY + "/api/game/games/" + game_id, token=tokens["900003"])[0] == 403
    assert probe.http(GATEWAY + "/api/game/games/not-a-game", token=tokens["900001"])[0] == 404
    for name in ["mongodb", "redis"]:
        assert raw(GATEWAY, "/api/game/test/" + name)[0] == 401
        assert raw(GATEWAY, "/api/game/test/" + name, token=tokens["900001"])[0] == 404
    probe.record("gateway_rest_and_authorization", board=200, member=200, outsider=403, missing=404,
                 anonymous=401, invalidJwt=401, internalNotExposed=True, noDuplicatePrefix=True,
                 diagnosticsDisabled=404)
    status, spec = probe.http(GATEWAY + "/api/game/openapi")
    assert status == 200
    assert set(spec["paths"]) == {"/api/game/board", "/api/game/games/{gameId}"}
    assert spec["servers"][0]["url"] == "/"
    (WORK / "public-openapi.json").write_text(json.dumps(spec, indent=2))
    internal_status, internal_spec = probe.http(GAME_BASE + "/v3/api-docs")
    assert internal_status == 200 and set(internal_spec["paths"]) == set(spec["paths"]) | {"/internal/games"}
    (WORK / "internal-openapi.json").write_text(json.dumps(internal_spec, indent=2))
    for asset in ["index.html", "swagger-ui.css", "swagger-ui-bundle.js", "swagger-ui-standalone-preset.js"]:
        code, body, headers = raw(GATEWAY, "/api/game/swagger-ui/" + asset)
        assert code == 200 and body, (asset, code)
        if asset == "index.html": assert 'url: "/api/game/openapi"' in body
    assert raw(GAME_BASE, "/swagger-ui/index.html")[0] == 200
    probe.record("gateway_swagger", activeRestOperations=3, internalDocumentOperations=3,
                 publicDocumentOperations=2, uiAndAssets=200, internalInitializationExcluded=True)


async def websocket_security():
    for first, expected in [({"type": "ROLL_DICE", "gameId": "no-auth"}, "UNAUTHENTICATED"),
                            ({"type": "AUTH", "token": "invalid"}, "INVALID_TOKEN")]:
        async with websockets.connect(WS_URL, origin="http://localhost:8080") as ws:
            await ws.send(json.dumps(first))
            error = json.loads(await asyncio.wait_for(ws.recv(), 5))
            assert error["code"] == expected
    for url, origin, expected in [(WS_URL + "?token=invalid", None, 400),
                                  (WS_URL, "http://untrusted.invalid", 403)]:
        try:
            async with websockets.connect(url, origin=origin):
                raise AssertionError("Invalid handshake accepted")
        except (websockets.exceptions.InvalidStatusCode, websockets.exceptions.InvalidStatus) as error:
            actual = error.status_code if hasattr(error, "status_code") else error.response.status_code
            assert actual == expected
    probe.record("gateway_websocket_security", bearerlessHandshake=True, unauthenticatedAction="UNAUTHENTICATED",
                 badAuth="INVALID_TOKEN", queryRejected=400, disallowedOrigin=403)


async def automatic_bots(tokens):
    game_id = "validation-" + RUN + "-gateway-bots"
    body = {"gameId": game_id, "lobbyId": "validation-" + RUN, "mapId": "classic",
            "creatorPlayerId": "900001", "participants": [
                {"playerId": "bot-3", "bot": True, "seat": 3},
                {"playerId": "900001", "bot": False, "seat": 0},
                {"playerId": "bot-1", "bot": True, "seat": 1}]}
    code, state = probe.http(GAME_BASE + "/internal/games", body=body, lobby=True)
    assert code == 200 and state["turn"]["playerId"] == "900001"
    probe.RESULTS["retainedGames"].append({"gameId": game_id, "redisPrefix": PREFIX})
    async with websockets.connect(WS_URL) as ws:
        await ws.send(json.dumps({"type": "AUTH", "token": tokens["900001"]}))
        assert json.loads(await ws.recv())["type"] == "AUTHENTICATED"
        await ws.send(json.dumps({"type": "JOIN_GAME", "gameId": game_id}))
        rolled, moved, advanced, extras = False, False, False, False
        observed = ["900001"]
        last_turn, actor = 1, "900001"
        started = time.monotonic()
        while time.monotonic() - started < 120:
            message = json.loads(await asyncio.wait_for(ws.recv(), 12))
            if message["type"] != "GAME_UPDATED": continue
            for event in message["events"]:
                if event["type"] == "DICE_ROLLED" and event["data"]["playerId"].startswith("bot-"):
                    assert len(event["data"]["dice"]) == 2 and all(1 <= value <= 6 for value in event["data"]["dice"])
                    rolled = True
                if event["type"] == "PIECE_MOVED":
                    piece = next(p for p in message["state"]["pieces"] if p["id"] == event["data"]["pieceId"])
                    moved |= piece["ownerId"].startswith("bot-")
                if event["type"] == "TURN_CHANGED":
                    next_actor = event["data"]["playerId"]
                    assert event["data"]["turnNumber"] == last_turn + 1
                    successor = {"900001": "bot-1", "bot-1": "bot-3", "bot-3": "900001"}[actor]
                    assert next_actor in [actor, successor], (actor, next_actor)
                    extras |= next_actor == actor
                    advanced |= next_actor == "900001" and actor != "900001"
                    observed.append(next_actor)
                    actor, last_turn = next_actor, last_turn + 1
            if rolled and moved and advanced and time.monotonic() - started >= 35:
                snapshot = probe.redis_state(PREFIX, game_id)
                assert not snapshot["players"][0]["retired"]
                probe.record("gateway_automatic_bots", gameId=game_id, creatorFirst=True, serverDice=True,
                             botPieceMoved=True, botEventsReceived=True, clockwise=True, observedPlayers=observed,
                             clientCommands=["AUTH", "JOIN_GAME"], connectedSeconds=round(time.monotonic() - started),
                             extraTurnObserved=extras, version=snapshot["version"])
                return
        raise AssertionError("No automatic bot movement/clockwise cycle in 120 seconds")


def actual_auth_routes(base):
    for path, internal in [("/api/refresh", "/api/auth/refresh"), ("/api/logout", "/api/auth/logout")]:
        payload = {"refreshToken": "gateway-validation-nonexistent-" + RUN}
        direct = raw("http://localhost:8081", internal, "POST", payload)
        through = raw(base, path, "POST", payload)
        assert through[0] == direct[0] and through[0] in [200, 401], (path, direct[0], through[0])
        probe.record("existing_auth_route", publicPath=path, internalPath=internal, status=through[0], backend="running Auth")
    assert raw("http://localhost:8081", "/oauth2/authorization/google")[0] == 400
    assert raw(base, "/oauth2/authorization/google")[0] == 400
    oauth_path = "/oauth2/authorization/google?client_state=" + RUN + "-gateway-probe&client_port=18089"
    direct = raw("http://localhost:8081", oauth_path)
    through = raw(base, oauth_path)
    assert direct[0] == through[0] == 302
    assert "accounts.google.com" in through[2].get("Location", "")
    assert raw(base, "/api/auth/me")[0] == 401
    probe.record("existing_auth_oauth_start", status=302, redirectHost="accounts.google.com", anonymousProfile=401,
                 completedGoogleLogin=False)


def start_auth_gateway():
    # Auth advertises its container DNS name. Validate in that same existing network.
    name = "pixelchiss-gateway-validation-" + RUN
    command = ["docker", "run", "-d", "--name", name,
               "--network", os.getenv("GATEWAY_VALIDATION_NETWORK", "docker_pixelchiss-network"),
               "-p", "127.0.0.1:18081:18081",
               "--mount", "type=bind,source=" + str(ROOT / "target/api-gateway-0.0.1-SNAPSHOT.jar") + ",target=/app/app.jar,readonly",
               "--mount", "type=bind,source=" + str(ROOT.parent / "auth-service/keys/public_key.pem") + ",target=/app/public.pem,readonly",
               "-e", "JWT_PUBLIC_KEY_PATH=/app/public.pem", "-e", "EUREKA_URL=http://pixelchiss-eureka:8761/eureka/",
               os.getenv("GATEWAY_VALIDATION_IMAGE", "pixelchiss-game:validation-fixed-20261010"),
               "--server.port=18081", "--eureka.client.register-with-eureka=false"]
    subprocess.run(command, check=True, stdout=subprocess.DEVNULL)
    return name


def main():
    processes = []
    containers = []
    for port in [18080, 18081, 18084]:
        with socket.socket() as sock:
            assert sock.connect_ex(("localhost", port)) != 0, "Validation port occupied: " + str(port)
    try:
        public_key, tokens = probe.fixture()
        process, log = probe.start(18084, PREFIX, public_key)
        processes.append((process, log))
        probe.wait_health(GAME_BASE, process)
        probe.registry(18084)
        process, log = start_gateway(18080, public_key)
        processes.append((process, log))
        probe.wait_health(GATEWAY, process)
        rest_and_docs(tokens)
        asyncio.run(websocket_security())
        # Reuse the direct probe, adapting only the public WS path and the private Lobby caller.
        original_http = probe.http
        def integration_http(url, **kwargs):
            if url == GATEWAY + "/internal/games": url = GAME_BASE + "/internal/games"
            return original_http(url, **kwargs)
        probe.http = integration_http
        probe.websockets = SimpleNamespace(connect=lambda url, **kwargs:
                websockets.connect(url.replace("/ws/game", "/api/game/ws"), **kwargs))
        asyncio.run(probe.websocket_probe(GATEWAY, PREFIX, tokens))
        probe.record("gateway_websocket_game_flow", route="/api/game/ws", upstream="/ws/game", discovery="GAME-SERVICE")
        asyncio.run(automatic_bots(tokens))
        probe.clockwise_turns(GAME_BASE, PREFIX)
        containers.append(start_auth_gateway())
        probe.wait_health("http://localhost:18081")
        actual_auth_routes("http://localhost:18081")
        probe.RESULTS["status"] = "PASS"
    except Exception as error:
        probe.RESULTS["status"] = "FAIL"
        probe.RESULTS["error"] = type(error).__name__ + ": " + str(error)
        raise
    finally:
        for container in reversed(containers):
            with open(WORK / "gateway-auth-docker.log", "w") as log:
                subprocess.run(["docker", "logs", container], stdout=log, stderr=subprocess.STDOUT)
            subprocess.run(["docker", "stop", container], check=True, stdout=subprocess.DEVNULL)
        for process, log in reversed(processes):
            process.terminate()
            try: process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            log.close()
        report = WORK / "report.json"
        report.write_text(json.dumps(probe.RESULTS, indent=2))
        print("Gateway validation report: " + str(report), flush=True)


if __name__ == "__main__":
    main()
