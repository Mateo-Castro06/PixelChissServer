# Validación de corredor final v5-100

## Cambio y alcance

Baseline: `1ba21cdee37f6e00a04517895fc9d33cb7f6cc28` en main.
La regla autorizada sustituye el retorno a la salida: última común 63, primera final
64 y corona 71. El recorrido v4 usaba 68/69/76 y añadía cinco pasos antes de entrar.
Se conservan los 100 IDs físicos, 68 comunes y ocho posiciones por corredor.

| Color | Salida p=0 | Entrada p=63 | Primera final p=64 | Corona p=71 |
| --- | ---: | ---: | ---: | ---: |
| Rojo | 5 | 0 | 68 | 75 |
| Azul | 22 | 17 | 76 | 83 |
| Verde | 39 | 34 | 84 | 91 |
| Amarillo | 56 | 51 | 92 | 99 |

Cada ruta tiene 72 IDs distintos, sin repetir su salida: progreso 0–63 común y
64–71 final. La unión de las rutas sigue cubriendo las 100 posiciones. Se conserva
`position=null, progress=-1, status=HOME`; la corona conserva status CROWNED.

Producción cambia únicamente Board (versión, offset y límite común), más los textos
OpenAPI de BoardLayout y GameController. RuleEngine, GameState, GameService, CardRules,
BotPolicy, BotRunner y BotPacing no necesitan cambios: ya dependen de Board.FINISH,
Board.LAST_COMMON_PROGRESS y Board.position. El límite exacto y el premio del tercer
par alcanzan ahora progreso 71 por esas constantes.

## Pruebas y evidencia

Antes de cambiar Board, las 24 variantes nuevas de FinalLaneContractTest fallaron
contra v4: reprodujeron la entrada incorrecta, llegada y cruces de varios pasos.
Resultado final de `mvn -o -Dgame.integration=true verify -B`: **421 pruebas aprobadas,
cero fallos, cero errores y cero omisiones**. Incluye los dos mapas y cuatro colores,
Spring real, Redis/MongoDB reales aislados, Gateway real y clientes WebSocket con JWT.

Se actualizaron BoardTest, LapEntryTest, Board100RulesTest, FinishPathsTest y
FinalLaneContractTest, conservando sus comprobaciones de límites, capacidades,
corredores privados y llegada exacta. Las regresiones de reglas existentes conservaron
sus aserciones: se adaptaron progresos de fichas próximas a coronar y fixtures que
antes ubicaban fichas en el tramo omitido de su color. EngineFixture ahora verifica
que una posición común preparada coincide con la ruta de ese propietario.

Se comprobaron HOME, liberación parcial y restricción de la recién liberada, captura
obligatoria/SOPLAR, bloqueo de capturadora, tercer par y premio, descarte automático,
turnos, 30/10/15 segundos y pausa de trivia, cartas, bots, pacing, recibos, CAS,
reconexión y recuperación de otra JVM. No se borraron pruebas ni se relajaron las
comprobaciones de reglas para hacerlas pasar.

Los primeros ensayos detectaron expectativas v4 y fixtures fuera del nuevo recorrido.
La integración de cartas conservaba progreso 67/posición 4: se preparó ahora justo antes
del seguro nuevo, progreso 62/posición 67, con rival en 0. Se mantuvieron las aserciones
de captura, desplazamiento a seguro, efectos y persistencia. La suite final completa
pasó después de esos ajustes. Los intentos bloqueados por sockets del sandbox se
repitieron con permisos, siempre sobre procesos de prueba aislados.

### Cruces por Gateway WebSocket

Se verificaron **40 casos reales**, 20 por mapa: entradas con dados de 1–4 y el cruce
específico desde progreso 62 con dado 3 para cada color. Evidencia regenerable:
`game-service/target/final-lane-gateway-classic.json` y `final-lane-gateway-science.json`.

| Color | Estado inicial p/posición | Pasos con dado 3 | Estado final p/posición |
| --- | --- | --- | --- |
| Rojo | 62/67 | 0 → 68 → 69 | 65/69 |
| Azul | 62/16 | 17 → 76 → 77 | 65/77 |
| Verde | 62/33 | 34 → 84 → 85 | 65/85 |
| Amarillo | 62/50 | 51 → 92 → 93 | 65/93 |

El evento rojo recibido conserva el contrato:

```json
{"type":"PIECE_MOVED","data":{"pieceId":"seat-0-piece-0","from":67,"to":69,"dieIndex":0,"status":"BOARD"}}
```

El snapshot confirma progreso 65/posición 69. No se envía una ruta nueva: Godot usa
la definición v5 del asiento y los progresos anterior/nuevo para reconstruir los pasos.
También se probaron 70+1, 69+2 y 68+3, rechazo de 70+3, dos dados consecutivos,
seguros llenos y cartas que llegan al seguro y se detienen antes del corredor.

La regresión de cierre inmediato `(4,3)` conservó sus eventos en un solo commit:
captura, movimiento y TURN_CHANGED. En esta ejecución local el observador recibió
el cierre en 3 ms en classic y 6 ms en science. No son garantías de latencia LAN.
El test de pacing Redis observó 85 ms para un presupuesto configurado de 80 ms;
los presupuestos de producción no se modificaron.

### Reproducción

Desde game-service, con procesos Redis y MongoDB **aislados**:

```bash
GAME_TEST_GATEWAY_JAR=/ruta/absoluta/api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar \
GAME_TEST_REDIS_URL=redis://127.0.0.1:16379/0 \
GAME_TEST_MONGO_URI=mongodb://127.0.0.1:17017/pixelchiss_lane_v5 \
mvn -o -Dgame.integration=true verify -B
```

En esta ejecución se usaron ejecutables temporales y datos bajo /tmp, con puertos
127.0.0.1:16379 y :17017. No se creó otro proyecto Docker ni se utilizaron las bases
LAN para preparar fixtures. Los tres scripts de infrastructure/tests afectados se
actualizaron y pasaron análisis de sintaxis; sus probes operativos completos, que
requieren identidades y un entorno aislado adicional, no se ejecutaron aquí.

## Persistencia y política de compatibilidad

v5-100 es un marcador nuevo: **no se reutiliza v4-100**. Redis guarda boardLayout,
progreso, posición, versión y recibos; MongoDB mantiene catálogos. Se comprobaron
estados v4 en progresos 0, 63–69, 75 y 76: se rechazan sin reinterpretación ni mutación.
También se verificó el rechazo de v4 por Gateway REST (409) y WebSocket
(UNSUPPORTED_BOARD_LAYOUT), conservando el JSON original.

El inventario de la instancia LAN encontró cero partidas en el índice principal y
ningún hash bajo el prefijo principal de estados. Hay **29 hashes históricos de
validación**, sin boardLayout: 18 llevan status ACTIVE, 10 ABANDONED y uno FINISHED.
Esos flags históricos de otros namespaces no son partidas del índice activo principal.
No se tocaron sus contenidos; se registraron huellas para comprobar su conservación.
No se deduce una geometría por sus coordenadas: ya eran incompatibles sin marcador.

Los estados v4 se preservan pero no pueden reanudarse con el motor v5. No se mantiene
un segundo motor: Board y su endpoint son globales, y el cliente necesitaría rutas
seleccionadas por partida para soportar versiones simultáneas correctamente.

No hay migración automática. Un p=64 antiguo puede significar una común que ya no
forma parte de la ruta propia; sustituir el marcador o restar cinco globalmente
corrompería esa interpretación. Los progresos finales antiguos podrían desplazarse
cinco conservando su posición, pero eso por sí solo no resuelve las fichas comunes,
dados, cartas pendientes, recibos y solicitudes en vuelo de toda la partida.

Para reanudar una antigua se requiere su versión compatible o una migración diseñada
y autorizada por separado. Para partidas nuevas se usa otro ID y v5. No se borran
partidas ni volúmenes. La decisión de desplegar debe incluir esta limitación explícita.

## Docker, LAN y cliente

El proyecto Compose existente es docker, con el archivo base y overlay LAN. El Game
ejecutado conserva la imagen sha256:3a82ccb23ea10ded444472e6ea6b66796b1f184751705841e6b628c1b54930e7:
sus clases RuleEngine/TriviaRules coinciden con la revisión 8d9c7c7, anterior al arreglo
1ba21cd. Esa actualización continúa pendiente; la imagen v5 preparada incluye también
1ba21cd. No se recreó Game durante la implementación.

Se prepara únicamente la imagen de Game con los mismos Compose y env-files. No se
modifican certificados, firewall, CA, puertos, Auth, Lobby, Gateway, PostgreSQL,
Redis/MongoDB LAN, montajes ni volúmenes. Las huellas de entorno y archivos LAN se
controlan antes y después. HTTPS verificó CA/hostname y salud UP; descubrimiento de
CA idéntica y WSS hasta Game con el contrato INVALID_TOKEN también pasaron.
Esa prueba LAN usa el Game actual: no certifica aún el nuevo runtime v5 por WSS.

El despliegue v5 requiere autorización y coordinación con Godot, incluso con índice
principal vacío, para no presentar estados antiguos como compatibles. Godot debe
renovar la caché a v5, usar rutas de 72 IDs, entrada 63→64 y corona 71; los detalles
están en [WEBSOCKET.md](WEBSOCKET.md). No se ejecutó ni modificó Godot: falta verificar
su animación y sus coordenadas reales tras la actualización coordinada.

## Archivos afectados

- `docs/BOARD100_VALIDACION.md`
- `game-service/README.md`
- `game-service/docs/BOARD.md`
- `game-service/docs/BOARD100_VALIDACION.md`
- `game-service/docs/TURN_RESOLUTION_VALIDATION.md`
- `game-service/docs/WEBSOCKET.md`
- `game-service/src/main/java/com/pixelchiss/gameservice/controller/GameController.java`
- `game-service/src/main/java/com/pixelchiss/gameservice/domain/Board.java`
- `game-service/src/main/java/com/pixelchiss/gameservice/dto/BoardLayout.java`
- `game-service/src/test/java/com/pixelchiss/gameservice/AutomaticRollResolutionTest.java`
- `game-service/src/test/java/com/pixelchiss/gameservice/Board100RulesTest.java`
- `game-service/src/test/java/com/pixelchiss/gameservice/BoardTest.java`
- `game-service/src/test/java/com/pixelchiss/gameservice/BotTest.java`
- `game-service/src/test/java/com/pixelchiss/gameservice/CaptureRollLockTest.java`
- `game-service/src/test/java/com/pixelchiss/gameservice/CaptureRollPersistenceTest.java`
- `game-service/src/test/java/com/pixelchiss/gameservice/CardsTriviaTest.java`
- `game-service/src/test/java/com/pixelchiss/gameservice/DefinitiveRulesTest.java`
- `game-service/src/test/java/com/pixelchiss/gameservice/EngineFixture.java`
- `game-service/src/test/java/com/pixelchiss/gameservice/FinalLaneContractTest.java`
- `game-service/src/test/java/com/pixelchiss/gameservice/FinishPathsTest.java`
- `game-service/src/test/java/com/pixelchiss/gameservice/GameServiceTest.java`
- `game-service/src/test/java/com/pixelchiss/gameservice/GameStateSerializationTest.java`
- `game-service/src/test/java/com/pixelchiss/gameservice/InfrastructureIntegrationTest.java`
- `game-service/src/test/java/com/pixelchiss/gameservice/LapEntryTest.java`
- `game-service/src/test/java/com/pixelchiss/gameservice/PairsAndTriviaTimersTest.java`
- `game-service/src/test/java/com/pixelchiss/gameservice/PartialHomeReleaseTest.java`
- `game-service/src/test/java/com/pixelchiss/gameservice/ReleaseCardsTest.java`
- `game-service/src/test/java/com/pixelchiss/gameservice/RuleEngineTest.java`
- `infrastructure/tests/definitive_timeouts.py`
- `infrastructure/tests/pairs_and_trivia.py`
- `infrastructure/tests/two_humans.py`
