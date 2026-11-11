# Enrutamiento público e interno

Godot usa el API Gateway. Las rutas se conservan según su contrato, aun cuando las rutas internas tengan otro prefijo.

- Auth: OAuth y perfil mantienen ruta; `POST /api/refresh` y `POST /api/logout` se reescriben a `/api/auth/refresh` y `/api/auth/logout`.
- Lobby: `/api/lobbies/**` se encamina sin quitar el prefijo.
- Game: consultas `/api/game/board` y `/api/game/games/{gameId}` mantienen ruta. `/api/game/ws` es el handshake del puente hacia `/ws/game`; Game exige `AUTH` como primer mensaje.
- Inicio privado: `POST /internal/games` es exclusivo de Lobby y Game. No se publica en Gateway ni en su OpenAPI de Game.
- Métricas: `/actuator/prometheus` y `/internal/metrics` son endpoints de scraping con clave separada. No pertenecen a la API del cliente.

Ver [tabla de métodos, DTO y códigos](../MANUAL_INSTALACION.md#operaciones-rest), [WebSocket](../../game-service/docs/WEBSOCKET.md) y [contrato asíncrono](../second-cut/start-saga.md).
