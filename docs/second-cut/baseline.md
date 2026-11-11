# Segundo corte: línea base y matriz inicial

No se encontró el documento de rúbrica, ni código Godot, en el árbol inspeccionado. Se usa la lista del responsable. Esta página registra la auditoría previa a los cambios; la evidencia de salida se registra por separado.

| Requisito | Estado inicial | Evidencia y dependencia |
|---|---|---|
| Hexagonal | Parcial | Motor y repositorios Game separados; DTO HTTP y Spring Data aún alcanzan el núcleo. Lobby depende de clientes y Redis concretos. Auth organiza controladores y servicios. |
| DDD | Parcial | GameState concentra invariantes; Lobby valida mutaciones en Lua; Auth conserva identidad y perfil. Falta explicitar fronteras y lenguaje. |
| EDA / Event Bus | Pendiente | Pub/Sub de Game difunde a sockets; no hay entrega recuperable entre microservicios. |
| SAGA | Pendiente | STARTING y abort sin correlación; el timeout puede dejar una partida huérfana. |
| Tokens | Implementado, requiere regresión | OAuth Google, JWT RSA, refresh en PostgreSQL; logout solo revoca refresh. |
| MFA | Sin evidencia | No se exige ni comprueba segundo factor en el flujo actual. |
| Persistencia | Implementada, requiere integración | JPA/Hibernate para Auth; MongoTemplate para catálogo; Redis/Lua para salas y juego; DTO HTTP separados donde existen. |
| Prometheus y Grafana | Pendiente | Actuator en servicios Java y salud Redis en Lobby; Compose no tiene los dos componentes. |
| Swagger | Parcial | Especificaciones por servicio; comprobar rutas y errores en ejecución. |
| Manual de usuario | Pendiente | Sin manual integral verificado. |
| Instalación | Parcial | Manual existente, actualizar conservando capítulos. |
| Arquitectura y demostración | Parcial | Documentos históricos contradictorios; faltan evidencia integral y fallos controlados. |

## Contratos congelados

- Auth público por Gateway: OAuth GET `/oauth2/authorization/google` con `client_state` y `client_port`; callback GET `/login/oauth2/code/google`; POST `/api/refresh`, `/api/logout` (reescritos a `/api/auth/refresh`, `/api/auth/logout`); GET `/api/auth/me`; PUT `/api/auth/me/display-name`. Cuerpos `refreshToken` y `displayName`, respuestas `accessToken`, `message`, o perfil `id,email,displayName,createdAt`. Callback loopback con `accessToken,refreshToken,state`. Auth directo conserva diagnóstico `GET /api/test/protected`.
- Lobby: bajo `/api/lobbies`, POST raíz (201), GET `/maps` y `/{code}`, POST `/{code}/join`, POST `/{code}/leave` (204), PUT `/{code}/ready` con `ready`, POST `/{code}/bots` (201), DELETE `/{code}/bots/{bot_id}`, PUT `/{code}/vote` con `map_id`, DELETE `/{code}/players/{player_id}`, POST `/{code}/start` (200). Bearer, JSON snake_case de LobbyResponse, errores `detail`, códigos de dominio 400/403/404/409, validación 422, inicio 502/503 en fallos de Game. Start exige anfitrión, al menos 2 participantes y listos excepto anfitrión/bots. Un start concurrente devuelve el conflicto existente. GET directo de salud `/health/redis`.
- Game: GET `/api/game/board` y `/api/game/games/{gameId}` con Bearer; snapshot solo para miembros. Diagnósticos optativos GET `/api/game/test/{mongodb|redis}` reescritos a `/test/...`. OpenAPI público en GET `/api/game/openapi` y UI en `/api/game/swagger-ui/index.html`. Privado POST `/internal/games`, protegido por `X-Lobby-Token`, con `gameId,lobbyId,mapId,creatorPlayerId,participants[{playerId,bot,seat}]` y `firstPlayerId` opcional; 200 GameView, errores `code,message`. No ruta Gateway para la creación privada.
- Socket: GET upgrade público `/api/game/ws` a `/ws/game`, sin query; AUTH con token, JOIN_GAME / SYNC_REQUEST con gameId; ROLL_DICE, MOVE_REQUEST, END_TURN, TRIVIA_ANSWER, CARD_TARGET con `requestId,expectedVersion` y campos de Action. Respuestas AUTHENTICATED, GAME_STATE, ACTION_ACK, GAME_UPDATED, ERROR. Ver [protocolo](../../game-service/docs/WEBSOCKET.md). Origen, tiempo, tamaños, identidad y membresía siguen en los adaptadores.
- Integración Auth → Lobby: consulta GET /api/auth/me con Bearer original; fallback al nombre genérico. Integración Lobby → Game: mismas rutas, JSON y autorización interna. Lobby conserva orden de participantes, mapa con mayor votación (sorteo en empate), creador presente o anfitrión de reemplazo y asientos [0,2], [0,1,2] o [0,1,2,3].

Fuentes: controladores, DTO, router FastAPI, configuración Gateway y manejadores WebSocket del árbol previo. Pruebas de caracterización existentes: GatewayRoutingTest, GameWebSocketProxyTest, WebSocketTest, OpenApiDocumentationTest, AuthHttpIntegrationTest y test_lobby_regressions.py. Los nuevos contratos internos se documentan aparte.
