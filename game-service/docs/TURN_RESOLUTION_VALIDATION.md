# Auditoría de cierre automático y corredor final

Validación del 23 de noviembre de 2026 sobre `main`, inicialmente
`8d9c7c7ee76becb498f6a3a51431a10235c5480e`.

## Diagnóstico y corrección limitada

El caso comunicado `(4,3)` **ya cerraba inmediatamente** en el motor vigente:
captura de 6 a 10 con el 4, capturadora bloqueada y otra ficha en progreso 75
(a un paso de la corona). El 3 excede la meta de la segunda ficha; `legalMoves`
queda vacío y `continueTurn` consume el resto y cambia de jugador en el mismo commit.
También se verificó capturar con el 3 y descartar el 4. La reproducción pasó antes
de modificar producción. Las clases relevantes del JAR LAN se compararon byte a byte
con las compiladas desde HEAD: RuleEngine, TriviaRules, Board, DiceRules, GameService,
GameScheduler, BotRunner y BotPacing eran idénticas.

No se dispone del snapshot ni de la traza de Godot del incidente original. Por tanto,
no se atribuye su espera observada a un timeout del backend ni se afirma un fallo
visual del cliente como hecho demostrado. Una trivia/carta pendiente puede impedir
legítimamente el cierre aunque `legalMoves` esté vacío.

Se reprodujeron dos defectos concretos relacionados:

1. Al confirmar dados imposibles, fuera del caso especial HOME, se publicaba
   `MOVEMENT_TIMER_STARTED` seguido de `TURN_CHANGED` o `ROLL_AVAILABLE` en el mismo
   resultado. El snapshot final estaba resuelto, pero el evento iniciaba un reloj
   que no tenía una fase de movimiento útil. Se generalizó la comprobación existente
   de movimientos antes de emitir ese inicio. Cuando sí existen opciones se conservan
   íntegros los 30 segundos.
2. Si se recuperaba un estado ya persistido con dados pendientes, animación completada
   y ninguna opción, `tick` no lo resolvía hasta el deadline. Se añadió la revalidación
   mediante el mismo `continueTurn` en el mantenimiento anterior al vencimiento.
   TriviaRules delega al motor base cuando no hay desafíos; su resolución de trivia,
   cartas y vencimientos no cambia.

Las nuevas regresiones reprodujeron ocho fallos antes de corregir producción:
cuatro variantes de temporizador innecesario y cuatro de recuperación. El escenario
ordinario de captura ya pasaba. No se cambió `legalMoves`, el bloqueo por captura,
la llegada exacta, HOME, SOPLAR, dados, elección de bots ni geometría.

El descarte normal ocurre dentro de la acción aceptada, sin esperar un tick ni
`END_TURN`. La recuperación ocurre en el siguiente mantenimiento, por defecto cada
1000 ms, más carga y posibles conflictos CAS. Si ya venció el deadline, se conserva
la resolución de timeout. Animación pendiente, trivia, carta y elección de coronación
mantienen sus obligaciones y plazos. Los pares habilitan `ROLL_AVAILABLE`, salvo el
tercero: se conserva su premio y cierre sin cuarta tirada.

Estado, recibo y notificación se confirman con el CAS Redis existente. Los reintentos
no consumen otro dado ni cambian otra vez el turno. Un tick sin transición no aumenta
la versión. El agregado nuevo reemplaza los relojes del turno anterior; no hay tareas
por dado ni temporizadores adicionales. Redis conserva partidas y recibos; MongoDB
contiene catálogos, no una segunda copia del estado de turnos. BotPacing no se modifica
ni se reserva una pausa nueva por el mantenimiento sin acción visual.

## Evidencia temporal y WebSocket

Integración real Gateway → Game → Redis, con JWT de prueba, dos clientes WebSocket,
MongoDB real para catálogos y bases aisladas. Posiciones y dados se prepararon como
fixtures de prueba, no en partidas LAN existentes.

| Mapa | Captura enviada → TURN_CHANGED recibido por el otro cliente |
| --- | ---: |
| classic | 4 ms |
| science | 3 ms |

Son mediciones locales de esta ejecución, no un límite de latencia de producción.
La comprobación con reloj controlado no adelantó el tiempo entre captura y cierre.
El observador recibió una única versión nueva con los eventos, en este orden:

```json
{"type":"PIECE_CAPTURED","data":{"pieceId":"seat-1-piece-0","byPieceId":"seat-0-piece-0"}}
{"type":"PIECE_MOVED","data":{"pieceId":"seat-0-piece-0","from":6,"to":10,"dieIndex":0,"status":"BOARD"}}
{"type":"TURN_CHANGED","data":{"playerId":"test-b","turnNumber":2,"deadline":1795493031154}}
```

El timestamp corresponde a la ejecución `classic` y es el plazo de preparación del nuevo turno.
El snapshot incluye `turn.playerId="test-b"`, `dice=[]`, `movementStartedAt=null` y
la otra ficha sin cambiar, en progreso 75/posición 74. No se añaden eventos de descarte.
Los dados del turno cerrado se consumen antes de reemplazar el turno; el nuevo snapshot
no expone un array de consumo histórico del anterior. Una repetición conserva el ACK
con `duplicate=true`; `SYNC_REQUEST` devuelve la versión actual y sus `legalMoves`.

Los mensajes completos se regeneran en `target/no-moves-gateway-{classic,science}.json`.

## Corredor: contrato confirmado, sin cambio de geometría

Layout vigente `v4-100`: 68 casillas comunes; progreso 0 al salir, 68 al regresar a la
misma salida y 69 al entrar en la primera final. Corona exacta en progreso 76. Cada
corredor tiene ocho posiciones. El número físico no es el progreso de la ficha.

| Color/asiento | Salida y última común p=68 | Predecesora p=67 | Primera final p=69 | Corona p=76 |
| --- | ---: | ---: | ---: | ---: |
| Rojo/0 | 5 | 4 | 68 | 75 |
| Azul/1 | 22 | 21 | 76 | 83 |
| Verde/2 | 39 | 38 | 84 | 91 |
| Amarillo/3 | 56 | 55 | 92 | 99 |

La siguiente tabla se contrastó con mensajes reales `PIECE_MOVED` y snapshots por
Gateway WebSocket, para **ambos mapas**. La columna de pasos se obtiene de la ruta;
el evento transmite origen/destino, sin un array de puntos intermedios.

| Color | Inicio progreso/posición | Dado | Pasos físicos esperados | Final progreso/posición; `from → to` real |
| --- | --- | ---: | --- | --- |
| Rojo | 68/5 | 1 | 68 | 69/68; 5 → 68 |
| Rojo | 67/4 | 2 | 5, 68 | 69/68; 4 → 68 |
| Rojo | 66/3 | 3 | 4, 5, 68 | 69/68; 3 → 68 |
| Rojo | 65/2 | 4 | 3, 4, 5, 68 | 69/68; 2 → 68 |
| Azul | 68/22 | 1 | 76 | 69/76; 22 → 76 |
| Azul | 67/21 | 2 | 22, 76 | 69/76; 21 → 76 |
| Azul | 66/20 | 3 | 21, 22, 76 | 69/76; 20 → 76 |
| Azul | 65/19 | 4 | 20, 21, 22, 76 | 69/76; 19 → 76 |
| Verde | 68/39 | 1 | 84 | 69/84; 39 → 84 |
| Verde | 67/38 | 2 | 39, 84 | 69/84; 38 → 84 |
| Verde | 66/37 | 3 | 38, 39, 84 | 69/84; 37 → 84 |
| Verde | 65/36 | 4 | 37, 38, 39, 84 | 69/84; 36 → 84 |
| Amarillo | 68/56 | 1 | 92 | 69/92; 56 → 92 |
| Amarillo | 67/55 | 2 | 56, 92 | 69/92; 55 → 92 |
| Amarillo | 66/54 | 3 | 55, 56, 92 | 69/92; 54 → 92 |
| Amarillo | 65/53 | 4 | 54, 55, 56, 92 | 69/92; 53 → 92 |

Evidencia completa: `target/final-lane-gateway-{classic,science}.json`, 16 casos por mapa.
Las pruebas deterministas también cruzan uno y dos pasos más allá de la primera final,
avanzan en el corredor, coronan desde progreso 73 con 3 y rechazan 75 + 3.
No se reprodujo un desplazamiento adicional de cuatro casillas en Game.

Godot debe comparar sus coordenadas con `GET /api/game/board`, usar la ruta del asiento
correcto y sus índices de progreso. No reconstruir progreso desde `position`: las visitas
0 y 68 tienen el mismo ID. No mezclar tablas históricas v3 con v4 ni sumar offsets al
destino del evento. Se corrigieron referencias documentales que aún presentaban
0/17/34/51 como salidas vigentes; esos son comienzos de sector.

No se ejecutó ni modificó Godot. Falta contrastar esta tabla con sus coordenadas reales
y registrar recepción/versiones, inicio/fin de animaciones y temporizadores del incidente.
Si ya recibió `TURN_CHANGED` pero sigue mostrando la captura, el retraso es de presentación;
si el estado sigue en el jugador anterior, comprobar desafíos, versión y `legalMoves`.

## Pruebas y reproducción

- Baseline Game: 350 casos, 327 pasaron, 23 integraciones omitidas, cero fallos/errores.
- Regresiones finales e integración real de Game: **403 pasaron, cero fallos/errores/omisiones**.
- Gateway: **20 pasaron, cero fallos/errores/omisiones**.
- Sin rebajar aserciones existentes. Se corrigió una fixture nueva que intentaba cambiar
  de partida en la misma conexión; el protocolo exige una conexión por partida.
- Se verificaron recuperación, dos mantenimientos concurrentes, duplicados, solicitudes
  tardías, reconexión, reloj controlado, HOME, capturas/SOPLAR, tercer par, temporizadores,
  cartas/trivia, BotPacing, ambos mapas y los cuatro colores.

Desde `game-service/`, con Redis/Mongo **aislados** disponibles:

```bash
GAME_TEST_GATEWAY_JAR=/ruta/absoluta/api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar \
GAME_TEST_REDIS_URL=redis://127.0.0.1:16379/0 \
GAME_TEST_MONGO_URI=mongodb://127.0.0.1:17017/pixelchiss_turn_audit \
mvn -o -Dgame.integration=true verify -B
```

En esta ejecución las bases de prueba fueron procesos nativos con datos en `/tmp`,
usando ejecutables copiados en lectura de las imágenes existentes. No se creó otro
proyecto Docker ni se modificaron bases, volúmenes o partidas LAN. El Gateway LAN pasó
salud HTTPS, verificación CA/hostname, descubrimiento de CA idéntica y transporte WSS
hasta Game con el contrato `INVALID_TOKEN`. Eso no prueba una partida visual en Godot.
