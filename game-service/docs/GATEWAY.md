# Game a través del API Gateway

El [informe de integración](../../api-gateway/docs/GAME_INTEGRATION.md) incluye archivos, comandos, resultados reales, auditoría de bots y Swagger, decisiones de proxy y límites. Las reglas de negocio de Game no se modificaron en esta etapa.

Rutas públicas: GET `/api/game/board`, GET `/api/game/games/{gameId}`, GET `/api/game/test/mongodb` y `/api/game/test/redis` si Game habilita diagnósticos; WebSocket `/api/game/ws`→Game `/ws/game`. Todas descubren `GAME-SERVICE` en Eureka. `POST /internal/games` permanece privado para Lobby, sin ruta pública ni exposición en el documento público.

Swagger UI: `http://localhost:8080/api/game/swagger-ui/index.html`. Documento público: `http://localhost:8080/api/game/openapi`; Game lo genera en `/v3/api-docs/public-game`. Documento interno completo: `http://localhost:8084/v3/api-docs`, UI directa `/swagger-ui/index.html`.

**Auditoría de REST:** cinco operaciones de controladores, tres activas por defecto y dos diagnósticos opt-in. Las tres aparecen en OpenAPI con la configuración normal; las cinco aparecen al habilitar diagnósticos. Grupo público: dos o cuatro, excluyendo el inicio privado y reescribiendo los paths de diagnósticos. Las pruebas comparan con el registro MVC, métodos HTTP, DTOs, parámetros, cuerpos, respuestas, errores y seguridad. `BoardLayout` únicamente describe el JSON existente; no cambia su estructura.

**Bots:** `BotRunner` funciona con scheduling habilitado y utiliza el mismo motor/CAS que humanos. Se verifican captura, seguridad, coronación, liberación, efectos y trivia en `BotTest`; el probe de JAR observa sus dados, movimientos y cambios horarios por Gateway sin enviar solicitudes de ejecución. Lobby actual todavía no permite agregar bots a una sala porque su dominio de salas no está implementado. Debe enviar `participants[].bot`, asientos y creador mediante el [contrato](INTEGRATIONS.md).

**Autenticación WebSocket:** conexión pública sin Bearer en el handshake, seguida obligatoriamente de AUTH con JWT. Game rechaza acciones no autenticadas y deriva identidad del token. Tokens en query strings rechazados400; Origin validado en Gateway y Game. El Gateway conserva la instancia backend por conexión y sus propios latidos; cierre/expiración libera ambos tramos. REST sigue requiriendo JWT. Los mensajes y ejemplos completos están en [WEBSOCKET.md](WEBSOCKET.md).

Los probes emplean puertos18080/18081/18084 para no alterar servicios existentes. Las direcciones8080/8084 corresponden a la configuración conservada; el Gateway desplegado debe arrancar con el artefacto actualizado. No se modificaron Auth, Lobby, Eureka, Compose, infraestructura ni Godot; no hubo commits ni pushes. Login OAuth completo y una ejecución de Godot quedan pendientes.

## Resultado de ejecución

Gateway:12 pruebas aprobadas. Game:74 pruebas aprobadas, incluidas las de OpenAPI con diagnósticos activos. Cero fallos, errores u omisiones en ambas verificaciones finales.

Probe real Gateway→Eureka→Game→Redis/Mongo: **PASS** en `api-gateway/.build/gateway-runtime/1704a978f5d9/report.json`. Dos sockets recibieron dados/movimiento y sincronización; sin AUTH no se pudo actuar. Durante35 segundos el observador solo envió AUTH/JOIN_GAME y recibió turnos automáticos de bots, incluyendo dobles y movimientos. Auth real en la red Docker mantuvo refresh401, logout200 y el inicio OAuth302 con sus parámetros requeridos. No se completó login Google ni se usó Godot.

**¿Todos los endpoints REST están documentados? Sí:3/3 operaciones activas;5/5 al habilitar los dos diagnósticos.** El documento público excluye el inicio interno y muestra2/4 operaciones públicas según configuración. Swagger UI y assets respondieron200 por Gateway. El informe completo distingue errores corregidos del probe, controles de ruta y límites de integración.
