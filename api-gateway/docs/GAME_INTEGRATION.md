# Integración de Game con Gateway — 10 de octubre de 2026

## Cambios y enrutamiento

Se conservó Gateway Server WebMVC5.0.3, aplicación `api-gateway`, puerto8080, Eureka y todas las rutas/reescrituras de Auth. El código instalado de `HandlerFunctions` ofrece HTTP/HTTPS/forward, sin proxy ws/wss; la [incidencia oficial3442](https://github.com/spring-cloud/spring-cloud-gateway/issues/3442) refleja esa limitación. Por ello se agregó un puente WebSocket específico para Game, sin migrar a WebFlux ni implementar reglas de juego en el Gateway.

Rutas exactas, todas GET salvo el inicio privado:

| Ruta pública | Destino |
|---|---|
| `/api/game/board` | `lb://GAME-SERVICE/api/game/board` |
| `/api/game/games/{gameId}` | `lb://GAME-SERVICE/api/game/games/{gameId}` |
| `/api/game/test/mongodb` | `lb://GAME-SERVICE/test/mongodb`, opt-in |
| `/api/game/test/redis` | `lb://GAME-SERVICE/test/redis`, opt-in |
| `/api/game/ws` + Upgrade | instancia Eureka `GAME-SERVICE`, `/ws/game` |
| `/api/game/openapi` | `lb://GAME-SERVICE/v3/api-docs/public-game` |
| `/api/game/swagger-ui/index.html` | HTML del Gateway, con assets proxy `/swagger-ui/**` |

El mecanismo de rutas REST usa el YAML WebMVC existente y `RewritePath` donde la ruta interna difiere. Consultas de partida/tablero conservan el path completo, sin StripPrefix duplicado. No se creó `/api/game/internal/games`. `POST /internal/games` permanece exclusivamente entre Lobby y Game, con `X-Lobby-Token`.

Las seis rutas de Auth mantienen métodos y filtros: POST `/api/refresh`→`/api/auth/refresh`; POST `/api/logout`→`/api/auth/logout`; GET `/oauth2/authorization/**` y `/login/oauth2/code/**` sin reescritura; GET `/api/auth/me`; PUT `/api/auth/me/display-name`.

## Eureka y WebSocket

HTTP usa `lb://GAME-SERVICE`. `GameWebSocketProxy` usa el `LoadBalancerClient` existente para elegir `GAME-SERVICE`, abre una conexión JDK a `/ws/game` y hace el upgrade público solo si el backend acepta. Sin instancia devuelve503; rechazo de origen se conserva como403; error de conexión502. La instancia se fija durante la sesión. No se envían acciones a otra instancia ni se reintentan mutaciones del juego.

El handshake GET exacto `/api/game/ws` se permite sin Bearer para Godot. Game mantiene AUTH obligatorio como primer mensaje, plazo10 segundos y validación JWT. No se registran tokens, no se envían por URL y no se interpreta `playerId` cliente como identidad. Cualquier query string se rechaza400 antes de descubrir/abrir el backend. Origin se valida en ambos saltos. Las consultas REST siguen verificando JWT en Gateway y Game y pertenencia en Game.

`GameSocketBridge` transmite textos/binarios, agrega fragmentos del backend y propaga cierres. Límites16KiB de entrada,64KiB de respuesta/buffer, conexión5 segundos y envío3 segundos. El cliente JDK responde automáticamente a pings ([Java21 WebSocket.Listener](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/WebSocket.Listener.html)); el Gateway mantiene sus propios pings al cliente y cierra ambos saltos si no recibe pong en30 segundos. Así una conexión upstream no mantiene indefinidamente presente a un cliente perdido.

Swagger público es una excepción GET solo para documento/assets, distinta del permiso de handshake; ninguna operación del juego queda anónima. El HTML usa un forward interno específico autorizado a `/game-swagger.html`; el acceso HTTP directo a ese archivo sigue401. El documento público excluye inicio privado. Los contratos JSON del juego y el motor no se modificaron.

## Auditoría de Swagger/OpenAPI

**¿Todos los endpoints REST de Game están correctamente documentados? Sí, verificado:3/3 activos y5/5 con diagnósticos.** Se encontraron **5 operaciones REST de controladores Game**:3 activas por defecto y2 diagnósticos condicionados. La auditoría compara el registro MVC real con los paths/métodos del documento, no solo la existencia de Swagger.

| Operación interna | Por defecto | Documento completo | Documento público Gateway |
|---|---|---|---|
| GET `/api/game/board` | Activa | Sí | Mismo path |
| GET `/api/game/games/{gameId}` | Activa | Sí | Mismo path |
| POST `/internal/games` | Activa | Sí, LobbyToken | Excluida |
| GET `/test/mongodb` | Solo diagnostics=true | Sí cuando habilitada | `/api/game/test/mongodb` |
| GET `/test/redis` | Solo diagnostics=true | Sí cuando habilitada | `/api/game/test/redis` |

Por defecto: **3/3** en `/v3/api-docs`;2 públicas en `/v3/api-docs/public-game`. Con diagnósticos: **5/5** completas y4 públicas. No aparecen rutas inexistentes de cartas, dados o bots; esas acciones son WebSocket. El framework Actuator y los endpoints/recursos de documentación se distinguen de las cinco operaciones REST de controladores de Game; no se inventan operaciones de juego para ellos. Gateway mantiene su propio `/actuator/health`.

Corregidos: esquema tipado `BoardLayout` para la respuesta existente de tablero (sin cambiar JSON), parámetros de gameId y credencial interna, cuerpo CreateGame, respuestas200 y errores relevantes con esquema `Error`, requisito Bearer de consultas/diagnósticos y LobbyToken del inicio. Nuevo grupo público con paths reescritos y `x-internal-path`. El servidor OpenAPI `/` evita `/api/game/api/game/...`.

Swagger UI Gateway: `http://localhost:8080/api/game/swagger-ui/index.html`. Especificación pública: `http://localhost:8080/api/game/openapi`. Directos Game: `http://localhost:8084/swagger-ui/index.html`, `/v3/api-docs` y `/v3/api-docs/public-game`. No se publica la especificación interna completa por Gateway.

## Bots y orden de turnos

Lobby actual solo tiene `/`, `/hello/{name}` y `/health/redis`: **no implementa todavía salas ni añadir bots**. No se modificó. El contrato de inicio de Game sí recibe `participants[].bot`, `seat` y `creatorPlayerId`; Game los valida y asigna cuatro fichas por participante.

`BotRunner` se ejecuta automáticamente cada500ms, lee estado activo de Redis, consulta `BotPolicy` y llama `GameService.botAction`. Las acciones pasan por `RuleEngine`/`TriviaRules` y la misma confirmación CAS que humanos. No existe ni se añadió endpoint para disparar bots. Dados del servidor, decisiones por corona/captura/seguro/exposición/liberación/avance, efectos y trivia usan el motor existente. La política se reevalúa tras confirmar cada dado; no fue reimplementada.

Las pruebas `BotTest` ejecutadas cubren captura+seguridad con ambos dados, corona, liberación de distintas fichas, ausencia de movimientos y rechazo fuera de turno, cartas/trivia y elección de dados respetando efectos. `TurnOrderTest` cubre creador,2/3/4 jugadores, bots, dobles, retirada/reconexión, asientos dispersos y arrays reordenados. El creador inicia y la rotación por asiento es horaria; no depende del orden de conexión.

**¿Los bots ejecutan automáticamente sus turnos? Sí, verificado mediante prueba ejecutada de Game a través del Gateway.** El reporte `1704a978f5d9/report.json` observa35 segundos de conexión: creador900001 primero, bots en asientos1 y3, dados de servidor, movimientos publicados, retorno al humano y turnos adicionales. El cliente únicamente envió AUTH y JOIN_GAME. Se conservaron los asientos horarios, sin invocar acciones manuales para bots. Esto prueba el comportamiento de Game con un inicio interno autorizado que simula a Lobby; no prueba añadir bots en el Lobby inexistente.

En una partida adicional de cuatro humanos, con payload reordenado, se observó `900003→900004→900001→900002→900003` mediante cuatro expiraciones, iniciando el creador de asiento2.

## Pruebas, errores y límites

Comandos desde cada servicio:

```bash
# api-gateway
mvn -Dmaven.repo.local=.build/m2 -o verify
python3 tools/validate_game_gateway.py
# game-service
mvn -Dmaven.repo.local=.build/m2 -o -Dgame.integration=true verify
```

Gateway: **12 pruebas,0 fallos,0 errores,0 omitidas; BUILD SUCCESS**. Game: **74 pruebas,0 fallos,0 errores,0 omitidas; BUILD SUCCESS**. JARs producidos en sus respectivos `target/`. Game terminó a las15:00:26 de Bogotá; Gateway a las14:56:57. Gateway incluye cuatro pruebas HTTP con backend controlado que comprueban las seis rutas de Auth, rewrites y JWT; siete del puente/handshake y la prueba de contexto original. Game incluye tres pruebas nuevas de auditoría OpenAPI, con MongoDB/Redis reales. Logs finales: `api-gateway/.build/gateway-verify-all.log`, `game-service/.build/gateway-game-verify-final.log`.

Fallos encontrados y corregidos durante la validación:

1. Swagger reenviado internamente respondía401: se autorizó exclusivamente el forward al HTML, conservando401 del acceso directo. Primer log `gateway-tests.log`; pruebas repetidas exitosamente.
2. La nueva auditoría inyectó un mapping ambiguo (MVC y Actuator): se seleccionó el bean MVC explícitamente, sin modificar producción ni excluir pruebas. Primer log Game `gateway-game-verify.log`; luego9 pruebas de integración/documentación y las74 finales exitosas.
3. El probe Python esperaba la excepción de una versión distinta de `websockets`; se adaptó a ambas formas. El rechazo HTTP400 real era correcto. Primer reporte `57626b829981/report.json`.
4. Gateway lanzado en host no resolvía el hostname Docker de Auth anunciado en Eureka; el despacho de error terminó mostrando401. Se observó UnknownHostException en el log, no se tomó ese401 como evidencia de integración exitosa. La comprobación de Auth se trasladó a un contenedor Gateway nuevo en la red existente, sin cambiar DNS global, Eureka, Compose ni el Auth desplegado. Reporte inicial `93f3e359fa9a/report.json`.

5. El probe OAuth omitía `client_state`/`client_port`, obligatorios por `ClientLoginParamsFilter`: Auth devolvió400 correctamente. Se actualizó el probe para comprobar400 sin parámetros y302 con parámetros válidos. No se modificó Auth. Reporte `fdb47e11fbe0/report.json`.

### Resultado operativo final

Comando real: `python3 tools/validate_game_gateway.py > .build/gateway-runtime-complete.log 2>&1` desde Gateway. Resultado **PASS**,16 comprobaciones en `api-gateway/.build/gateway-runtime/1704a978f5d9/report.json`; se conservaron 4 partidas nuevas en el prefijo aislado y se detuvieron solo dos procesos JAR y un contenedor Gateway de prueba. Los contenedores de prueba detenidos se retuvieron; no se borraron imágenes ni volúmenes.

| Verificación final ejecutada | Resultado |
|---|---|
| Eureka | Instancia Game18084 UP observada, consumida por el Gateway |
| REST tablero y snapshot participante |200 a través del Gateway |
| REST sin JWT/inválido, ajeno y partida ausente |401,403,404, respectivamente |
| Prefijo duplicado / inicio interno público |404 con JWT; no se publica inicio |
| Diagnósticos deshabilitados |401 sin JWT y404 con JWT, sin ejecutar escrituras |
| OpenAPI / UI / CSS y JS |200; documento público2 operaciones e interno3 |
| WebSocket sin Bearer | Upgrade correcto; acción sin AUTH rechazada UNAUTHENTICATED |
| AUTH inválido, query y Origin no permitido | INVALID_TOKEN, handshake400 y403 |
| Dos sockets | Dados, movimiento, ACK duplicado y sincronización versión24 |
| Bots | Datos y movimientos automáticos recibidos;35 segundos, versión25, orden horario y dobles |
| Cuatro participantes | Vuelta C→D→A→B→C por timers |
| Auth real por Eureka/red Docker | refresh401 con token inexistente, logout200, OAuth400 sin parámetros y302 con parámetros válidos; perfil anónimo401 |

La clase compilada Auth JwtService emitió los tokens aislados que validaron Gateway y Game. La comprobación contra Auth desplegado usa su clave pública real y la misma red Docker para resolver sus nombres anunciados. No se leyó su clave privada. El puerto18081 pertenece a un nuevo contenedor Gateway con el JAR actual montado de solo lectura; el8080 existente no fue reiniciado.

Token OAuth real de usuario, login completo en Google, perfil autenticado contra el Auth desplegado y cliente Godot real quedan pendientes. Se usan tokens emitidos por la clase Auth JwtService compilada con una clave aislada, sin leer/modificar su clave privada. Lobby aún debe implementar su contrato. No se declara probado el ciclo completo Lobby→Godot.

El despliegue existente8080 no fue sustituido por los probes; estos usan18080/18081 y Game18084. Las URLs8080/8084 son las configuraciones conservadas para el nuevo artefacto. Debe arrancarse/reconstruirse el Gateway con el JAR nuevo por el procedimiento habitual de despliegue; no hace falta editar Compose para las rutas. No se hicieron commits ni pushes.

## Archivos de esta tarea

Gateway modificados: `.gitignore`, `pom.xml`, `src/main/resources/application.yaml`, `security/SecurityConfig.java`, prueba `ApiGatewayApplicationTests.java`.

Gateway nuevos: `websocket/GameWebSocketConfig.java`, `GameWebSocketProxy.java`, `GameSocketBridge.java`; `static/game-swagger.html`; pruebas `GatewayTestKeys.java`, `GatewayRoutingTest.java`, `websocket/GameSocketBridgeTest.java`, `GameWebSocketProxyTest.java` y configuración Mockito; `tools/validate_game_gateway.py`; este informe y `README.md`.

Game modificados exclusivamente por integración/documentación: `config/OpenApiConfig.java`, controladores `GameController`, `LobbyIntegrationController`, `MongoTestController`, `RedisTestController`; prueba `InfrastructureIntegrationTest`; documentación README/INTEGRATIONS/WEBSOCKET y nuevo informe. Nuevos `dto/BoardLayout.java` (esquema) y prueba `OpenApiDocumentationTest.java`. No se modificaron motor, bots, turnos, dominio autoritativo, repositorios ni seguridad de Game.

Artefactos/logs/cache dentro de `.build/` y `target/` de los dos servicios. Se guardó referencia de cambios en `api-gateway/.build/integration-baseline.json`. Ningún archivo fuera de `api-gateway/` y `game-service/` fue modificado. Estas operaciones no tocaron los elementos preexistentes `Terminal`, `Tool` ni revirtieron trabajo anterior. No se borró estado persistente previo.

### Inventario exacto frente al inicio de esta tarea

14 archivos modificados y15 nuevos; no incluye modificaciones heredadas de la implementación/refactorización previa.

Modificados:

```text
api-gateway/.gitignore
api-gateway/pom.xml
api-gateway/src/main/java/com/pixelchiss/gateway/security/SecurityConfig.java
api-gateway/src/main/resources/application.yaml
api-gateway/src/test/java/com/pixelchiss/gateway/ApiGatewayApplicationTests.java
game-service/README.md
game-service/docs/INTEGRATIONS.md
game-service/docs/WEBSOCKET.md
game-service/src/main/java/com/pixelchiss/gameservice/config/OpenApiConfig.java
game-service/src/main/java/com/pixelchiss/gameservice/controller/GameController.java
game-service/src/main/java/com/pixelchiss/gameservice/controller/LobbyIntegrationController.java
game-service/src/main/java/com/pixelchiss/gameservice/controller/MongoTestController.java
game-service/src/main/java/com/pixelchiss/gameservice/controller/RedisTestController.java
game-service/src/test/java/com/pixelchiss/gameservice/InfrastructureIntegrationTest.java
```

Nuevos:

```text
api-gateway/README.md
api-gateway/docs/GAME_INTEGRATION.md
api-gateway/src/main/java/com/pixelchiss/gateway/websocket/GameSocketBridge.java
api-gateway/src/main/java/com/pixelchiss/gateway/websocket/GameWebSocketConfig.java
api-gateway/src/main/java/com/pixelchiss/gateway/websocket/GameWebSocketProxy.java
api-gateway/src/main/resources/static/game-swagger.html
api-gateway/src/test/java/com/pixelchiss/gateway/GatewayRoutingTest.java
api-gateway/src/test/java/com/pixelchiss/gateway/GatewayTestKeys.java
api-gateway/src/test/java/com/pixelchiss/gateway/websocket/GameSocketBridgeTest.java
api-gateway/src/test/java/com/pixelchiss/gateway/websocket/GameWebSocketProxyTest.java
api-gateway/src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker
api-gateway/tools/validate_game_gateway.py
game-service/docs/GATEWAY.md
game-service/src/main/java/com/pixelchiss/gameservice/dto/BoardLayout.java
game-service/src/test/java/com/pixelchiss/gameservice/OpenApiDocumentationTest.java
```
