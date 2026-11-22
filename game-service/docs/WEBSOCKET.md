> Integración Gateway disponible: `ws://localhost:8080/api/game/ws` (usar `wss` bajo TLS). Directo Game: `ws://localhost:8084/ws/game`. El protocolo JSON siguiente es el mismo en ambas rutas. Evidencia y seguridad del puente en [GATEWAY.md](GATEWAY.md).

# WebSocket JSON v1

Endpoint directo `ws://localhost:8084/ws/game`; con TLS, `wss://.../ws/game`. WebSocket estándar de texto, sin STOMP/SockJS. Una partida por conexión; mensajes hasta 16KiB y buffer de envío 64KiB.

## Autenticar y sincronizar

Primer mensaje dentro de 10 segundos:

```json
{"type":"AUTH","token":"<access-token-de-Auth>"}
```

Respuesta `{"type":"AUTHENTICATED","playerId":"42"}`. La identidad deriva exclusivamente del subject firmado. Antes de autenticar no puede leer, actuar, unirse ni recibir eventos. La conexión vence con el JWT: renovar usando Auth y volver a conectar.

No enviar query strings: el handshake las rechaza. No se necesitan headers personalizados. [Godot WebSocketPeer no admite esos headers en exportación web](https://docs.godotengine.org/es/4.x/classes/class_websocketpeer.html); por eso se usa primer mensaje y nunca tokens en URLs. Godot debe mantener `poll()`, procesar Ping/Pong y cierres. El cliente no controla temporizadores. Usar TLS en despliegue y configurar `GAME_ALLOWED_ORIGINS` para navegador; Godot nativo puede omitir Origin.

```json
{"type":"JOIN_GAME","gameId":"game-123"}
```

Verifica pertenencia, rechaza bots/retirados y responde `GAME_STATE` con `state` y `legalMoves` calculados desde el mismo snapshot. Para recuperar eventos perdidos:

```json
{"type":"SYNC_REQUEST","gameId":"game-123"}
```

Snapshot: gameId, version, mapId, status, winnerId, players, pieces, turn, trivia y card. Cada ficha tiene id, ownerId, number, status, progress y position. `turn`: playerId, number, deadline Unix en milisegundos, dice, consumed, rolled, doubles, maxDieValue y diceSelectionRequired. Los efectos incluyen code, remainingTurns, startsAtTurn y maxValue. No hay recibos internos ni respuesta correcta de trivia pendiente.

Una reconexión puede cancelar desconexión pendiente, incrementando versión y publicando el evento. También existe consulta REST con Bearer. El mapa de100 casillas se obtiene una vez mediante `GET /api/game/board`, no en cada jugada.

## Acciones

Todas las mutaciones requieren `type`, `gameId`, `requestId` único y `expectedVersion`. IDs de partida/solicitud:1–80 caracteres alfanuméricos, guion o guion bajo. La versión debe ser la del último estado confirmado. Esperar ACK/estado antes de construir otra acción; no enviar identidad en el payload.

Lanzar:

```json
{"type":"ROLL_DICE","gameId":"game-123","requestId":"roll-1","expectedVersion":0}
```

El servidor genera los resultados y publica `DICE_ROLLED`, por ejemplo `{"type":"DICE_ROLLED","data":{"playerId":"42","dice":[4,2]}}`, dentro de `GAME_UPDATED`. El lote incluye DICE_ANIMATION_REQUIRED con rollId. En humanos el servidor espera confirmación antes de habilitar movimiento o resolver nuevos intentos.

Solo si `diceSelectionRequired=true`, incluir `"chosenDice":[2,3]`. El servidor verifica el efecto y `maxDieValue`; fuera de ese derecho rechaza `UNAUTHORIZED_DICE`.

Mover (ejemplo orientativo sujeto a origen/recorrido reales):

```json
{
  "type":"MOVE_REQUEST","gameId":"game-123","requestId":"move-1","expectedVersion":1,
  "pieceId":"seat-0-piece-0","dieIndex":0,"dieValue":4,"fromPosition":24,"targetPosition":28
}
```

HOME se libera automáticamente al obtener par, mediante PIECE_RELEASED con from=null; no requiere MOVE_REQUEST. El servidor recalcula, nunca acepta posición por el solo envío. `dieIndex` identifica 0 o 1, o 2 para consumir la suma de ambos; si no hay HOME, incluso dados iguales pueden consumirse por separado. Si se libera exactamente una ficha, se consume el dado 0 y queda el dado 1 para fichas previamente activas; la recién liberada queda excluida. Si se liberan dos o más, ambos dados se consumen. El tercer par pasa directamente al premio, sin movimiento. `legalMoves` devuelve pieceId, dieIndex, dieValue, from, to, progress, capture, safe, crown y release.

Responder trivia (índice base0):

```json
{"type":"TRIVIA_ANSWER","gameId":"game-123","requestId":"answer-1","expectedVersion":2,"challengeId":"<trivia.id>","answerIndex":1}
```

El challenge vincula la pregunta del servidor; no confiar en questionId enviado. Antes de resolver no se revela `correctIndex`.

Resolver carta:

```json
{"type":"CARD_TARGET","gameId":"game-123","requestId":"card-1","expectedVersion":3,"challengeId":"<card.id>","pieceId":"seat-0-piece-0","targetPieceId":"seat-1-piece-2","targetPosition":28}
```

Copiar una opción de `card.choices`: pieceId, targetPieceId, targetPlayerId y targetPosition. Los campos irrelevantes se omiten o son null. `advance-safe` usa ficha propia y destino. `kill-chosen` usa **solo `targetPosition`**; los otros campos de objetivo se omiten o se envían como `null`. El ejemplo anterior también sigue válido para un desafío legado que identifica la ficha rival. El motor recalcula opciones. `card` incluye nombre, descripción y código para mostrar la selección inmediata al usuario.

Terminar voluntariamente:

```json
{"type":"END_TURN","gameId":"game-123","requestId":"end-1","expectedVersion":4}
```

Exige lanzamiento y ningún desafío pendiente; descarta resultados y renuncia al premio de dobles.

## ACK, publicación y errores

El solicitante recibe, con snapshot completo abreviado aquí:

```json
{"type":"ACTION_ACK","requestId":"move-1","committedVersion":2,"duplicate":false,"state":{"gameId":"game-123","version":2}}
```

Reintento idéntico: `duplicate=true`, versión de confirmación original y snapshot **actual**, sin ejecutar/publicar de nuevo. Otro payload con igual requestId se rechaza.

Todos los participantes unidos reciben un lote atómico:

```json
{
  "type":"GAME_UPDATED","gameId":"game-123","version":2,"requestId":"move-1",
  "events":[{"type":"PIECE_MOVED","data":{"pieceId":"seat-0-piece-0","from":24,"to":28,"dieIndex":0,"status":"BOARD"}}],
  "state":{"gameId":"game-123","version":2}
}
```

Eventos: `GAME_STARTED`, `DICE_ROLLED`, `DICE_SELECTION_REQUIRED`, `PIECE_MOVED`, `PIECE_CAPTURED`, `CARD_PIECE_RETURNED`, `TURN_CHANGED`, `TURN_EXPIRED`, `TRIVIA_QUESTION`, `TRIVIA_RESOLVED`, `CARD_TARGET_REQUIRED`, `CARD_DISCARDED`, `EFFECT_APPLIED`, `EFFECT_CONSUMED`, `EFFECT_UPDATED`, `EFFECT_EXPIRED`, `PLAYER_DISCONNECTED`, `PLAYER_RECONNECTED`, `PLAYER_RETIRED`, `GAME_FINISHED`, `GAME_ABANDONED`.

**Extensión compatible:** `CARD_PIECE_RETURNED` aparece dentro de `GAME_UPDATED.events` para una devolución sin ficha atacante. Su `data` trae `pieceId`, `playerId`, `from`, `effectCode` y `cardId` (`null` si la devolución viene del efecto periódico de dobles). No cambia `PIECE_CAPTURED`. Godot debe renderizar opciones de casilla cuando venga solo `targetPosition` y usar el snapshot para actualizar la ficha a `HOME`; puede añadir una animación al reconocer el nuevo evento. Al perder turno recibe `EFFECT_CONSUMED` seguido de `TURN_CHANGED` para el jugador que sí puede actuar. No hay nueva acción cliente ni cambios en los esquemas de Streams.


`requestId=null` indica transición interna. ACK y Pub/Sub pueden llegar en diferente orden: ignorar snapshots antiguos, deduplicar eventos por `(gameId,version)` y sincronizar ante huecos. Si ACK ya instaló el estado de igual versión, la animación del evento aún puede ejecutarse una vez. También puede dibujarse directamente el snapshot final sin animación incremental.

```json
{"type":"ERROR","gameId":"game-123","requestId":"move-1","version":2,"code":"STALE_VERSION","message":"Resynchronize before acting"}
```

Contexto e IDs pueden faltar/sernull sin sesión/partida suficiente. Códigos principales: `UNAUTHENTICATED`, `INVALID_TOKEN`, `TOKEN_EXPIRED`, `AUTH_TIMEOUT`, `INVALID_JSON`, `INVALID_ACTION`, `JOIN_REQUIRED`, `NOT_MEMBER`, `PLAYER_RETIRED`, `BOT_IDENTITY`, `NOT_YOUR_TURN`, `TURN_EXPIRED`, `ALREADY_ROLLED`, `ROLL_REQUIRED`, `DIE_UNAVAILABLE`, `INVENTED_DIE`, `NOT_YOUR_PIECE`, `DOUBLES_REQUIRED`, `STALE_ORIGIN`, `INVALID_DESTINATION`, `OVERSHOOT`, `CELL_FULL`, `CROWNED_PIECE`, `PENDING_CHALLENGE`, `INVALID_CHALLENGE`, `CHALLENGE_EXPIRED`, `INVALID_ANSWER`, `INVALID_CARD_TARGET`, `UNAUTHORIZED_DICE`, `DICE_SELECTION_REQUIRED`, `INVALID_DICE`, `REQUEST_ID_REUSED`, `STALE_VERSION`, `CONCURRENT_UPDATE`, `GAME_FINISHED`, `SERVICE_UNAVAILABLE`.

Un rechazo no confirma cambios. Ante almacenamiento/concurrencia, sincronizar: puede haberse perdido el ACK de una confirmación real. Reintentar **el mismo payload y requestId** permite recuperar esa confirmación sin duplicarla.

## Orden de turnos y compatibilidad

El formato de acciones, ACK, publicaciones, errores y `GameView` se conserva. El snapshot ya incluye el `seat` fijo de cada jugador y `turn.playerId`. `JOIN_GAME`, `SYNC_REQUEST`, reconexiones y el orden de llegada de mensajes no asignan asientos ni escogen el primer turno. Inicia el participante indicado en `creatorPlayerId` por Lobby y `TURN_CHANGED` sigue la secuencia horaria definida por Lobby; dobles repiten el mismo asiento y los retirados se omiten. Godot no envía `creatorPlayerId` ni cambios de asientos por este canal. Estos datos pertenecen exclusivamente al inicio autorizado de Lobby: [INTEGRATIONS.md](INTEGRATIONS.md).

## Consumo mediante Gateway

Godot conecta a `/api/game/ws`, sin token en URL ni necesidad de encabezados personalizados. Envía AUTH con el JWT de Auth y espera AUTHENTICATED antes de JOIN_GAME. El Gateway permite únicamente ese handshake sin Bearer; Game valida el token y rechaza toda acción anterior a AUTH. `ROLL_DICE`, `MOVE_REQUEST`, `TRIVIA_ANSWER`, `CARD_TARGET`, `END_TURN`, ACK, GAME_UPDATED, errores y SYNC_REQUEST mantienen los ejemplos de este documento.

Ejemplo secuencial de mensajes hacia `ws://localhost:8080/api/game/ws`:

```json
{"type":"AUTH","token":"<access-token-de-Auth>"}
{"type":"JOIN_GAME","gameId":"game-123"}
{"type":"ROLL_DICE","gameId":"game-123","requestId":"roll-1","expectedVersion":0}
```

Cada mensaje es un frame JSON separado, enviando el último solo si ese jugador es el activo y la versión coincide. No se incluye `/api/game` dos veces; `/ws/game` es el destino interno y no una ruta pública del Gateway. Usar pings/pongs de la biblioteca WebSocket; el puente cierra clientes sin pong durante30 segundos. Las consultas REST requieren Bearer por separado. Origin del cliente web debe estar permitido en Gateway y Game. Rechazos de handshake (400 query,403 Origin,503 sin instancia) ocurren antes del protocolo JSON.

No existe petición pública para disparar bots: el servidor los ejecuta y publica sus movimientos automáticamente. La validación operativa observó estos eventos por Gateway con un cliente que solo envió AUTH/JOIN_GAME. El recorrido de inicio desde Lobby se prueba en el segundo corte. La ejecución del cliente Godot sigue pendiente.

## Confirmación de animación y relojes (v4-100)

Godot debe actualizar sus rutas a [BOARD.md](BOARD.md). Los sectores siguen empezando en 0/17/34/51; las salidas son 5/22/39/56. Seguros normales: capacidad dos; salidas: cuatro. HOME lleva position=null y progress=-1. PIECE_RELEASED es automático al obtener un par y tiene from=null. No enviar el antiguo MOVE_REQUEST para salir de HOME.

Tras ROLL_DICE, procesar DICE_ROLLED y DICE_ANIMATION_REQUIRED del mismo GAME_UPDATED. Ejemplos completos de los elementos del array events:

```json
{"type":"DICE_ROLLED","data":{"playerId":"42","dice":[4,2]}}
```

```json
{"type":"DICE_ANIMATION_REQUIRED","data":{"rollId":"f81231b1-7a20-440d-8de0-11dc2f7d219b","turnNumber":3,"animationDeadline":1800000010000,"required":true}}
```

El snapshot turn contiene esos datos persistidos además de animationStatus="WAITING", rollPublishedAt y movementStartedAt=null. Mientras WAITING no mostrar una cuenta regresiva de movimiento ni habilitar movimientos. El servidor espera hasta 10 segundos, configurables con GAME_ANIMATION_WAIT_MILLIS. Animar los dados y, al terminar, enviar:

```json
{"type":"DICE_ANIMATION_COMPLETED","gameId":"game-123","requestId":"animation-3-1","expectedVersion":7,"challengeId":"f81231b1-7a20-440d-8de0-11dc2f7d219b"}
```

challengeId reutiliza el campo de correlación existente, aquí con turn.rollId. El UUID identifica inequívocamente la tirada; expectedVersion identifica el estado y el servidor comprueba turno y JWT activo. No enviar playerId. Usar la última versión recibida, incluso si una reconexión la cambió. Una tirada anterior produce STALE_ROLL; un actor ajeno, NOT_YOUR_TURN. Un intento en o después del plazo produce TURN_EXPIRED aunque el scheduler todavía no lo haya cerrado.

El solicitante recibe ACTION_ACK y todos reciben GAME_UPDATED con:

```json
{"type":"MOVEMENT_TIMER_STARTED","data":{"rollId":"f81231b1-7a20-440d-8de0-11dc2f7d219b","startedAt":1800000003000,"deadline":1800000033000}}
```

Mostrar `max(0, deadline - horaServidorEstimada)` en milisegundos, convertir a segundos para la interfaz. El servidor es la autoridad; la latencia visual no extiende el plazo. Una confirmación duplicada mientras siga vigente la misma tirada no reinicia deadline. El reintento idéntico con mismo requestId obtiene duplicate=true. Tras reconectar, SYNC_REQUEST devuelve el mismo plazo; no volver a contar desde 30.

Ejemplo de movimiento sumado, ambos dados pendientes [4,2], origen físico 6 y destino 12:

```json
{"type":"MOVE_REQUEST","gameId":"game-123","requestId":"sum-3-1","expectedVersion":8,"pieceId":"seat-0-piece-0","dieIndex":2,"dieValue":6,"fromPosition":6,"targetPosition":12}
```

Índice 2 consume ambos dados; no se permite si alguno ya se consumió. Los índices 0/1 permiten dos movimientos sucesivos de una misma ficha. Usar legalMoves tras cada ACK o SYNC, nunca asumir que una opción antigua sigue vigente.

Si se agotan los 30 segundos con el dado 1 pendiente:

```json
{"type":"TURN_EXPIRED","data":{"playerId":"42","rollId":"f81231b1-7a20-440d-8de0-11dc2f7d219b","reason":"MOVEMENT_TIMEOUT","discardedDice":[1]}}
```

SOPLAR, cuando hay una sola ficha identificable y ninguna captura satisfactoria:

```json
{"type":"PIECE_BLOWN","data":{"pieceId":"seat-0-piece-0","playerId":"42"}}
```

Si hay varias candidatas, no se devuelve ninguna arbitrariamente:

```json
{"type":"BLOW_AMBIGUOUS","data":{"pieceIds":["seat-0-piece-0","seat-0-piece-1"]}}
```

El lote incluye el siguiente turno y el snapshot completo definitivo:

```json
{"type":"TURN_CHANGED","data":{"playerId":"84","turnNumber":4,"deadline":1800000093000}}
```

Los movimientos aceptados permanecen; los dados descartados no se reutilizan y las fichas sin mover no avanzan automáticamente. deadline del siguiente turno, antes de lanzar, pertenece a la espera de lanzamiento de 60 segundos: no es el plazo de movimiento. Si no se confirma animación, TURN_EXPIRED.reason="ANIMATION_TIMEOUT", se descartan dados y se avanza sin iniciar movimiento ni penalizar oportunidades inaccesibles de esa tirada. La trivia pausa el saldo de movimiento y tiene 15 segundos propios; las cartas de selección conservan su límite por el plazo de movimiento reanudado.

Eventos adicionales:

```json
{"type":"PIECE_RELEASED","data":{"pieceId":"seat-0-piece-0","from":null,"to":5}}
```

```json
{"type":"ROLL_AVAILABLE","data":{"playerId":"42"}}
```

```json
{"type":"CROWN_CHOICE_REQUIRED","data":{"playerId":"42"}}
```

Cada tirada adicional usa otro rollId y exige otra confirmación humana. Si aparece CROWN_CHOICE_REQUIRED, después de confirmar animación elegir una ficha propia de turn.crownEligible que siga BOARD:

```json
{"type":"CROWN_REQUEST","gameId":"game-123","requestId":"crown-3","expectedVersion":9,"pieceId":"seat-0-piece-0"}
```

El servidor publica:

```json
{"type":"PIECE_CROWNED","data":{"pieceId":"seat-0-piece-0","reason":"THREE_DOUBLES"}}
```

No se acepta HOME, CROWNED, rival ni una ficha recién liberada en esa tercera tirada. Tras elegir se reevalúa victoria y se cierra el turno; no hay movimientos con ese tercer par ni cuarta tirada. Si no existe ficha elegible, el turno se cierra al confirmar la animación. No hay penalización por tres pares. Bots confirman internamente sin depender de Godot. La trivia del bot usa p nueva por pregunta y responde por TRIVIA_ANSWER normal; no existe un mensaje de respuesta automáticamente correcta.

Errores nuevos: ANIMATION_PENDING, STALE_ROLL, CROWN_CHOICE_REQUIRED, NO_CROWN_CHOICE, INVALID_CROWN_PIECE y HOME_RELEASE_AUTOMATIC. El layout v4-100 rechaza estados antiguos con UNSUPPORTED_BOARD_LAYOUT; no borra ni migra partidas.

El [ejemplo completo de GAME_UPDATED con todos los campos del snapshot](WS_DICE_EXAMPLE.json) muestra dos jugadores, sus ocho fichas y una tirada pendiente de animación. Los valores son ilustrativos, no credenciales ni evidencia de una sesión real. Se puede usar como fixture de deserialización de Godot.


## Liberación prioritaria y trivia independiente (actualización de pares)

`turn.homeRelease=true` identifica una tirada con liberación prioritaria de HOME. El lote de ROLL_DICE contiene DICE_ROLLED, HOME_RELEASE_RESOLVED, capturas especiales si proceden, PIECE_RELEASED por ficha y DICE_ANIMATION_REQUIRED. Por ejemplo:

```json
{"type":"HOME_RELEASE_RESOLVED","data":{"rollId":"roll-home-1","pieceIds":["seat-0-piece-0","seat-0-piece-1"],"consumed":[true,true]}}
```

Este evento resume una transición atómica: no se envía una acción para cada ficha. Los límites siguen siendo 4 para (1,1), 3 para (6,6), 2 para los demás pares, limitados por HOME disponible. Godot anima la tirada y confirma su rollId con DICE_ANIMATION_COMPLETED dentro de 10 segundos. Con una sola liberada, `consumed=[true,false]`: tras confirmar, MOVEMENT_TIMER_STARTED habilita 30 segundos para el dado 1 si existen movimientos legales. No ofrecer el índice 0, la suma ni fracciones. Con dos o más liberadas, o sin movimiento legal para el sobrante, se consumen ambos dados y se emite ROLL_AVAILABLE sin MOVEMENT_TIMER_STARTED. El tercer par conserva la selección de corona en vez de permitir movimiento.

El nuevo campo `turn.releasedPieceIds` enumera las liberadas en esa tirada. Durante una liberación única, `crownEligible` contiene las fichas que estaban activas antes de lanzar, tanto del recorrido común como del corredor final; el servidor cruza ese conjunto con el estado actual y las reglas de movimiento. Godot debe obtener las opciones de `GAME_STATE.legalMoves` con JOIN_GAME/SYNC_REQUEST después de confirmar; ACTION_ACK/GAME_UPDATED contienen el estado pero no añaden ese array. El servidor rechaza `PIECE_RELEASED_THIS_ROLL` si se intenta mover la recién liberada. Una reconexión conserva rollId, consumo, elegibilidad y deadline. Cada nueva tirada reemplaza rollId, limpia releasedPieceIds y recalcula crownEligible: la ficha liberada puede moverse entonces. No enviar otra tirada mientras rolled=true; esperar ROLL_AVAILABLE y el estado confirmado. Reintentar una confirmación anterior no habilita movimientos.

`turn.consecutiveDoubles` conserva 1/2 entre tiradas, llega a 3 en el premio y se reinicia con no par o cierre. `turn.homeAttempts` es independiente. `turn.thirdDoubleReward` identifica el premio pendiente; `crownEligible` conserva las activas previas. El snapshot es la autoridad para reconexión y deduplicación por versión.

Al caer en trivia con movimiento activo, el mismo lote contiene PIECE_MOVED, MOVEMENT_TIMER_PAUSED y TRIVIA_QUESTION. Ejemplos de eventos:

```json
{"type":"MOVEMENT_TIMER_PAUSED","data":{"rollId":"roll-move-2","remainingMillis":18000}}
{"type":"TRIVIA_QUESTION","data":{"question":{"id":"challenge-1","questionId":"server-question","playerId":"42","prompt":"Pregunta","options":["A","B","C"],"deadline":1800000027000}}}
```

Mostrar **15 segundos** desde la activación usando `trivia.deadline`, sin descontar movimiento. Durante pausa `turn.movementRemainingMillis` y `pausedMovementRollId` están persistidos; `turn.deadline` conserva su valor previo y no debe mostrarse como reloj activo. TRIVIA_ANSWER usa trivia.id como challengeId. No enviar respuestas tardías; el servidor las rechaza aunque no haya ejecutado todavía el scheduler.

Respuesta o vencimiento producen TRIVIA_RESOLVED (`correct=false` si no responde), las consecuencias normales y:

```json
{"type":"MOVEMENT_TIMER_RESUMED","data":{"rollId":"roll-move-2","remainingMillis":18000,"deadline":1800000040000}}
```

Usar el nuevo deadline, sin restaurar 30 segundos. Los campos de pausa vuelven a null. Si terminó el turno, no reactivar un reloj anterior: aplicar siempre el snapshot final del lote. Reconectar no reinicia ninguno de los plazos. Un timeout posterior descarta solo dados pendientes, conserva movimientos y pasa turno sin conceder lanzamiento extra por par sin resolver. Bots usan las mismas reglas y no esperan animación visual.

No se cambian acciones ni rutas. Los nuevos campos/eventos son aditivos; el cliente debe tolerarlos. Las pruebas del servidor usan clientes WebSocket simulados, no acreditan ejecución del cliente Godot real.

Si el scheduler o proceso vuelve después del vencimiento de trivia, el saldo de movimiento se reanuda desde trivia.deadline, no desde la hora tardía del procesamiento. Si también se agotó ese saldo, el mismo commit resuelve trivia y cierra el turno. Un reinicio no regala segundos adicionales.

## Ejemplos ejecutables: liberación parcial y tres pares

Estos ejemplos se copiaron de `PartialReleaseContractTest`, que ejecuta GameService y el handler WebSocket con persistencia y transporte en memoria. No son capturas de una sesión LAN. El test genera `target/partial-release-wire-examples.json` con solicitudes, ACK, publicaciones y sincronizaciones completos. Los bloques de eventos siguientes contienen elementos completos de `GAME_UPDATED.events`, uno por línea; no son tipos nuevos de mensaje. Los tiempos vienen del reloj de prueba.

Para regenerarlos: `mvn -o -Dmaven.repo.local=.build/m2 -Dtest=PartialReleaseContractTest test` desde game-service.


### 1. Una ficha HOME y (5,5)

A sale a 5; el índice 0 se consume. B, C y D estaban en 12, 25 y 39. Tras confirmar, solo pueden usar el índice 1 de valor 5. A no figura entre las opciones.

```json
{"type":"DICE_ROLLED","data":{"playerId":"a","dice":[5,5]}}
{"type":"HOME_RELEASE_RESOLVED","data":{"rollId":"a46de193-babe-4aa4-8d95-d87fb6c022c2","pieceIds":["seat-0-piece-0"],"consumed":[true,false]}}
{"type":"PIECE_RELEASED","data":{"pieceId":"seat-0-piece-0","from":null,"to":5}}
{"type":"DICE_ANIMATION_REQUIRED","data":{"rollId":"a46de193-babe-4aa4-8d95-d87fb6c022c2","turnNumber":1,"animationDeadline":11000,"required":true}}
```

```json
{"type":"MOVEMENT_TIMER_STARTED","data":{"rollId":"a46de193-babe-4aa4-8d95-d87fb6c022c2","startedAt":1000,"deadline":31000}}
```

El array real de `GAME_STATE.legalMoves` después de confirmar es:

```json
[{"pieceId":"seat-0-piece-1","dieIndex":1,"dieValue":5,"from":12,"to":17,"progress":12,"capture":false,"safe":true,"crown":false,"release":false},{"pieceId":"seat-0-piece-2","dieIndex":1,"dieValue":5,"from":25,"to":30,"progress":25,"capture":false,"safe":false,"crown":false,"release":false},{"pieceId":"seat-0-piece-3","dieIndex":1,"dieValue":5,"from":39,"to":44,"progress":39,"capture":false,"safe":false,"crown":false,"release":false}]
```


### 2. Dos fichas HOME y (5,5)

Se liberan dos fichas y se consumen ambos dados. La confirmación habilita otro lanzamiento; no abre movimiento.

```json
{"type":"DICE_ROLLED","data":{"playerId":"a","dice":[5,5]}}
{"type":"HOME_RELEASE_RESOLVED","data":{"rollId":"fc796c8c-a7d5-4506-99ae-6d1b045ef300","pieceIds":["seat-0-piece-0","seat-0-piece-1"],"consumed":[true,true]}}
{"type":"PIECE_RELEASED","data":{"pieceId":"seat-0-piece-0","from":null,"to":5}}
{"type":"PIECE_RELEASED","data":{"pieceId":"seat-0-piece-1","from":null,"to":5}}
{"type":"DICE_ANIMATION_REQUIRED","data":{"rollId":"fc796c8c-a7d5-4506-99ae-6d1b045ef300","turnNumber":1,"animationDeadline":11000,"required":true}}
```

```json
{"type":"ROLL_AVAILABLE","data":{"playerId":"a","rollId":"fc796c8c-a7d5-4506-99ae-6d1b045ef300","extraRollAvailable":true,"consecutiveDoubles":1}}
```


### 3. Una ficha HOME y (1,1)

La capacidad máxima sigue siendo cuatro; al existir una sola HOME se libera una y queda un único dado de valor 1 para las previamente activas.

```json
{"type":"DICE_ROLLED","data":{"playerId":"a","dice":[1,1]}}
{"type":"HOME_RELEASE_RESOLVED","data":{"rollId":"a0e44a99-9189-467b-983d-246c3d9b2ffe","pieceIds":["seat-0-piece-0"],"consumed":[true,false]}}
{"type":"PIECE_RELEASED","data":{"pieceId":"seat-0-piece-0","from":null,"to":5}}
{"type":"DICE_ANIMATION_REQUIRED","data":{"rollId":"a0e44a99-9189-467b-983d-246c3d9b2ffe","turnNumber":1,"animationDeadline":11000,"required":true}}
```

```json
{"type":"MOVEMENT_TIMER_STARTED","data":{"rollId":"a0e44a99-9189-467b-983d-246c3d9b2ffe","startedAt":1000,"deadline":31000}}
```


### 4. Una ficha HOME y (6,6)

La capacidad máxima sigue siendo tres; al existir una sola HOME se libera una y queda un único dado de valor 6 para las previamente activas.

```json
{"type":"DICE_ROLLED","data":{"playerId":"a","dice":[6,6]}}
{"type":"HOME_RELEASE_RESOLVED","data":{"rollId":"87cbec35-bbc8-496f-98f1-ac439bc2ae3f","pieceIds":["seat-0-piece-0"],"consumed":[true,false]}}
{"type":"PIECE_RELEASED","data":{"pieceId":"seat-0-piece-0","from":null,"to":5}}
{"type":"DICE_ANIMATION_REQUIRED","data":{"rollId":"87cbec35-bbc8-496f-98f1-ac439bc2ae3f","turnNumber":1,"animationDeadline":11000,"required":true}}
```

```json
{"type":"MOVEMENT_TIMER_STARTED","data":{"rollId":"87cbec35-bbc8-496f-98f1-ac439bc2ae3f","startedAt":1000,"deadline":31000}}
```


### 5. Liberación sin movimientos legales

Las otras tres fichas están coronadas. La liberada no puede usar el dado pendiente. Después de animar, se descarta ese dado, sin reloj de movimiento ni SOPLAR, y queda disponible otro lanzamiento:

```json
{"type":"DICE_ROLLED","data":{"playerId":"a","dice":[5,5]}}
{"type":"HOME_RELEASE_RESOLVED","data":{"rollId":"aaedd583-da75-4d4f-a0a9-4c4550a47338","pieceIds":["seat-0-piece-0"],"consumed":[true,false]}}
{"type":"PIECE_RELEASED","data":{"pieceId":"seat-0-piece-0","from":null,"to":5}}
{"type":"DICE_ANIMATION_REQUIRED","data":{"rollId":"aaedd583-da75-4d4f-a0a9-4c4550a47338","turnNumber":1,"animationDeadline":11000,"required":true}}
```

```json
{"type":"ROLL_AVAILABLE","data":{"playerId":"a","rollId":"aaedd583-da75-4d4f-a0a9-4c4550a47338","extraRollAvailable":true,"consecutiveDoubles":1}}
```

El estado confirmado tiene consumed=[true,true], rolled=false, extraRollAvailable=true y movementStartedAt=null. No confundirlo con omitir una captura legal: esa omisión conserva SOPLAR.


### 6. Secuencia de tres pares

La primera tirada libera una ficha; el dado restante mueve B de 12 a 17. La segunda usa ambos dados con C de 25 a 35. Después de cada resolución aparece ROLL_AVAILABLE con el contador correspondiente:

```json
{"type":"PIECE_MOVED","data":{"pieceId":"seat-0-piece-1","from":12,"to":17,"dieIndex":1,"status":"BOARD"}}
{"type":"ROLL_AVAILABLE","data":{"playerId":"a","rollId":"4936ec0d-a268-4114-bcbd-a36a0585333d","extraRollAvailable":true,"consecutiveDoubles":1}}
```

```json
{"type":"PIECE_MOVED","data":{"pieceId":"seat-0-piece-2","from":25,"to":35,"dieIndex":2,"status":"BOARD"}}
{"type":"ROLL_AVAILABLE","data":{"playerId":"a","rollId":"e106b558-fd0e-45ea-a830-7ae572357eca","extraRollAvailable":true,"consecutiveDoubles":2}}
```

La tercera tirada sustituye el movimiento por la selección de corona y consume ambos dados:

```json
{"type":"DICE_ROLLED","data":{"playerId":"a","dice":[5,5]}}
{"type":"CROWN_CHOICE_REQUIRED","data":{"playerId":"a"}}
{"type":"DICE_ANIMATION_REQUIRED","data":{"rollId":"33c2a78c-6e6a-43b8-ac9e-2791fb0eae47","turnNumber":1,"animationDeadline":11000,"required":true}}
```

Orden con HOME en el tercer par: DICE_ROLLED → HOME_RELEASE_RESOLVED/capturas de salida/PIECE_RELEASED → efectos de cartas existentes → CROWN_CHOICE_REQUIRED, si hay elegibles → DICE_ANIMATION_REQUIRED. Primero se confirma la animación. La confirmación conserva la selección pendiente sin emitir MOVEMENT_TIMER_STARTED; si no hay elegibles cierra el turno. No se añade una fase de movimiento al premio.


### 7. Coronación seleccionada y cambio de turno

```json
{"type":"CROWN_REQUEST","gameId":"g","requestId":"example-19","expectedVersion":8,"pieceId":"seat-0-piece-3"}
```

```json
{"type":"PIECE_CROWNED","data":{"pieceId":"seat-0-piece-3","reason":"THREE_DOUBLES"}}
{"type":"TURN_CHANGED","data":{"playerId":"b","turnNumber":2,"deadline":61000}}
```

La selección admite fichas propias que ya estaban BOARD antes de lanzar y sigan activas, también en corredor final; excluye HOME, coronadas, rivales y recién liberadas. El cierre reemplaza el turno, reinicia el contador y deja rollId, movementStartedAt y pausas anteriores a null. Si completa las cuatro coronadas, se conserva GAME_FINISHED en lugar de iniciar otro turno. Un reintento idéntico devuelve el ACK original con duplicate=true, sin otra coronación.


### 8. Rechazo de una cuarta tirada

El jugador a ya perdió el turno. Una solicitud nueva con la versión actual se rechaza; una solicitud antigua puede recibir STALE_VERSION antes de validar el turno:

```json
{"type":"ROLL_DICE","gameId":"g","requestId":"fourth-roll","expectedVersion":9}
```

```json
{"type":"ERROR","requestId":"fourth-roll","code":"NOT_YOUR_TURN","message":"Not your turn","gameId":"g","version":9}
```

No hay cuarta tirada, penalización por el tercer par ni reinicio de relojes antiguos. El timeout de un movimiento pendiente descarta el dado y cierra el turno sin lanzamiento adicional, conforme a la política ya vigente.

## Una captura por ficha y lanzamiento

Una ficha que captura mediante un movimiento de dados no puede volver a usar dados de ese rollId. La misma regla se aplica a humanos y bots. El campo aditivo `turn.captureLockedPieceIds` contiene las fichas capturadoras bloqueadas; no son las víctimas. Se persiste atómicamente con el movimiento, el consumo y el recibo. No hay acciones ni eventos nuevos: solo el código ERROR `PIECE_CAPTURE_LOCKED`.

`PIECE_CAPTURED.data.byPieceId` identifica al atacante de una captura ordinaria, y PIECE_MOVED.dieIndex identifica el dado consumido. Usar turn.dice/consumed y `GAME_STATE.legalMoves` para las opciones restantes. No inferir bloqueos solo de PIECE_CAPTURED: las capturas de cartas conservan sus reglas y pueden usar ese evento con cardId. La liberación especial sigue PIECE_RELEASED/HOME_RELEASE_RESOLVED y la exclusión de recién liberadas.

Los ejemplos siguientes proceden de pruebas ejecutadas. La captura/reconexión/rechazo se obtuvo con dos JWT y dos WebSocket reales a través de Gateway y Game; el descarte se obtuvo de GameService con repositorio en memoria. Son fixtures de pruebas, no una sesión Godot ni una validación LAN. Los fragmentos de estado omiten campos irrelevantes; los objetos de events y ERROR se reproducen completos.

### (4,3): captura con 4 y uso del 3 por otra ficha

En esta fixture, A está en 6, B en 20 y las otras dos fichas están coronadas. Hay rivales en 10 y 13. La animación ya fue confirmada. Petición aceptada:

```json
{"type":"MOVE_REQUEST","gameId":"it-222b4125-eeda-415d-9351-27d54fed1442","requestId":"capture","expectedVersion":3,"pieceId":"seat-0-piece-0","dieIndex":0,"dieValue":4,"fromPosition":6,"targetPosition":10}
```

El mismo GAME_UPDATED incluye estos elementos de events, en este orden:

```json
{"type":"PIECE_CAPTURED","data":{"pieceId":"seat-1-piece-0","byPieceId":"seat-0-piece-0"}}
{"type":"PIECE_MOVED","data":{"pieceId":"seat-0-piece-0","from":6,"to":10,"dieIndex":0,"status":"BOARD"}}
```

Extracto real de state.turn después de capturar:

```json
{"playerId":"test-a","rollId":"27a2da30-b4ac-48c2-ac61-0e5f8140d87d","dice":[4,3],"consumed":[true,false],"captureLockedPieceIds":["seat-0-piece-0"],"captureSatisfied":true,"rolled":true,"animationStatus":"COMPLETED","movementStartedAt":1795317138219,"deadline":1795317168219,"extraRollAvailable":false}
```

La marca no reinicia movementStartedAt ni deadline. Aunque A podría alcanzar al rival de 13 sumando tres casillas, ese movimiento está prohibido. Tras reconexión y SYNC_REQUEST, el array completo de legalMoves es:

```json
[{"pieceId":"seat-0-piece-1","dieIndex":1,"dieValue":3,"from":20,"to":23,"progress":18,"capture":false,"safe":false,"crown":false,"release":false}]
```

B puede gastar el dado 1 de valor 3 y avanzar de 20 a 23. No usar una lista de elegibles calculada o enviada por el cliente. Si primero se captura con el dado 1 de valor 3, queda el índice 0 de valor 4 y se aplica la misma exclusión.

### Petición manipulada y reintentos

```json
{"type":"MOVE_REQUEST","gameId":"it-222b4125-eeda-415d-9351-27d54fed1442","requestId":"capture-forged","expectedVersion":6,"pieceId":"seat-0-piece-0","dieIndex":1,"dieValue":3,"fromPosition":10,"targetPosition":13}
```

```json
{"type":"ERROR","requestId":"capture-forged","code":"PIECE_CAPTURE_LOCKED","message":"Capturing piece cannot move again in this roll","gameId":"it-222b4125-eeda-415d-9351-27d54fed1442","version":6}
```

El rechazo no cambia posición, versión ni consumo y no captura al rival de 13. Repetir la captura original con el mismo requestId/payload devuelve ACTION_ACK con duplicate=true, sin otra captura/publicación. Con requestId nuevo se revalida versión y dado. No enviar otro lanzamiento mientras rolled=true: ALREADY_ROLLED conserva también el bloqueo.

### Sin otra ficha que pueda mover: descarte inmediato

En esta segunda fixture las otras tres fichas están coronadas. La única activa captura con el 4. Esta es la publicación completa en cuanto a eventos, sin esperar timeout:

```json
{"type":"PIECE_CAPTURED","data":{"pieceId":"seat-1-piece-0","byPieceId":"seat-0-piece-0"}}
{"type":"PIECE_MOVED","data":{"pieceId":"seat-0-piece-0","from":6,"to":10,"dieIndex":0,"status":"BOARD"}}
{"type":"TURN_CHANGED","data":{"playerId":"b","turnNumber":2,"deadline":61000}}
```

El dado 3 se descarta: no existe un segundo PIECE_MOVED ni un evento DICE_DISCARDED nuevo. El ACK/GAME_UPDATED ya contiene el turno del siguiente jugador, con dice=[] y movementStartedAt=null. No interpretar los dados del turno nuevo como resultados pendientes del anterior. No hay SOPLAR por una opción prohibida ni movimiento automático de la capturadora.

### Pares, temporizadores y alcance

Con pares y sin movimientos legales para otras fichas, el servidor consume el sobrante y publica el ROLL_AVAILABLE existente. Conserva el contador, limpia el bloqueo al aceptar el próximo ROLL_DICE y reconstruye legalMoves; la ficha puede mover o capturar otra vez. El tercer par mantiene CROWN_REQUEST y su cierre obligatorio sin una cuarta tirada.

Una liberación única sigue consumiendo el índice 0 y excluyendo la recién liberada. Si otra ficha captura con el índice 1, se resuelve ese dado y continúa el flujo de pares. Liberaciones múltiples, cartas, seguros, coronación y geometría permanecen iguales.

La captura no reinicia los 30 segundos. Con otras opciones legales queda el mismo deadline; sin opciones se resuelve el lanzamiento inmediatamente. La trivia conserva sus 15 segundos, pausa/reanudación del saldo y bloqueo persistido. Reconectar no vuelve a habilitar la ficha ni reinicia el tiempo.

SOPLAR conserva su semántica vigente: una captura satisface la obligación del turno completo mediante captureSatisfied. No se exige una segunda captura obligatoria después de haberla satisfecho. Las oportunidades anteriores a capturar siguen evaluándose con el historial existente; las opciones actuales se obtienen de legalMoves, que excluye las bloqueadas.

### Persistencia y reproducción

El campo nuevo usa [] al leer un agregado previo que no lo incluya. No se adivina qué ficha capturó antes del despliegue; las nuevas capturas quedan marcadas con esta versión. No cambia boardLayout v4-100 ni el esquema de Streams.

Las pruebas unitarias son CaptureRollLockTest y CaptureRollPersistenceTest. Las integraciones generan target/capture-wire-game-{map}.json y target/capture-wire-gateway-{map}.json; CaptureRollPersistenceTest genera target/capture-no-moves.json. Para comprobar el puente real, construir primero Gateway y establecer GAME_TEST_GATEWAY_JAR a su JAR; ejecutar Game con game.integration=true y GAME_TEST_REDIS_URL/GAME_TEST_MONGO_URI de bases aisladas. Sin GAME_TEST_GATEWAY_JAR, los casos del puente se omiten explícitamente.
