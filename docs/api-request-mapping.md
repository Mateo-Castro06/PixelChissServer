# Catálogo de Request Mapping de PixelChiss

Este documento reúne los mappings HTTP y WebSocket implementados por los
microservicios y el API Gateway. La tabla usa las columnas solicitadas en el
reto: **Funcionalidad, Request Mapping, Microservicio, Método HTTP,
Parámetro / Tipo de dato y Retorno**.

## Direcciones de acceso

| Componente | Puerto | Base URL local | Tecnología |
| --- | ---: | --- | --- |
| API Gateway | 8080 | `http://localhost:8080` | Spring Cloud Gateway |
| Auth Service | 8081 | `http://localhost:8081` | Kotlin + Spring Boot |
| Lobby Service | 8083 | `http://localhost:8083` | Python + FastAPI |
| Game Service | 8084 | `http://localhost:8084` | Java + Spring Boot |
| Eureka Server | 8761 | `http://localhost:8761` | Spring Cloud Netflix Eureka |

Las rutas que aparecen como **públicas vía Gateway** son las que debe consumir
el cliente en el despliegue integrado. Las rutas **internas** se consumen entre
servicios dentro de la red privada y no deben exponerse como operaciones de
usuario. Los servicios también pueden probarse directamente en sus puertos
locales cuando estén publicados.

## Auth Service

Los mappings base del servicio son `/api/auth`, `/oauth2` y `/login`. En las
rutas OAuth2, Gateway reenvía la solicitud a Auth sin modificar el path.

| Funcionalidad | Request Mapping | Microservicio | Método HTTP | Parámetro / Tipo de dato | Retorno |
| --- | --- | --- | --- | --- | --- |
| Iniciar autenticación con Google | Público vía Gateway: `/oauth2/authorization/google`; directo: `http://localhost:8081/oauth2/authorization/google` | Auth Service | GET | Query requerida: `client_state` (string, 16–128 caracteres alfanuméricos, `_` o `-`); `client_port` (integer, 1024–65535). | `302`: redirección al proveedor OAuth2. |
| Completar autenticación OAuth2 | Público vía Gateway: `/login/oauth2/code/google`; directo: `http://localhost:8081/login/oauth2/code/google` | Auth Service | GET | Parámetros OAuth2 `code` y `state`, gestionados por Spring Security y Google. | `302`: redirección a `http://127.0.0.1:{client_port}/callback` con `accessToken`, `refreshToken` y `state` en query. |
| Renovar access token | Público vía Gateway: `POST /api/refresh` → Auth `POST /api/auth/refresh`; directo: `POST http://localhost:8081/api/auth/refresh` | Auth Service | POST | JSON `RefreshTokenRequest`: `{"refreshToken":"<refresh-token>"}`. No requiere access token. | `200`: `TokenResponse` con `accessToken`; `401` si el refresh token no es válido. |
| Cerrar sesión | Público vía Gateway: `POST /api/logout` → Auth `POST /api/auth/logout`; directo: `POST http://localhost:8081/api/auth/logout` | Auth Service | POST | JSON `LogoutRequest`: `{"refreshToken":"<refresh-token>"}`. | `200`: `MessageResponse` con mensaje de confirmación. |
| Consultar perfil propio | Público vía Gateway/directo: `GET /api/auth/me` | Auth Service | GET | Encabezado `Authorization: Bearer <accessToken>`. | `200`: `UserProfileResponse` (`id`, `email`, `displayName`, `createdAt`); `401` o `404` en error. |
| Crear o actualizar nombre visible | Público vía Gateway/directo: `PUT /api/auth/me/display-name` | Auth Service | PUT | Encabezado Bearer; JSON `UpdateDisplayNameRequest`: `{"displayName":"Jugador_01"}`. El nombre admite de 3 a 24 caracteres: letras ASCII, números y `_`. | `200`: `UserProfileResponse`; `400`, `401` o `404` en error. |
| Verificar JWT protegido (endpoint de prueba) | Directo: `GET http://localhost:8081/api/test/protected`; no tiene ruta dedicada en Gateway. | Auth Service | GET | Encabezado `Authorization: Bearer <accessToken>`. | `200`: JSON con mensaje de validación e identificador del usuario autenticado. |
| Consultar salud | Directo: `GET http://localhost:8081/actuator/health` | Auth Service | GET | Sin parámetros. | `200`: estado de salud de Spring Actuator. |

## Lobby Service

Los endpoints de salas están bajo `/api/lobbies` y se reenvían por Gateway con
el mismo path. Swagger de Lobby se sirve directamente desde el servicio.
Todos los endpoints de esta tabla requieren un JWT de usuario, excepto
`/health/redis`.

| Funcionalidad | Request Mapping | Microservicio | Método HTTP | Parámetro / Tipo de dato | Retorno |
| --- | --- | --- | --- | --- | --- |
| Consultar salud de Redis | Directo: `GET http://localhost:8083/health/redis` | Lobby Service | GET | Sin parámetros; no requiere JWT. | `200`: `{"redis":"UP"}` si Redis responde o `{"redis":"DOWN"}` si no responde. |
| Crear sala | Público vía Gateway/directo: `POST /api/lobbies` | Lobby Service | POST | Encabezado Bearer; sin parámetros ni body. | `201`: `LobbyResponse` con código de seis caracteres y creador/anfitrión. |
| Listar mapas | Público vía Gateway/directo: `GET /api/lobbies/maps` | Lobby Service | GET | Encabezado Bearer; sin parámetros ni body. | `200`: lista de `MapResponse`. |
| Consultar sala | Público vía Gateway/directo: `GET /api/lobbies/{code}` | Lobby Service | GET | `code` (string, path); Bearer; el usuario debe pertenecer a la sala. | `200`: `LobbyResponse`. |
| Unirse a sala | Público vía Gateway/directo: `POST /api/lobbies/{code}/join` | Lobby Service | POST | `code` (string, path); encabezado Bearer. | `200`: `LobbyResponse` actualizada; `404` o `409` si no existe, está llena o ya inició. |
| Salir de sala | Público vía Gateway/directo: `POST /api/lobbies/{code}/leave` | Lobby Service | POST | `code` (string, path); Bearer; el usuario debe pertenecer a la sala. | `204`: sin body. |
| Cambiar estado de listo | Público vía Gateway/directo: `PUT /api/lobbies/{code}/ready` | Lobby Service | PUT | `code` (string, path); Bearer; JSON `ReadyRequest`: `{"ready":true}` o `{"ready":false}`. | `200`: `LobbyResponse` actualizada. |
| Añadir bot | Público vía Gateway/directo: `POST /api/lobbies/{code}/bots` | Lobby Service | POST | `code` (string, path); Bearer de anfitrión. | `201`: `LobbyResponse` actualizada con el bot. |
| Quitar bot | Público vía Gateway/directo: `DELETE /api/lobbies/{code}/bots/{bot_id}` | Lobby Service | DELETE | `code` y `bot_id` (string, path); Bearer de anfitrión. | `200`: `LobbyResponse` actualizada. |
| Votar por mapa | Público vía Gateway/directo: `PUT /api/lobbies/{code}/vote` | Lobby Service | PUT | `code` (string, path); Bearer; JSON `VoteRequest`: `{"map_id":"classic"}`. El identificador debe estar en `GET /api/lobbies/maps`. | `200`: `LobbyResponse` actualizada con los votos. |
| Expulsar participante | Público vía Gateway/directo: `DELETE /api/lobbies/{code}/players/{player_id}` | Lobby Service | DELETE | `code` y `player_id` (string, path); Bearer de anfitrión. | `200`: `LobbyResponse` actualizada. |
| Iniciar partida | Público vía Gateway/directo: `POST /api/lobbies/{code}/start` | Lobby Service | POST | `code` (string, path); Bearer de anfitrión; mínimo dos participantes y todos listos. | `200`: `LobbyResponse` con estado iniciado, mapa e identificador de partida. |

`LobbyResponse` contiene el código, anfitrión, creador original, estado, versión,
cupo, participantes, votos, mapa, expulsados e identificador de partida cuando
corresponda. Los errores usan un objeto con `detail`. Una sala admite como máximo
cuatro participantes contando los bots.

## Game Service

Las operaciones REST públicas de Game usan JWT Bearer, excepto salud y la
integración interna con Lobby. El inicio interno requiere `X-Lobby-Token` y no
se publica por el Gateway.

| Funcionalidad | Request Mapping | Microservicio | Método HTTP | Parámetro / Tipo de dato | Retorno |
| --- | --- | --- | --- | --- | --- |
| Consultar definición del tablero | Público vía Gateway/directo: `GET /api/game/board` | Game Service | GET | Encabezado `Authorization: Bearer <accessToken>`. | `200`: definición del tablero (`layout`, `cellCount`, casillas seguras, trivia, celdas y rutas). |
| Consultar estado de partida | Público vía Gateway/directo: `GET /api/game/games/{gameId}` | Game Service | GET | `gameId` (string, path; 1–80 letras, números, `_` o `-`); Bearer; el jugador debe pertenecer a la partida. | `200`: `GameView` consistente; `400`, `401`, `403`, `404` o `503` según error. |
| Inicializar partida desde Lobby | Interno, no enrutado por Gateway: `POST http://game-service:8084/internal/games` | Game Service | POST | Encabezado `X-Lobby-Token`; JSON `CreateGame`: `gameId`, `lobbyId`, `mapId`, `participants` (2–4 con `playerId`, `bot` y asiento único 0–3), `creatorPlayerId`; `firstPlayerId` es opcional/deprecado. | `200`: `GameView`; `400`, `403`, `409` o `503` en error. Los reintentos idénticos son idempotentes. |
| Diagnosticar MongoDB (opcional) | Público vía Gateway: `GET /api/game/test/mongodb` → interno `GET /test/mongodb`; directo: `GET http://localhost:8084/test/mongodb` | Game Service | GET | Bearer. Ruta deshabilitada salvo que `game.diagnostics.enabled=true`; al ejecutarse escribe un documento de prueba. | `200`: texto de diagnóstico; `401` o `503` en error. |
| Diagnosticar Redis (opcional) | Público vía Gateway: `GET /api/game/test/redis` → interno `GET /test/redis`; directo: `GET http://localhost:8084/test/redis` | Game Service | GET | Bearer. Ruta deshabilitada salvo que `game.diagnostics.enabled=true`; al ejecutarse escribe una clave de prueba. | `200`: texto de diagnóstico; `401` o `503` en error. |
| Consultar salud | Directo: `GET http://localhost:8084/actuator/health` | Game Service | GET | Sin parámetros. | `200`: estado de salud de Spring Actuator. |

`GameView` incluye estado/versionado de la partida, mapa, jugadores, fichas,
turno y desafíos pendientes; no expone claves de respuestas ni recibos internos.
Para jugar en tiempo real se utiliza el WebSocket documentado a continuación.

## API Gateway y WebSocket de Game

El Gateway ofrece estas rutas para Game. El WebSocket se lista aparte de REST
porque, después del handshake HTTP, intercambia mensajes JSON en ambas
direcciones.

| Funcionalidad | Request Mapping | Microservicio | Método HTTP | Parámetro / Tipo de dato | Retorno |
| --- | --- | --- | --- | --- | --- |
| Abrir Swagger de Game | `GET http://localhost:8080/api/game/swagger-ui/index.html` | API Gateway → Game Service | GET | Sin parámetros. | `200`: Swagger UI de Game. |
| Obtener OpenAPI público de Game | `GET http://localhost:8080/api/game/openapi` | API Gateway → Game Service | GET | Sin parámetros. | `200`: JSON OpenAPI público; excluye la inicialización interna de partidas. |
| Consultar salud del Gateway | Directo: `GET http://localhost:8080/actuator/health` | API Gateway | GET | Sin parámetros. | `200`: estado de salud de Spring Actuator. |
| Conectar al juego en tiempo real | Gateway: `ws://localhost:8080/api/game/ws`; directo: `ws://localhost:8084/ws/game` (usar `wss` con TLS). | API Gateway → Game Service | WebSocket (handshake HTTP GET/Upgrade) | Primer mensaje JSON obligatorio dentro de 10 segundos: `{"type":"AUTH","token":"<accessToken>"}`. Luego `JOIN_GAME`, `SYNC_REQUEST` y acciones JSON del protocolo. No se permite query string en el handshake. | `101` al establecer WebSocket; mensajes JSON como `AUTHENTICATED`, `GAME_STATE`, `ACTION_ACK`, `GAME_UPDATED` y errores. |

El contrato de mensajes, acciones, errores y ejemplos de WebSocket está en
[game-service/docs/WEBSOCKET.md](../game-service/docs/WEBSOCKET.md). El
Gateway no expone `POST /internal/games`.

## Documentación Swagger/OpenAPI

| API | Swagger UI | Documento OpenAPI |
| --- | --- | --- |
| Lobby | `http://localhost:8083/docs` | `http://localhost:8083/openapi.json` |
| Game directo | `http://localhost:8084/swagger-ui/index.html` | `http://localhost:8084/v3/api-docs` |
| Game vía Gateway | `http://localhost:8080/api/game/swagger-ui/index.html` | `http://localhost:8080/api/game/openapi` |
| Auth | `http://localhost:8081/swagger-ui/index.html` | `http://localhost:8081/v3/api-docs` |

Swagger es una interfaz para explorar y ejecutar las operaciones HTTP; los
endpoints protegidos requieren un JWT de usuario. `GAME_LOBBY_TOKEN` es un
secreto de servicio usado exclusivamente para la integración privada Lobby →
Game y no debe introducirse como JWT ni publicarse.

## Fuentes de los mappings

- Auth: `../auth-service/src/main/kotlin/com/pixelchiss/auth/controller/`.
- Lobby: `../lobby-service/app/api/lobbies.py` y `../lobby-service/main.py`.
- Game: `../game-service/src/main/java/com/pixelchiss/gameservice/controller/`.
- Gateway: `../api-gateway/src/main/resources/application.yaml`.
- WebSocket: `game-service/docs/WEBSOCKET.md`.
