# Corrección de turnos y refactorización — 10 de octubre de 2026

## Resultado y alcance

El creador **original** de la sala siempre inicia cada nueva partida. `creatorPlayerId` es obligatorio, debe identificar un participante y solo se recibe por `POST /internal/games`, protegido por la credencial de servicio de Lobby. No se sortea ni se deduce del primer elemento de una lista. Los siguientes turnos siguen asientos físicos explícitos en sentido horario, incluyendo bots. Esta regla sustituye el antiguo `firstPlayerId` como elección del iniciador.

No se reconstruyó Game ni se cambiaron sus demás reglas. Se conserva el tablero v1 de96 casillas, sus12 seguros, cuatro fichas, dos dados, liberación, captura, coronación exacta, victoria, cartas/trivia, bots, plazos, retiro, JWT, concurrencia, deduplicación, Redis/Mongo y eventos. Las pruebas previas se conservaron y ampliaron.

## Contrato obligatorio de Lobby

```json
{
  "gameId": "game-123",
  "lobbyId": "room-123",
  "mapId": "classic",
  "creatorPlayerId": "C",
  "participants": [
    {"playerId": "D", "bot": true, "seat": 3},
    {"playerId": "B", "bot": false, "seat": 1},
    {"playerId": "C", "bot": false, "seat": 2},
    {"playerId": "A", "bot": false, "seat": 0}
  ]
}
```

Header `X-Lobby-Token`, al menos32 caracteres configurados en `GAME_LOBBY_TOKEN`. IDs humanos iguales al `sub` de Auth. Lobby debe conservar el creador original aunque cambie el anfitrión operativo; Game valida pertenencia, pero no puede verificar el historial de una sala en el Lobby actual, que todavía solo tiene endpoints de ejemplo. La confianza viene de la credencial interna de servicio, no de Godot.

Asientos: `0=RED → 1=BLUE → 2=GREEN → 3=YELLOW → 0`, representando posiciones físicas horarias. Cada participante, humano o bot, debe tener un asiento único; se admiten2–4 participantes y huecos. El array puede llegar desordenado. La secuencia del ejemplo es `C→D→A→B→C`. Game asigna fichas y colores por asiento y lo mantiene; no renumera al retirar/reconectar.

Errores de inicio: `MISSING_CREATOR` si falta/vacío, `INVALID_CREATOR` si ajeno, `INVALID_SEATS` si asiento ausente/duplicado/fuera del0–3 en el motor; Bean Validation puede devolver `INVALID_REQUEST` para los asientos inválidos en HTTP. No se crea estado rechazado. `firstPlayerId` permanece opcional y deprecado: si se envía debe igualar al creador; conflicto `INVALID_FIRST_PLAYER`. Payloads antiguos sin creador explícito/asientos deben actualizarse. No se aceptan como fallback porque violaría la regla obligatoria.

El cambio se limita al contrato privado de inicio y a un campo persistido del agregado. `Action`, `GameView`, rutas públicas de consulta y formatos WebSocket no cambian. Swagger describe los nuevos campos y `creatorPlayerId` figura como requerido. Datos anteriores sin creador siguen legibles: no se reinician partidas ni se inventa su historia. Su turno actual y asientos se conservan; las futuras rotaciones usan sus asientos existentes.

## Rotación y casos especiales

`TurnOrder.nextPlayer` elige el siguiente asiento activo mayor que el actual, o vuelve al menor. No usa índices de colección, IDs ni conexiones. Dobles mantienen el asiento actual cuando corresponde el premio; al concluir el turno extra se sigue con su sucesor horario. El timeout y `END_TURN` conservan su regla previa de no conceder el premio. Los turnos extra cuentan para la duración de efectos del propietario.

Retiro definitivo marca al participante y devuelve sus fichas a casa, sin eliminar/compactar su asiento. Se omite al rotar. Desconexión temporal mantiene asiento y orden; reconexión solo recupera presencia. Si quedan menos de dos activos se conserva la finalización `ABANDONED`, sin inventar ganador. AUTH, JOIN_GAME y SYNC_REQUEST no cambian la secuencia.

## Refactorización aplicada

| Clase | Responsabilidad y métodos principales |
|---|---|
| `RuleEngine` | Coordina movimiento y reglas; `create` separa validación de creación, `calculate` separa ocupación, `move` separa captura. Delega dados y transiciones. Conserva API del motor y eventos. |
| `DiceRules` (nueva) | `roll`, `authorizedValue`, `consume`, `maximumValue`, `selectionEffect`: rango, selección autorizada, consumo independiente y precedencia. Un único cálculo compartido con el bot. |
| `TurnManager` (nueva) | `start`, `finish`, `expireOwnEffects`: plazos y duración por turnos propios, emisión de transición y solicitud de elección de dados. |
| `TurnOrder` (nueva) | `nextPlayer`: sucesor por asiento físico horario y excepción de dobles. |
| `CardRules` (nueva) | `choices`, `grant`, `resolveTarget`, `nextCapture`, `nextSafe`, `applyLimit`, `moveToSafe`: objetivos y aplicación inmediata, sin inventario. |
| `TriviaRules` | Ciclo de pregunta/respuesta/recompensa y expiración; delega cartas. `target` usa una sola lectura de la definición para validar y aplicar. |
| `Board` | Constantes de geometría y métodos legibles; fórmulas y respuesta `definition` se conservan. |
| `GameState` | Consulta compartida `occupants` por posición física; nuevo `creatorPlayerId`. Se conserva la corrección de `Effect` con `JsonCreator.Mode.DISABLED`. |
| `BotPolicy` | Métodos para trivia, carta, lanzamiento, movimiento ponderado y exposición; pesos con nombres, sin cambiar valores/criterios. Reutiliza reglas de efectos de dados. |
| `GameService` | `validateAction`, `duplicateResult`, `rememberReceipt`, `authorizeConnection`: acceso y flujo de confirmación legibles. Mantiene SHA-256,256 recibos, deduplicación antes de versión y CAS. |
| `GameWebSocketHandler` | `authenticate`, `dispatch`, `join`, `sendPublication`, `maintainConnection`: separa protocolo, conexión y envío; autoriza participantes mediante el servicio. Constantes de límites/plazos. |
| `GameScheduler` | `maintainGame`, `maintenanceEvents`, `updatePresence`: evita bucles/condicionales anidados con responsabilidades mezcladas. Conserva aislamiento de fallos por partida. |

Se corrigieron responsabilidad excesiva del motor de trivia, reglas de dados duplicadas en el bot, lectura doble de carta, números poco expresivos, parámetros internos del bot con numerosos nulls y métodos de transporte/mantenimiento difíciles de seguir. Se usó composición dentro del motor existente, sin añadir interfaces, contenedores ni jerarquías artificiales. Repositorios, JWT, configuración de Eureka y runner de bots funcionales no se refactorizaron por iniciativa propia.

## Pruebas añadidas y ejecutadas

Se añadieron **25 pruebas** a las46 existentes; ninguna fue eliminada o deshabilitada.

- 12 pruebas en `TurnOrderTest`: creador desde cualquier posición/asiento, rotación2/3/4, huecos y colecciones reordenadas, bots, dobles, retiro de sucesor/creador, desconexión, reconexión, creador ausente/ajeno/conflicto legado y asientos inválidos. Algunas recorren varias combinaciones por prueba.
- 3 nuevas en `WebSocketTest`: independencia del orden de llegada AUTH/JOIN/SYNC, timeout de autenticación/expiración JWT y rechazo de identidad bot.
- 1 integración HTTP: contrato del creador/asientos, falta de creador sin fallback, credencial de servicio y esquema OpenAPI.
- 2 de cartas: reemplazo de efectos al reaplicar y duración en turnos adicionales/propios sin descontar rivales.
- 2 de bots: selección de otra alternativa razonable y respuesta errónea de trivia válida.
- 3 en `GameSchedulerTest`: inicio de gracia/retiro de humanos, presencia renovada sin cambio de asiento/turno y timeout que omite retirados.
- 2 de serialización: creador/asientos dispersos y lectura de agregado antiguo sin creador conservando su turno.

Antes de extraer cartas/bots/scheduler/handler se ejecutaron las nueve pruebas de caracterización nuevas contra el código anterior, con resultado exitoso.

Comandos ejecutados desde `game-service/` (los sufijos de redirección guardan los logs bajo `.build/`):

| Comando Maven, siempre con `-Dmaven.repo.local=.build/m2 -o` | Resultado real |
|---|---|
| `-Dtest=TurnOrderTest,RuleEngineTest,WebSocketTest,GameStateSerializationTest test` |29 aprobadas, log `turn-order-tests.log` |
| `-Dgame.integration=true verify` tras corregir turnos |60 aprobadas, `turn-order-verify.log` |
| `-Dtest=CardsTriviaTest,BotTest,WebSocketTest,GameSchedulerTest test` antes de refactorizar |25 aprobadas, `characterization-tests.log` |
| `-Dtest=BoardTest,RuleEngineTest,TurnOrderTest,CardsTriviaTest,BotTest,GameStateSerializationTest test` |45 aprobadas, `refactor-rules-tests.log` |
| `-Dtest=CardsTriviaTest,BotTest,RuleEngineTest,TurnOrderTest,GameStateSerializationTest test` |43 aprobadas, `refactor-cards-bots-tests.log` |
| `-Dtest=GameServiceTest,GameSchedulerTest,WebSocketTest,DisconnectTest,TurnOrderTest test` |25 aprobadas, `refactor-transport-tests.log` |
| `-Dgame.integration=true verify`, primer intento final |71 ejecutadas,0 fallos y1 error: fixture nuevo omitía `bot`; `clean-code-verify.log` |
| `-Dgame.integration=true verify`, fixture corregido |**71 aprobadas,0 fallos,0 errores,0 omitidas; BUILD SUCCESS**, `clean-code-verify-final.log` |
| `-Dgame.integration=true verify`, árbol actual tras movimiento ajeno de QuestionDefinition |**71 aprobadas,0 fallos,0 errores,0 omitidas; BUILD SUCCESS** a las14:38:57 de Bogotá; `clean-code-verify-current-tree.log` |

El error se corrigió incluyendo `bot=false` en el fixture antiguo representativo; no se relajó Jackson ni se modificó producción para ocultarlo. Las pruebas de integración usaron MongoDB/Redis reales y HTTP/WebSocket en puerto aleatorio; verifican semillas idempotentes, CAS, efectos persistidos, JWT, Swagger, inicio interno, dos sockets, dados, movimiento, ACK duplicado y sincronización. Claves de prueba con prefijo aleatorio; nunca se vació Redis ni se borró estado previo.

| Suite final | Pruebas |
|---|---:|
| BoardTest |2|
| RuleEngineTest |12|
| TurnOrderTest |12|
| CardsTriviaTest |11|
| BotTest |6|
| GameServiceTest |4|
| GameSchedulerTest |3|
| GameStateSerializationTest |4|
| WebSocketTest |5|
| DisconnectTest |1|
| JwtTest |2|
| GameServiceApplicationTests |3|
| InfrastructureIntegrationTest |6|
| Total |**71**|

Reports: `target/surefire-reports/`. Artefacto: `target/game-service-0.0.1-SNAPSHOT.jar`.

## Probes operativos e integraciones pendientes

Se ejecutó `python3 tools/validate_runtime.py > .build/clean-code-runtime-final.log 2>&1` contra el JAR nuevo. **PASS** en `.build/runtime-validation/host-bc5d8a322e8b.json`, con procesos propios y prefijos Redis únicos; conserva las cuatro partidas creadas y detuvo sus dos procesos. La herramienta se actualizó al contrato creador/asientos y a una espera más amplia de registro.

| Probe actual | Resultado observado |
|---|---|
| Arranque con clave pública real de Auth, MongoDB y Redis | SaludUP en8084 |
| Eureka | `192.168.1.18:game-service:8084`, estadoUP observado en consulta |
| Turnos automáticos | Inicio con creador900002 y timeout al siguiente asiento900001 |
| Vuelta horaria real | Creador900003(seat2), array desordenado; `900003→900004→900001→900002→900003` mediante cuatro expiraciones |
| Bots fuera de tests | Dados generados, ficha movida, turno3/version4 sin acciones cliente |
| JWT compatible con Auth | Su clase JwtService compilada emitió tokens con clave aislada y Game los validó; no es token OAuth de producción |
| Tablero REST |96 casillas,12 seguros,16 salidas, coronas83/87/91/95,16 recorridos |
| WebSocket directo18084 | Sin AUTH rechazado; dos sesiones autenticadas, dados/movimiento publicados, ACK duplicado y sincronización versión10 |

El probe no leyó la clave privada de Auth, no modificó sus clases ni servicios, no vació Redis y no borró estados existentes. La espera ampliada permitió observar el registro UP; no se deduce que el primer timeout fuera un fallo del registro de Game.

El primer intento arrancó con saludUP y registration204, pero la consulta Eureka no mostróUP en los20 segundos de espera. Se amplió a45 segundos solo el plazo del probe, sin editar Eureka o configuración de Game. Log fallido: `.build/clean-code-runtime.log`; reporte `.build/runtime-validation/host-e9bc7edce8ed.json`.

Pendientes externos: Lobby todavía debe implementar salas, creador inmutable y asientos; Gateway carece de ruta/proxy WebSocket y exige Bearer en el handshake; consumo real desde Godot y login OAuth con token de sesión real no comprobados. El token compatible de los probes se emite con la clase compilada Auth JwtService y clave de prueba aislada, nunca su clave privada. Las imágenes Docker de [VALIDATION.md](VALIDATION.md) pertenecen al cierre anterior; no se han reconstruido en esta etapa. No se declara integración completa.

## Archivos de esta etapa y límites

La lista siguiente distingue esta etapa del trabajo previo, que en gran parte todavía está sin seguimiento en Git. Todas las rutas se interpretan dentro de `game-service/`.

Nuevos:

```text
src/main/java/com/pixelchiss/gameservice/engine/TurnOrder.java
src/main/java/com/pixelchiss/gameservice/engine/TurnManager.java
src/main/java/com/pixelchiss/gameservice/engine/DiceRules.java
src/main/java/com/pixelchiss/gameservice/engine/CardRules.java
src/test/java/com/pixelchiss/gameservice/TurnOrderTest.java
src/test/java/com/pixelchiss/gameservice/GameSchedulerTest.java
docs/CLEAN_CODE.md
```

Modificados:

```text
src/main/java/com/pixelchiss/gameservice/domain/Board.java
src/main/java/com/pixelchiss/gameservice/domain/GameState.java
src/main/java/com/pixelchiss/gameservice/dto/CreateGame.java
src/main/java/com/pixelchiss/gameservice/engine/RuleEngine.java
src/main/java/com/pixelchiss/gameservice/engine/TriviaRules.java
src/main/java/com/pixelchiss/gameservice/bot/BotPolicy.java
src/main/java/com/pixelchiss/gameservice/service/GameService.java
src/main/java/com/pixelchiss/gameservice/service/GameScheduler.java
src/main/java/com/pixelchiss/gameservice/websocket/GameWebSocketHandler.java
src/main/java/com/pixelchiss/gameservice/controller/LobbyIntegrationController.java
src/test/java/com/pixelchiss/gameservice/EngineFixture.java
src/test/java/com/pixelchiss/gameservice/CardsTriviaTest.java
src/test/java/com/pixelchiss/gameservice/BotTest.java
src/test/java/com/pixelchiss/gameservice/WebSocketTest.java
src/test/java/com/pixelchiss/gameservice/InfrastructureIntegrationTest.java
src/test/java/com/pixelchiss/gameservice/GameStateSerializationTest.java
tools/validate_runtime.py
README.md
docs/BOARD.md
docs/ENGINE.md
docs/WEBSOCKET.md
docs/INTEGRATIONS.md
docs/DELIVERY.md
docs/VALIDATION.md
```

Se generaron logs, copia de referencia en `.build/clean-code-baseline/` y artefactos Maven bajo Game. Se observó durante la sesión un movimiento de `domain/QuestionDefinition.java` a `controller/QuestionDefinition.java`, ajeno a estas operaciones; conserva exactamente su contenido y paquete `domain`. No se deshizo ni se atribuye a esta refactorización; la verificación final se repitió sobre esa ubicación con71 pruebas aprobadas.

No se modificó ningún archivo fuera de Game. Se preservaron los preexistentes `Terminal` y `Tool`. No se realizaron commits, pushes, resets, borrados de estados previos ni cambios de infraestructura. Propuesta opcional de agrupación futura: corrección de turnos y contrato; extracción de reglas; aplicación/transporte; pruebas/documentación. No se ejecutó ningún commit.
