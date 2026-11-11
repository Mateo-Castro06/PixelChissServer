"""Create and play an isolated proof game. Never point this at development."""
import asyncio
import base64
import json
import os
import time
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

import websockets

GATEWAY = os.getenv("PROOF_GATEWAY", "http://127.0.0.1:18080")
AUTH = os.getenv("PROOF_AUTH", "http://127.0.0.1:18081")
LOBBY = os.getenv("PROOF_LOBBY", "http://127.0.0.1:18083")
GAME = os.getenv("PROOF_GAME", "http://127.0.0.1:18084")
EUREKA = os.getenv("PROOF_EUREKA", "http://127.0.0.1:18761")
PROM = os.getenv("PROOF_PROMETHEUS", "http://127.0.0.1:19090")
GRAFANA = os.getenv("PROOF_GRAFANA", "http://127.0.0.1:13000")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request(base, path, *, method="GET", body=None, token=None, status=200, headers=None):
    headers = {"Accept": "application/json", **(headers or {})}
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        headers["Content-Type"] = "application/json"
    req = Request(base + path, None if body is None else json.dumps(body).encode(), headers, method=method)
    try:
        response = build_opener(NoRedirect).open(req, timeout=12)
    except HTTPError as error:
        response = error
    with response:
        data = response.read()
        assert response.status == status, f"{method} {urlsplit(path).path}: {response.status}, expected {status}"
        if data and "json" in response.headers.get("Content-Type", ""):
            return json.loads(data)
        return data


async def wait_message(ws, predicate, timeout=12):
    async with asyncio.timeout(timeout):
        while True:
            message = json.loads(await ws.recv())
            if message.get("type") == "ERROR" and message.get("code") not in {
                "STALE_VERSION", "CONCURRENT_UPDATE",
            }:
                raise AssertionError(f"WebSocket error: {message.get('code')}")
            if predicate(message):
                return message


async def socket_probe(token, game_id, actor):
    ws_uri = "ws" + GATEWAY.removeprefix("http") + "/api/game/ws"

    async def send(ws, message):
        await ws.send(json.dumps(message))

    async with websockets.connect(ws_uri) as ws:
        await send(ws, {"type": "AUTH", "token": token})
        await wait_message(ws, lambda m: m["type"] == "AUTHENTICATED")
        await send(ws, {"type": "JOIN_GAME", "gameId": game_id})
        joined = await wait_message(ws, lambda m: m["type"] == "GAME_STATE")
        state = joined["state"]
        assert state["turn"]["playerId"] == actor
        # Presence maintenance can win a race; a rejected version does not consume the action.
        for _ in range(5):
            action = {"type": "ROLL_DICE", "gameId": game_id, "requestId": "proof-roll",
                      "expectedVersion": state["version"]}
            await send(ws, action)
            result = await wait_message(ws, lambda m: m.get("requestId") == "proof-roll"
                                        and m["type"] in {"ACTION_ACK", "ERROR"})
            if result["type"] == "ACTION_ACK":
                break
            await send(ws, {"type": "SYNC_REQUEST", "gameId": game_id})
            state = (await wait_message(ws, lambda m: m["type"] == "GAME_STATE"))["state"]
        else:
            raise AssertionError("ROLL_DICE could not commit")

        await send(ws, action)
        duplicate = await wait_message(ws, lambda m: m.get("requestId") == "proof-roll"
                                       and m["type"] == "ACTION_ACK")
        assert duplicate["duplicate"] is True and duplicate["committedVersion"] == result["committedVersion"]
        state = duplicate["state"]
        if state["turn"]["playerId"] == actor:
            await send(ws, {"type": "END_TURN", "gameId": game_id, "requestId": "proof-end",
                            "expectedVersion": state["version"]})
            ended = await wait_message(ws, lambda m: m.get("requestId") == "proof-end"
                                       and m["type"] == "ACTION_ACK")
            assert ended["state"]["turn"]["playerId"] != actor
        await wait_message(ws, lambda m: m["type"] == "GAME_UPDATED" and any(
            event["type"] == "DICE_ROLLED" and event["data"]["playerId"] != actor
            for event in m["events"]
        ))
    async with websockets.connect(ws_uri) as ws:
        await send(ws, {"type": "AUTH", "token": token})
        await wait_message(ws, lambda m: m["type"] == "AUTHENTICATED")
        await send(ws, {"type": "SYNC_REQUEST", "gameId": game_id})
        resumed = await wait_message(ws, lambda m: m["type"] == "GAME_STATE")
        assert resumed["state"]["gameId"] == game_id
        assert resumed["state"]["version"] > joined["state"]["version"]


def monitor():
    targets = {}
    for _ in range(12):
        response = request(PROM, "/api/v1/targets")
        targets = {t["labels"]["service"]: t["health"] for t in response["data"]["activeTargets"]}
        if len(targets) == 5 and all(value == "up" for value in targets.values()):
            break
        time.sleep(2)
    assert len(targets) == 5 and set(targets.values()) == {"up"}, targets
    for metric in ("pixelchiss_game_integration_events_total", "pixelchiss_lobby_start_operations_total",
                   "pixelchiss_lobby_integration_events_total", "pixelchiss_lobby_http_request_seconds_count"):
        for _ in range(12):
            result = request(PROM, "/api/v1/query?" + urlencode({"query": metric}))["data"]["result"]
            if result:
                break
            time.sleep(2)
        else:
            raise AssertionError(f"Prometheus has no sample for {metric}")
    credentials = base64.b64encode(("admin:" + os.environ["PROOF_GRAFANA_PASSWORD"]).encode()).decode()
    headers = {"Authorization": "Basic " + credentials}
    dashboard = request(GRAFANA, "/api/dashboards/uid/pixelchiss-services", headers=headers)["dashboard"]
    assert len(dashboard["panels"]) >= 8
    datasource = request(GRAFANA, "/api/datasources/uid/pixelchiss-prometheus/health", headers=headers)
    assert datasource["status"] == "OK"
    return targets


def main():
    request(GATEWAY, "/actuator/health")
    assert request(LOBBY, "/health/redis") == {"redis": "UP"}
    expected = {"AUTH-SERVICE", "LOBBY-SERVICE", "GAME-SERVICE", "API-GATEWAY"}
    for _ in range(15):
        apps = request(EUREKA, "/eureka/apps")["applications"].get("application", [])
        names = {a["name"] for a in apps if any(i["status"] == "UP" for i in a["instance"])}
        if expected <= names:
            break
        time.sleep(2)
    assert expected <= names, names
    request(GATEWAY, "/oauth2/authorization/google?client_state=proofstate0123456789&client_port=8910", status=302)
    request(GATEWAY, "/api/lobbies/maps", status=401)
    refresh = os.environ["PROOF_REFRESH_TOKEN"]
    token = request(GATEWAY, "/api/refresh", method="POST", body={"refreshToken": refresh})["accessToken"]
    profile = request(GATEWAY, "/api/auth/me", token=token)
    assert set(profile) == {"id", "displayName", "email", "createdAt"}
    names = request(GATEWAY, "/api/lobbies/maps", token=token)
    assert [m["id"] for m in names] == ["classic", "science"]
    room = request(GATEWAY, "/api/lobbies", method="POST", token=token, status=201)
    assert room["players"][0]["display_name"] == profile["displayName"]
    path = f"/api/lobbies/{room['code']}"
    request(GATEWAY, path + "/bots", method="POST", token=token, status=201)
    request(GATEWAY, path + "/vote", method="PUT", token=token, body={"map_id": "classic"})
    started = request(GATEWAY, path + "/start", method="POST", token=token)
    assert started["status"] == "STARTED" and started["map_id"] == "classic"
    request(GATEWAY, path + "/start", method="POST", token=token, status=409)
    actor = str(profile["id"])
    state = request(GATEWAY, "/api/game/games/" + started["game_id"], token=token)
    assert [p["seat"] for p in state["players"]] == [0, 2]
    assert state["players"][1]["bot"] and state["turn"]["playerId"] == actor
    asyncio.run(socket_probe(token, started["game_id"], actor))

    assert "/api/auth/me" in request(AUTH, "/v3/api-docs")["paths"]
    assert "/api/lobbies/{code}/start" in request(LOBBY, "/openapi.json")["paths"]
    assert "/internal/games" not in request(GATEWAY, "/api/game/openapi")["paths"]
    assert "/internal/games" in request(GAME, "/v3/api-docs")["paths"]
    for base, path in [(AUTH, "/swagger-ui/index.html"), (LOBBY, "/docs"),
                       (GATEWAY, "/api/game/swagger-ui/index.html")]:
        request(base, path)

    request(GATEWAY, "/actuator/prometheus", token=token, status=403)
    request(LOBBY, "/internal/metrics", status=403)
    request(EUREKA, "/actuator/prometheus", status=403)
    targets = monitor()
    request(GATEWAY, "/api/logout", method="POST", body={"refreshToken": refresh})
    request(GATEWAY, "/api/refresh", method="POST", body={"refreshToken": refresh}, status=401)
    print(json.dumps({"result": "PASS", "eureka": sorted(["API-GATEWAY", "AUTH-SERVICE", "GAME-SERVICE", "LOBBY-SERVICE"]),
                      "swagger_ui": 3, "openapi_documents": 4, "prometheus_targets": targets,
                      "grafana_panels": 8, "gateway_flow": ["oauth_redirect", "jwt_refresh", "profile", "lobby",
                      "start", "ws_roll", "duplicate_ack", "bot", "reconnect", "logout"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
