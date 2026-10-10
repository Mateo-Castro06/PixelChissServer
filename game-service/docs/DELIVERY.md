# Informe de entrega — Game Service

Fecha: 10 de octubre de 2026. Todo el código, documentación y artefactos de compilación se creó/modificó dentro de `game-service/`. No se hicieron commits ni pushes. Los archivos preexistentes `Terminal` y `Tool` fuera de Game no se tocaron.

Actualizado tras corregir turnos y refactorizar: **71 pruebas aprobadas**. El creador original inicia y la rotación es horaria por asientos explícitos. Cambios de esta etapa y verificación en [CLEAN_CODE.md](CLEAN_CODE.md). El registro real en Eureka, la imagen Docker y la corrección de lectura de efectos del cierre previo conservan su evidencia histórica en [VALIDATION.md](VALIDATION.md).

## 1. Historias implementadas

| Historia | Implementación en Game |
|---|---|
| US-005 | Bot estándar, decisiones ponderadas, dos dados, captura/seguridad/liberación/efectos/trivia; participantes recibidos de Lobby |
| US-006 | Turno único, dos dados de servidor, consumos por índice, dobles, timeout y efectos |
| US-007 | Trivia temática MongoDB, opciones, respuesta, timer, buffs/debuffs y eventos |
| US-008 / US-009 | Cuatro cartas persistentes, selección inmediata de objetivo, efectos temporales y precedencia |
| US-010 / US-011 | Movimiento calculado, validaciones, ocupación, liberación por dobles y captura |
| US-012 / US-018 | Contrato de inicialización autorizada, colores por asiento horario explícito, mapa y creador original recibidos; no administración de Lobby |
| US-013 | Doce seguros, capacidad2, protección normal y de carta, efectos a seguro |
| US-014 | Meta exacta, coronados fuera de movimientos normales |
| US-015 | Victoria de cuatro coronaciones, ganador único y cierre inmediato |
| US-019 | Leases por sesión, gracia, reconexión, retiro idempotente, fichas a casa y turnos desbloqueados |

## 2. Alcance pendiente y motivos

Las funciones propias de Game solicitadas están implementadas y probadas. La integración extremo a extremo desde Godot **a través del Gateway actual y del inicio real de Lobby** está pendiente: faltan rutas/proxy WebSocket y Lobby solo contiene endpoints de ejemplo. Los cambios externos estaban prohibidos y se describen en [INTEGRATIONS.md](INTEGRATIONS.md).

No se probó un login OAuth real ni un token de sesión obtenido del contenedor Auth; sí firma/claims compatibles, algoritmos RSA y tokens emitidos por su clase JwtService compilada con claves de prueba. Eureka Client y configuración existente se conservan; las pruebas Spring deshabilitan el registro. Los probes del cierre previo registraron JAR y Docker UP; el nuevo JAR refactorizado también registróUP y verificó una vuelta horaria, bots y WebSocket directos. La imagen Docker corregida del cierre anterior se construyó y probó; no se reconstruyó tras esta refactorización. El nuevo JAR ejecutable sí se produjo.

Las distribuciones/capacidades de tablero y las reglas no especificadas son decisiones explícitas, no reglas descubiertas en el repositorio. Redis conserva el agregado, pero su durabilidad ante caída depende de AOF/RDB externos. Pub/Sub exige sincronización tras pérdida de conexión. No se implementó historial de partidas en otra base ni retención automática.

## 3. Archivos creados y modificados

Modificados:

```text
.gitignore
pom.xml
src/main/resources/application.yaml
src/main/java/com/pixelchiss/gameservice/config/WebSocketConfig.java
src/main/java/com/pixelchiss/gameservice/controller/MongoTestController.java
src/main/java/com/pixelchiss/gameservice/controller/RedisTestController.java
src/main/java/com/pixelchiss/gameservice/websocket/GameWebSocketHandler.java
src/test/java/com/pixelchiss/gameservice/GameServiceApplicationTests.java
```

Creados, bajo `src/main/java/com/pixelchiss/gameservice/`:

```text
bot/BotPolicy.java
bot/BotRunner.java
config/GameConfig.java
config/GameEventsConfig.java
config/JwtConfig.java
config/OpenApiConfig.java
config/SecurityConfig.java
controller/GameController.java
controller/GameErrorHandler.java
controller/LobbyIntegrationController.java
domain/Board.java
domain/CardDefinition.java
domain/GameState.java
domain/QuestionDefinition.java (ubicación restaurada con autorización al preparar la publicación)
dto/Action.java
dto/CreateGame.java
dto/GameView.java
engine/CardRules.java
engine/DiceRules.java
engine/DiceSource.java
engine/GameException.java
engine/RuleEngine.java
engine/SecureDiceSource.java
engine/TriviaRules.java
engine/TurnManager.java
engine/TurnOrder.java
persistence/CatalogSeeder.java
persistence/GameCatalog.java
persistence/GameRepository.java
persistence/MongoGameCatalog.java
persistence/RedisGameRepository.java
service/GameScheduler.java
service/GameService.java
service/PresenceService.java
```

Creados, bajo `src/test/java/com/pixelchiss/gameservice/`:

```text
BoardTest.java
BotTest.java
CardsTriviaTest.java
DisconnectTest.java
EngineFixture.java
GameSchedulerTest.java
GameServiceTest.java
GameStateSerializationTest.java
InfrastructureIntegrationTest.java
JwtTest.java
MemoryGames.java
RuleEngineTest.java
SeedCatalog.java
ServiceFixture.java
TurnOrderTest.java
WebSocketTest.java
```

Otros nuevos:

```text
.dockerignore
Dockerfile
README.md
docs/BOARD.md
docs/ENGINE.md
docs/INTEGRATIONS.md
docs/WEBSOCKET.md
docs/DELIVERY.md
docs/VALIDATION.md
docs/CLEAN_CODE.md
tools/AuthIssuerProbe.java
tools/validate_runtime.py
src/main/resources/seed/catalog.json
src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker
```

`GameServiceApplication.java`, `RedisConfig.java` y wrapper no se modificaron. Se generaron `.build/m2`, reports y JAR en `target/`, ignorados por Git. No se cambiaron versiones existentes de Boot/Cloud/Java.

## 4. Tablero

**64 comunes +16 salidas +16 posiciones finales =96**. Seguros:0,5,10,16,21,26,32,37,42,48,53,58. Salidas64–79, cuatro por color; coronas83,87,91,95. Casa fuera del tablero, posiciónnull. Cada ficha tiene progreso relativo y posición física. Detalles completos y fórmulas en [BOARD.md](BOARD.md).

## 5. Dados, turnos y movimiento

El creador original enviado por Lobby inicia siempre. Los turnos avanzan por asientos explícitos en sentido horario, independientemente del orden del array o de conexiones, y conservan posiciones al retirar/reconectar. Dobles repiten asiento y luego se sigue su sucesor. El contrato actualizado requiere `creatorPlayerId` y `seat`; [INTEGRATIONS.md](INTEGRATIONS.md) detalla validaciones y migración.

Dos dados aleatorios de servidor; índice0/1, valor autorizado, consumos independientes. Un cliente puede proponer destino pero el motor calcula el único destino legal. Valida origen, propiedad, pertenencia, turno, plazo, ocupación y meta. Libera una ficha por dado con dobles. Permite dos movimientos sobre una o distintas fichas; imposible/agotado avanza. Timeout y renuncia no premian dobles. La cuarta corona cierra en la misma actualización.

## 6. Bots

Una dificultad, sin búsqueda avanzada. Puntuación por corona/captura/seguridad/liberación/progreso y penalización de exposición, elección ponderada entre alternativas razonables. Una acción por tick y reevaluación tras cada confirmación. Cartas y trivia usan la misma validación humana. [ENGINE.md](ENGINE.md) documenta puntuaciones y probabilidad configurable de trivia.

## 7. Cartas y trivia

Cuatro documentos: `capture-next`, `move-safe`, `dice-limit`, `choose-dice`. Las dos primeras verifican objetivo dentro del recorrido y capacidad. Límite3 dura exactamente tres siguientes turnos propios; elección es un solo lanzamiento del próximo turno. El límite tiene precedencia sobre la elección. No hay inventario; selección pendiente tiene deadline. Ocho preguntas en dos temáticas, respuesta oculta hasta resolución y semilla idempotente.

## 8. WebSocket

Endpoint `/ws/game`; primer mensaje AUTH, luego JOIN_GAME/SYNC_REQUEST. Mutaciones: ROLL_DICE, MOVE_REQUEST, END_TURN, TRIVIA_ANSWER, CARD_TARGET. Todas llevan gameId, requestId y expectedVersion; identidad desde JWT. ACK, lote GAME_UPDATED con eventos/snapshot, errores tipados y sincronización por versión. [WEBSOCKET.md](WEBSOCKET.md) contiene payloads y flujo Godot.

## 9. REST y Swagger

Inicio interno `POST /internal/games` con credencial de Lobby; consultas `GET /api/game/games/{gameId}` y `GET /api/game/board` con Bearer. Diagnósticos antiguos opcionales y protegidos. Swagger `http://localhost:8084/swagger-ui/index.html`; especificación `/v3/api-docs`. Se comprobaron UI, esquemas de autenticación, rutas y acceso REST.

## 10. JWT y Eureka

Misma clave RSA pública de Auth, RS256/384/512, firma y claims verificados, subject como identidad y cierre WebSocket al vencer token. Sin login nuevo, tokens en URLs o claves privadas. Se conserva nombre `game-service`, puerto8084 y Eureka `${EUREKA_URL}`. No se modificaron Auth/Eureka/Gateway.

## 11. MongoDB y Redis

MongoDB: `game_cards`, `game_questions`, en la base existente. Upsert `$setOnInsert` por ID. Redis: agregado único, recibos de últimas256 acciones, índice activo, presencia y Pub/Sub. Confirmación atómica Lua con CAS y publicación. Las acciones inválidas nunca se persisten; conflictos requieren sincronizar. No hay estado autoritativo duplicado en MongoDB.

## 12. Compilar y ejecutar

Comandos generales y variables en [README.md](../README.md). Comando final real en este workspace:

```bash
cd game-service
mvn -Dmaven.repo.local=.build/m2 -o -Dgame.integration=true verify
```

Resultado **BUILD SUCCESS**. Artefacto `target/game-service-0.0.1-SNAPSHOT.jar`, ejecutable con Java21. El Dockerfile consume ese JAR; en el cierre previo se construyó y probó la imagen `pixelchiss-game:validation-fixed-20261010`. Comandos reales en [VALIDATION.md](VALIDATION.md).

## 13. Resultados de pruebas ejecutadas

**71 pruebas, 0 fallos, 0 errores, 0 omitidas**, en la verificación final con integración habilitada:

| Suite | Pruebas |
|---|---:|
| BoardTest | 2 |
| RuleEngineTest | 12 |
| CardsTriviaTest | 11 |
| BotTest | 6 |
| GameServiceTest | 4 |
| GameStateSerializationTest | 4 |
| WebSocketTest | 5 |
| DisconnectTest | 1 |
| JwtTest | 2 |
| GameServiceApplicationTests | 3 |
| InfrastructureIntegrationTest | 6 |
| TurnOrderTest | 12 |
| GameSchedulerTest | 3 |
| Total | **71** |

Las pruebas reales arrancan el servidor HTTP/WebSocket en puerto aleatorio, validan JWT, inicialización privada, membresía, publicación a dos sockets, dados, movimiento y duplicado; comprueban CAS Redis y carga repetida MongoDB. Las pruebas de motor cubren los demás escenarios obligatorios, incluida pérdida del catálogo durante timeout para evitar bloquear un turno.

Se compilaron y ejecutaron pruebas por bloques. La ejecución inicial no pudo resolver Surefire en modo offline; se descargó dentro de Game. Se corrigió el mock GridFS de la prueba de contexto. El primer empaquetado offline encontró dependencias JAR faltantes; se descargaron y la verificación final completó todas sus etapas. Esos fallos iniciales no se presentan como pruebas exitosas.

Reports XML y texto: `target/surefire-reports/`. La suite de integración es opt-in en máquinas sin infraestructura; en esta entrega se ejecutó realmente y no se omitió.

## 14. Supuestos de reglas

Máximo cuatro colores/jugadores; salida individual asignada a cada ficha; casa externa; capacidad2 en seguros y1 ordinaria; coronados excluidos de ocupación; no barreras intermedias; liberación consume un dado sin avance; dobles premian salvo timeout/renuncia; cada turno extra cuenta para efectos; cartas de captura/refugio tienen el alcance documentado; debuff de fallo se aplica a quien respondió; abandonar con menos de dos no declara victoria. Geometría v1 igual en ambas temáticas. Todos están detallados en [BOARD.md](BOARD.md) y [ENGINE.md](ENGINE.md).

## 15. Cambios externos necesarios, no aplicados

Lobby: implementar inicio autorizado y notificación gameId. Gateway: ruta HTTP, proxy con soporte WebSocket y handshake compatible con AUTH en mensaje. Despliegue: incorporar Game y su clave pública/variables a Compose si se quiere contenerizar. Redis: verificar persistencia si se exige durabilidad estricta. Archivos y motivos precisos en [INTEGRATIONS.md](INTEGRATIONS.md).

No se implementó ni configuró Log Aggregator, puerto5000, Player Service o cliente Godot.

## 16. Propuesta de commits, sin ejecutarlos

1. `feat(game): modelar tablero v1 y cuatro fichas por participante` — dominio, DTOs de inicio y pruebas de tablero.
2. `feat(game): validar dados, turnos, movimientos y capturas` — motor, azar encapsulado y pruebas.
3. `feat(game): sincronizar acciones y eventos por WebSocket` — protocolo, snapshots y recibos/CAS requeridos por el flujo.
4. `feat(game): finalizar partidas y gestionar desconexiones` — victoria, presencia y retiro.
5. `feat(game): persistir cartas y resolver trivia` — catálogo, semillas y efectos.
6. `feat(game): ejecutar bots con decisiones ponderadas` — política, runner y pruebas.
7. `feat(game): integrar JWT, Redis y contrato privado de Lobby` — seguridad, REST, configuración e integración.
8. `docs(game): documentar contratos, reglas y verificación` — OpenAPI, documentación, Dockerfile e informe final.

La propuesta describe agrupaciones lógicas; las dependencias de compilación entre bloques deben incluirse juntas al preparar commits reales. Ninguna operación Git de escritura fue realizada.
