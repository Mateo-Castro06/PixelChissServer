# Catálogo de Request Mapping de PixelChiss

Esta es la tabla consolidada de endpoints implementados en los microservicios y
el API Gateway. Cada operación aparece en una misma tabla; la columna
**Microservicio** identifica quién la implementa y, cuando corresponde, por qué
componente se accede.

Los endpoints de Auth, Lobby y Game que indican **Gateway** se consumen en el
despliegue integrado a través de `http://localhost:8080`. Los endpoints
marcados **Directo** usan el puerto publicado del servicio. Las rutas internas
son para comunicación servicio-a-servicio y no deben exponerse a clientes.
Los endpoints de Lobby requieren JWT, excepto salud de Redis. Las operaciones
de Game públicas requieren JWT, salvo que la tabla indique otra cosa.

| Funcionalidad | Request Mapping | Microservicio | Método HTTP | Parámetro / Tipo de dato | Retorno |
| --- | --- | --- | --- | --- | --- |
| Iniciar autenticación con Google | Gateway: `/oauth2/authorization/google`; directo: `http://localhost:8081/oauth2/authorization/google` | Auth Service (vía API Gateway) | GET | Query: `client_state` (string, 16–128 caracteres alfanuméricos, `_` o `-`); `client_port` (integer, 1024–65535). | `302`: redirección al proveedor OAuth2. |
| Completar autenticación OAuth2 | Gateway: `/login/oauth2/code/google`; directo: `http://localhost:8081/login/oauth2/code/google` | Auth Service (vía API Gateway) | GET | Parámetros OAuth2 `code` y `state`, gestionados por Spring Security y Google. | `302`: redirección al cliente con `accessToken`, `refreshToken` y `state` en query. |
| Renovar access token | Gateway: `POST /api/refresh` → Auth `POST /api/auth/refresh`; directo: `POST http://localhost:8081/api/auth/refresh` | Auth Service (vía API Gateway) | POST | JSON `RefreshTokenRequest`: `{"refreshToken":"<refresh-token>"}`. | `200`: `TokenResponse` con `accessToken`; `401` si el refresh token no es válido. |
| Cerrar sesión | Gateway: `POST /api/logout` → Auth `POST /api/auth/logout`; directo: `POST http://localhost:8081/api/auth/logout` | Auth Service (vía API Gateway) | POST | JSON `LogoutRequest`: `{"refreshToken":"<refresh-token>"}`. | `200`: `MessageResponse` con mensaje de confirmación. |
| Consultar perfil propio | Gateway/directo: `GET /api/auth/me` | Auth Service (vía API Gateway) | GET | Encabezado `Authorization: Bearer <accessToken>`. | `200`: `UserProfileResponse` (`id`, `email`, `displayName`, `createdAt`); `401` o `404` en error. |
| Crear o actualizar nombre visible | Gateway/directo: `PUT /api/auth/me/display-name` | Auth Service (vía API Gateway) | PUT | Encabezado Bearer; JSON `UpdateDisplayNameRequest`: `{"displayName":"Jugador_01"}`. Entre 3 y 24 caracteres: letras ASCII, números y `_`. | `200`: `UserProfileResponse`; `400`, `401` o `404` en error. |
| Verificar JWT (endpoint de prueba) | Directo: `GET http://localhost:8081/api/test/protected`; no tiene ruta dedicada en Gateway. | Auth Service | GET | Encabezado `Authorization: Bearer <accessToken>`. | `200`: JSON con mensaje de validación e identificador del usuario autenticado. |
| Consultar salud de Auth | Directo: `GET http://localhost:8081/actuator/health` | Auth Service | GET | Sin parámetros. | `200`: estado de salud de Spring Actuator. |
| Consultar salud del API Gateway | Directo: `GET http://localhost:8080/actuator/health` | API Gateway | GET | Sin parámetros. | `200`: estado de salud de Spring Actuator. |
| Consultar salud de Eureka | Directo: `GET http://localhost:8761/actuator/health` | Eureka Server | GET | Sin parámetros. | `200`: estado de salud si Actuator está disponible en la configuración activa. |
| Consultar salud de Redis de Lobby | Directo: `GET http://localhost:8083/health/redis` | Lobby Service | GET | Sin parámetros; no requiere JWT. | `200`: `{"redis":"UP"}` si Redis responde o `{"redis":"DOWN"}` si no responde. |
| Crear sala | Gateway/directo: `POST /api/lobbies` | Lobby Service (vía API Gateway) | POST | Encabezado Bearer; sin parámetros ni body. | `201`: `LobbyResponse` con código de seis caracteres, creador y anfitrión. |
| Listar mapas | Gateway/directo: `GET /api/lobbies/maps` | Lobby Service (vía API Gateway) | GET | Encabezado Bearer; sin parámetros ni body. | `200`: lista de `MapResponse`. |
| Consultar sala | Gateway/directo: `GET /api/lobbies/{code}` | Lobby Service (vía API Gateway) | GET | `code` (string, path); Bearer; el usuario debe pertenecer a la sala. | `200`: `LobbyResponse`. |
| Unirse a sala | Gateway/directo: `POST /api/lobbies/{code}/join` | Lobby Service (vía API Gateway) | POST | `code` (string, path); encabezado Bearer. | `200`: `LobbyResponse` actualizada; `404` o `409` si no existe, está llena o ya inició. |
| Salir de sala | Gateway/directo: `POST /api/lobbies/{code}/leave` | Lobby Service (vía API Gateway) | POST | `code` (string, path); Bearer; el usuario debe pertenecer a la sala. | `204`: sin body. |
| Cambiar estado de preparación | Gateway/directo: `PUT /api/lobbies/{code}/ready` | Lobby Service (vía API Gateway) | PUT | `code` (string, path); Bearer; JSON `ReadyRequest`: `{"ready":true}` o `{"ready":false}`. | `200`: `LobbyResponse` actualizada. |
| Añadir bot | Gateway/directo: `POST /api/lobbies/{code}/bots` | Lobby Service (vía API Gateway) | POST | `code` (string, path); Bearer de anfitrión. | `201`: `LobbyResponse` actualizada con el bot. |
| Quitar bot | Gateway/directo: `DELETE /api/lobbies/{code}/bots/{bot_id}` | Lobby Service (vía API Gateway) | DELETE | `code` y `bot_id` (string, path); Bearer de anfitrión. | `200`: `LobbyResponse` actualizada. |
| Votar por mapa | Gateway/directo: `PUT /api/lobbies/{code}/vote` | Lobby Service (vía API Gateway) | PUT | `code` (string, path); Bearer; JSON `VoteRequest`: `{"map_id":"classic"}`. El mapa debe estar en `GET /api/lobbies/maps`. | `200`: `LobbyResponse` actualizada con los votos. |
| Expulsar participante | Gateway/directo: `DELETE /api/lobbies/{code}/players/{player_id}` | Lobby Service (vía API Gateway) | DELETE | `code` y `player_id` (string, path); Bearer de anfitrión. | `200`: `LobbyResponse` actualizada. |
| Iniciar partida | Gateway/directo: `POST /api/lobbies/{code}/start` | Lobby Service (vía API Gateway) | POST | `code` (string, path); Bearer de anfitrión; mínimo dos participantes y todos listos. | `200`: `LobbyResponse` con estado iniciado, mapa e identificador de partida. |
| Consultar definición del tablero | Gateway/directo: `GET /api/game/board` | Game Service (vía API Gateway) | GET | Encabezado `Authorization: Bearer <accessToken>`. | `200`: definición del tablero (`layout`, `cellCount`, casillas seguras, trivia, celdas y rutas). |
| Consultar estado de partida | Gateway/directo: `GET /api/game/games/{gameId}` | Game Service (vía API Gateway) | GET | `gameId` (string, path; 1–80 letras, números, `_` o `-`); Bearer; el usuario debe pertenecer a la partida. | `200`: `GameView`; `400`, `401`, `403`, `404` o `503` según error. |
| Inicializar partida desde Lobby | Interno: `POST http://game-service:8084/internal/games`; no se enruta por Gateway. | Game Service (llamado por Lobby Service) | POST | Encabezado `X-Lobby-Token`; JSON `CreateGame`: `gameId`, `lobbyId`, `mapId`, `participants` (2–4 con `playerId`, `bot` y asiento único 0–3), `creatorPlayerId`; `firstPlayerId` opcional/deprecado. | `200`: `GameView`; `400`, `403`, `409` o `503`. Los reintentos idénticos son idempotentes. |
| Diagnosticar MongoDB (opcional) | Gateway: `GET /api/game/test/mongodb` → Game `GET /test/mongodb`; directo: `GET http://localhost:8084/test/mongodb` | Game Service (vía API Gateway) | GET | Bearer. Deshabilitado salvo `game.diagnostics.enabled=true`; al ejecutarse escribe un documento de prueba. | `200`: texto de diagnóstico; `401` o `503` en error. |
| Diagnosticar Redis (opcional) | Gateway: `GET /api/game/test/redis` → Game `GET /test/redis`; directo: `GET http://localhost:8084/test/redis` | Game Service (vía API Gateway) | GET | Bearer. Deshabilitado salvo `game.diagnostics.enabled=true`; al ejecutarse escribe una clave de prueba. | `200`: texto de diagnóstico; `401` o `503` en error. |
| Consultar salud de Game | Directo: `GET http://localhost:8084/actuator/health` | Game Service | GET | Sin parámetros. | `200`: estado de salud de Spring Actuator. |
| Abrir Swagger UI de Lobby | `GET http://localhost:8083/docs` | Lobby Service | GET | Sin parámetros. | `200`: interfaz Swagger UI de FastAPI. |
| Obtener OpenAPI de Lobby | `GET http://localhost:8083/openapi.json` | Lobby Service | GET | Sin parámetros. | `200`: especificación OpenAPI JSON de Lobby. |
| Abrir Swagger UI de Auth | `GET http://localhost:8081/swagger-ui/index.html` | Auth Service | GET | Sin parámetros. | `200`: interfaz Swagger UI de Auth. |
| Obtener OpenAPI de Auth | `GET http://localhost:8081/v3/api-docs` | Auth Service | GET | Sin parámetros. | `200`: especificación OpenAPI JSON de Auth. |
| Abrir Swagger UI de Game | Directo: `GET http://localhost:8084/swagger-ui/index.html`; Gateway: `GET http://localhost:8080/api/game/swagger-ui/index.html` | Game Service (directo o vía API Gateway) | GET | Sin parámetros. | `200`: interfaz Swagger UI de Game. |
| Obtener OpenAPI público de Game | Directo: `GET http://localhost:8084/v3/api-docs/public-game`; Gateway: `GET http://localhost:8080/api/game/openapi` | Game Service (directo o vía API Gateway) | GET | Sin parámetros. | `200`: documento OpenAPI público de Game; excluye la inicialización interna de partidas. |
| Conectar al juego en tiempo real | Gateway: `ws://localhost:8080/api/game/ws`; directo: `ws://localhost:8084/ws/game` (usar `wss` con TLS). | Game Service (vía API Gateway o directo) | WebSocket (handshake HTTP GET/Upgrade) | Primer mensaje JSON requerido en 10 segundos: `{"type":"AUTH","token":"<accessToken>"}`; luego mensajes `JOIN_GAME`, `SYNC_REQUEST` y acciones. No se permiten query strings en el handshake. | `101` al establecer conexión; mensajes JSON como `AUTHENTICATED`, `GAME_STATE`, `ACTION_ACK`, `GAME_UPDATED` y errores. |

`LobbyResponse` contiene código, anfitrión, creador original, estado, versión,
cupo, participantes, votos, mapa, expulsados e identificador de partida cuando
corresponda. Las salas admiten hasta cuatro participantes, contando bots.
`GameView` devuelve el estado consistente de partida, mapa, jugadores, fichas,
turno y desafíos pendientes.

Los endpoints protegidos usan JWT de usuario en
`Authorization: Bearer <accessToken>`. `GAME_LOBBY_TOKEN`/`X-Lobby-Token` es un
secreto interno de comunicación Lobby → Game: no es un JWT y no debe compartirse
con clientes. La integración interna `POST /internal/games` no está expuesta en
el Gateway.

## Swagger y contratos

- Lobby: <http://localhost:8083/docs> y <http://localhost:8083/openapi.json>.
- Auth: <http://localhost:8081/swagger-ui/index.html> y
  <http://localhost:8081/v3/api-docs>.
- Game directo: <http://localhost:8084/swagger-ui/index.html> y
  <http://localhost:8084/v3/api-docs>.
- Game vía Gateway: <http://localhost:8080/api/game/swagger-ui/index.html> y
  <http://localhost:8080/api/game/openapi>.
- El protocolo de mensajes de Game está en
  [game-service/docs/WEBSOCKET.md](../game-service/docs/WEBSOCKET.md).
