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

Una reconexión puede cancelar desconexión pendiente, incrementando versión y publicando el evento. También existe consulta REST con Bearer. El mapa de96 casillas se obtiene una vez mediante `GET /api/game/board`, no en cada jugada.

## Acciones

Todas las mutaciones requieren `type`, `gameId`, `requestId` único y `expectedVersion`. IDs de partida/solicitud:1–80 caracteres alfanuméricos, guion o guion bajo. La versión debe ser la del último estado confirmado. Esperar ACK/estado antes de construir otra acción; no enviar identidad en el payload.

Lanzar:

```json
{"type":"ROLL_DICE","gameId":"game-123","requestId":"roll-1","expectedVersion":0}
```

El servidor genera los resultados y publica `DICE_ROLLED`, por ejemplo `{"type":"DICE_ROLLED","data":{"playerId":"42","dice":[4,2]}}`, dentro de `GAME_UPDATED`. Si no hay movimientos legales, ese mismo lote incluye transición de turno.

Solo si `diceSelectionRequired=true`, incluir `"chosenDice":[2,3]`. El servidor verifica el efecto y `maxDieValue`; fuera de ese derecho rechaza `UNAUTHORIZED_DICE`.

Mover (ejemplo orientativo sujeto a origen/recorrido reales):

```json
{
  "type":"MOVE_REQUEST","gameId":"game-123","requestId":"move-1","expectedVersion":1,
  "pieceId":"seat-0-piece-0","dieIndex":0,"dieValue":4,"fromPosition":24,"targetPosition":28
}
```

Para salir de casa, `fromPosition=null` y destino=salida asignada. El servidor recalcula, nunca acepta posición por el solo envío. `dieIndex` identifica0 o1; incluso dados iguales se consumen por separado. Puede usar ambos en cualquier orden, con nueva acción/versión por movimiento. `legalMoves` devuelve pieceId, dieIndex, dieValue, from, to, progress, capture, safe, crown y release.

Responder trivia (índice base0):

```json
{"type":"TRIVIA_ANSWER","gameId":"game-123","requestId":"answer-1","expectedVersion":2,"challengeId":"<trivia.id>","answerIndex":1}
```

El challenge vincula la pregunta del servidor; no confiar en questionId enviado. Antes de resolver no se revela `correctIndex`.

Resolver carta:

```json
{"type":"CARD_TARGET","gameId":"game-123","requestId":"card-1","expectedVersion":3,"challengeId":"<card.id>","pieceId":"seat-0-piece-0","targetPieceId":"seat-1-piece-2","targetPosition":28}
```

Copiar una opción de `card.choices`: pieceId, targetPieceId, targetPlayerId y targetPosition. Los campos irrelevantes se omiten o son null. Refugio usa ficha propia/destino; captura además ficha rival. El motor recalcula opciones. `card` incluye nombre, descripción y código para mostrar la selección inmediata al usuario.

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

Eventos: `GAME_STARTED`, `DICE_ROLLED`, `DICE_SELECTION_REQUIRED`, `PIECE_MOVED`, `PIECE_CAPTURED`, `TURN_CHANGED`, `TURN_EXPIRED`, `TRIVIA_QUESTION`, `TRIVIA_RESOLVED`, `CARD_TARGET_REQUIRED`, `CARD_DISCARDED`, `EFFECT_APPLIED`, `EFFECT_CONSUMED`, `EFFECT_UPDATED`, `EFFECT_EXPIRED`, `PLAYER_DISCONNECTED`, `PLAYER_RECONNECTED`, `PLAYER_RETIRED`, `GAME_FINISHED`, `GAME_ABANDONED`.

`requestId=null` indica transición interna. ACK y Pub/Sub pueden llegar en diferente orden: ignorar snapshots antiguos, deduplicar eventos por `(gameId,version)` y sincronizar ante huecos. Si ACK ya instaló el estado de igual versión, la animación del evento aún puede ejecutarse una vez. También puede dibujarse directamente el snapshot final sin animación incremental.

```json
{"type":"ERROR","gameId":"game-123","requestId":"move-1","version":2,"code":"STALE_VERSION","message":"Resynchronize before acting"}
```

Contexto e IDs pueden faltar/sernull sin sesión/partida suficiente. Códigos principales: `UNAUTHENTICATED`, `INVALID_TOKEN`, `TOKEN_EXPIRED`, `AUTH_TIMEOUT`, `INVALID_JSON`, `INVALID_ACTION`, `JOIN_REQUIRED`, `NOT_MEMBER`, `PLAYER_RETIRED`, `BOT_IDENTITY`, `NOT_YOUR_TURN`, `TURN_EXPIRED`, `ALREADY_ROLLED`, `ROLL_REQUIRED`, `DIE_UNAVAILABLE`, `INVENTED_DIE`, `NOT_YOUR_PIECE`, `DOUBLES_REQUIRED`, `STALE_ORIGIN`, `INVALID_DESTINATION`, `OVERSHOOT`, `CELL_FULL`, `CROWNED_PIECE`, `PENDING_CHALLENGE`, `INVALID_CHALLENGE`, `CHALLENGE_EXPIRED`, `INVALID_ANSWER`, `INVALID_CARD_TARGET`, `UNAUTHORIZED_DICE`, `DICE_SELECTION_REQUIRED`, `INVALID_DICE`, `REQUEST_ID_REUSED`, `STALE_VERSION`, `CONCURRENT_UPDATE`, `GAME_FINISHED`, `SERVICE_UNAVAILABLE`.

Un rechazo no confirma cambios. Ante almacenamiento/concurrencia, sincronizar: puede haberse perdido el ACK de una confirmación real. Reintentar **el mismo payload y requestId** permite recuperar esa confirmación sin duplicarla.

## Orden de turnos y compatibilidad

El formato de acciones, ACK, publicaciones, errores y `GameView` se conserva. El snapshot ya incluye el `seat` fijo de cada jugador y `turn.playerId`. `JOIN_GAME`, `SYNC_REQUEST`, reconexiones y el orden de llegada de mensajes no asignan asientos ni escogen el primer turno. El creador original inicia y `TURN_CHANGED` sigue la secuencia horaria definida por Lobby; dobles repiten el mismo asiento y los retirados se omiten. Godot no envía `creatorPlayerId` ni cambios de asientos por este canal. Estos datos pertenecen exclusivamente al inicio autorizado de Lobby: [INTEGRATIONS.md](INTEGRATIONS.md).

## Consumo mediante Gateway

Godot conecta a `/api/game/ws`, sin token en URL ni necesidad de encabezados personalizados. Envía AUTH con el JWT de Auth y espera AUTHENTICATED antes de JOIN_GAME. El Gateway permite únicamente ese handshake sin Bearer; Game valida el token y rechaza toda acción anterior a AUTH. `ROLL_DICE`, `MOVE_REQUEST`, `TRIVIA_ANSWER`, `CARD_TARGET`, `END_TURN`, ACK, GAME_UPDATED, errores y SYNC_REQUEST mantienen los ejemplos de este documento.

Ejemplo secuencial de mensajes hacia `ws://localhost:8080/api/game/ws`:

```json
{"type":"AUTH","token":"<access-token-de-Auth>"}
{"type":"JOIN_GAME","gameId":"game-123"}
{"type":"ROLL_DICE","gameId":"game-123","requestId":"roll-1","expectedVersion":0}
```

Cada mensaje es un frame JSON separado, enviando el último solo si ese jugador es el activo y la versión coincide. No se incluye `/api/game` dos veces; `/ws/game` es el destino interno y no una ruta pública del Gateway. Usar pings/pongs de la biblioteca WebSocket; el puente cierra clientes sin pong durante30 segundos. Las consultas REST requieren Bearer por separado. Origin del cliente web debe estar permitido en Gateway y Game. Rechazos de handshake (400 query,403 Origin,503 sin instancia) ocurren antes del protocolo JSON.

No existe petición pública para disparar bots: el servidor los ejecuta y publica sus movimientos automáticamente. La validación operativa observó estos eventos por Gateway con un cliente que solo envió AUTH/JOIN_GAME. Inicio de Lobby real y consumo desde una ejecución de Godot siguen pendientes.
