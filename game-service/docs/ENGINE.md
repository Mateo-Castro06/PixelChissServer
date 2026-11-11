# Cartas, trivia, bots y almacenamiento

## MongoDB

Colecciones `game_cards` y `game_questions`. Cada carta incluye ID estable, nombre, descripción, tipo BUFF/DEBUFF, código, parámetros, condiciones, necesidad de objetivo y mapa (`*` significa todas las temáticas). Cada pregunta incluye mapa, texto, opciones e índice correcto; no existe endpoint público de banco de respuestas.

`seed/catalog.json` contiene **6 cartas y 32 preguntas**: 16 de cultura general en `classic` y 16 de ciencia en `science`. `CatalogSeeder` inserta por `_id` con `$setOnInsert`. Para bases existentes usa la [migración con copia previa](../../docs/GUIA_ACTUALIZACION.md#migrar-mongodb). Las definiciones retiradas de `legacy-challenges.json` nunca se siembran ni se sortean; solo permiten resolver por ID un desafío pendiente antes de actualizar.

## Cartas y resolución

| Carta / código | Tipo | Condiciones y efecto |
|---|---|---|
| `kill-next` / `CAPTURE_NEAREST_CELL` | BUFF | Para cada ficha propia en tablero, busca hacia delante hasta el fin de su recorrido común. Gana la menor distancia de progreso; si empatan, el menor número de ficha propia. Captura **todas** las fichas rivales de esa casilla común, incluso en seguro. La ficha atacante no se mueve. |
| `kill-chosen` / `CAPTURE_CHOSEN_CELL` | BUFF | Elige una casilla común ocupada por rivales mediante `targetPosition`. Devuelve todas las fichas rivales de esa casilla a casa, incluso en seguro. No afecta fichas propias ni carriles privados. |
| `advance-safe` / `MOVE_SAFE` | BUFF | Elige una ficha propia en tablero. Avanza al primer seguro con cupo por delante en su ruta común. No retrocede ni sale del tramo final. |
| `skip-turn` / `SKIP_NEXT_TURN` | DEBUFF | Consume una vez el siguiente turno que se le asigne al afectado, incluido un turno extra si corresponde. El servidor emite `EFFECT_CONSUMED` y pasa al siguiente asiento activo. El número de turno cuenta el turno omitido. |
| `instant-death` / `LOSE_LEADING_PIECE` | DEBUFF | Devuelve a casa una ficha propia no coronada con el mayor progreso; en empate, el menor número. Puede afectar una ficha propia en el carril final. |
| `doubles-death` / `DOUBLES_PENALTY` | DEBUFF | Desde que se aplica hasta el fin del siguiente turno asignado al afectado, cada tirada con **dos dados iguales** de cualquier participante devuelve una ficha propia del afectado a casa. Incluye sus propios dobles. Se elige uniformemente entre fichas en tablero mediante `DiceSource`; si no hay candidatas no se hace sorteo. El efecto expira también si ese turno vence o se omite. |

Acierto: BUFF; fallo o timeout: DEBUFF. Las cartas se sortean entre las del tipo y mapa. Los dos efectos programados no se acumulan al reaplicarlos. No hay inventario ni acción cliente para solicitar un premio concreto.

La selección se realiza con las opciones de `card.choices` y se revalida al recibir `CARD_TARGET`. Si no hay objetivo válido, se publica `CARD_DISCARDED`; un objetivo pendiente también se descarta al vencer su plazo. Las devoluciones a casa dejan la ficha en `HOME`, con `position=null` y `progress=-1`. Las cartas no consumen dados adicionales. Ver [protocolo](WEBSOCKET.md).

## Trivia

Movimiento ordinario a 8,24,40,56 selecciona pregunta temática. `TRIVIA_QUESTION` y snapshot incluyen texto, opciones, challengeId y deadline, **sin `correctIndex`**. Se vincula al jugador activo y pausa acciones ordinarias.

`TRIVIA_ANSWER` verifica identidad, challenge e índice antes del plazo. `TRIVIA_RESOLVED` revela acierto y respuesta correcta solo después de resolver; luego aplica o descarta carta. Timeout cuenta como fallo. Al vencer el turno se resuelven pendientes y avanza sin premio por dobles. Las preguntas pueden repetirse; no se agregó sistema de exclusión. Si el catálogo deja de estar disponible durante un timeout, el motor resuelve el fallo sin revelar una respuesta desconocida, descarta la carta con motivo `CATALOG_UNAVAILABLE` y permite avanzar el turno; no mantiene al jugador bloqueado esperando MongoDB.

## Bot estándar

`BotPolicy` consulta `RuleEngine.legalMoves`, genera el mismo `Action` y pasa por el mismo motor y CAS. Solo el ejecutor interno controla IDs marcados bot; conexiones humanas no pueden controlarlos.

Puntuación: progreso+10, corona+1000, captura+400, seguro+250, liberación+140; exposición a un rival entre 1 y6 posiciones detrás resta100. Se eligen aleatoriamente opciones dentro de 180 puntos de la mejor, con pesos positivos crecientes según puntuación. Es una heurística local, sin búsqueda de futuros turnos.

Reevalúa después de cada dado confirmado, pudiendo combinar captura con otro dado y seguridad de otra ficha. No mueve siempre la primera. Si no hay movimientos, el motor avanza; el fallback termina turno. Para cartas elige solo opciones autorizadas; si recupera un efecto de elección de dados de una partida anterior, elige los máximos permitidos. Trivia simula acierto con probabilidad configurable 0.65 y, en fallo, elige otra opción. El servidor conoce la respuesta para simular la probabilidad, pero no la publica antes de resolver.

## Estado y concurrencia

Redis es la **única fuente autoritativa de partida**: jugadores, cuatro fichas por jugador, posición, turno, dados, consumos, efectos, desafíos, ganador y recibos. MongoDB no duplica ese estado.

| Clave | Uso |
|---|---|
| Hash `pixelchiss:game:state:{gameId}` | JSON completo y versión |
| Set `pixelchiss:game:active` | Partidas que necesitan mantenimiento |
| ZSet `pixelchiss:game:presence:{gameId}:{playerId}` | Leases por conexión |
| Pub/Sub `pixelchiss:game:events` | Eventos entre instancias |

El repositorio devuelve copias deserializadas. El motor modifica una copia; Lua verifica versión, escribe, actualiza índice y publica atómicamente. Rechazos y carreras perdidas no alteran estado. La garantía no depende de locks locales.

Se guardan los últimos 256 recibos por actor/requestId, con hash del payload y versión confirmada. Reintentos idénticos recuperan ACK sin ejecución ni publicación; reutilizar ID con otro contenido falla. Fuera de esa ventana, la versión antigua impide repetir la mutación. Cada nueva acción usa ID nuevo.

Al reiniciar Game se recuperan snapshots, deadlines e índice desde Redis. Pub/Sub no es un historial durable: Godot detecta huecos de versión y pide sincronización. Los eventos incluyen snapshot público compacto, sin el mapa de96 casillas.

Durabilidad ante pérdida/reinicio de **Redis** depende de AOF/RDB externos. Compose usa AOF cada segundo para instalaciones nuevas. Si el volumen existente contiene solo RDB, el arranque conserva ese formato hasta la [conversión controlada](../../docs/GUIA_ACTUALIZACION.md#conservar-redis-al-activar-aof). Ninguno de los dos garantiza conservar la última acción tras un fallo abrupto. No hay TTL de partidas finalizadas ni copia histórica en MongoDB. La futura retención debe definirse explícitamente. Los scripts están diseñados para Redis standalone existente, no para Cluster.

## Desconexiones

Cierre WebSocket elimina lease. Las sesiones abiertas renuevan cada 5 segundos un lease de 15 segundos. Ping/Pong detecta inactividad; tras 30 segundos sin Pong se cierra. Expirar JWT también cierra y exige renovar mediante Auth y reconectar.

Una sesión válida en cualquier instancia basta para considerar conectado al participante. La caída de un proceso deja vencer leases. Sin sesiones, el scheduler inicia gracia de 60 segundos y publica `PLAYER_DISCONNECTED` con `retireAt`. Reconectar cancela mediante actualización versionada y publica `PLAYER_RECONNECTED`.

Al vencer la gracia aplica retiro idempotente, devuelve fichas a casa, elimina efectos y omite turnos. Quien nunca conectó recibe gracia desde el primer mantenimiento. No se reincorpora un retirado; los bots no necesitan WebSocket. No se confunde cierre de una de varias sesiones con retiro inmediato.

## Responsabilidades tras la refactorización

`RuleEngine` coordina validaciones, movimientos, capturas, coronación y victoria, sin transportes ni almacenamiento. `DiceRules` concentra autorización, rango y consumo de los dos dados y comparte el cálculo de efectos con el bot. `TurnManager` gestiona inicio/finalización, plazos y duración de efectos propios; `TurnOrder` selecciona por asiento horario, nunca por índice del array. `CardRules` calcula objetivos y aplica cartas inmediatas; `TriviaRules` conserva el ciclo de preguntas y recompensa sobre el motor existente.

`BotPolicy` conserva puntuaciones y selección ponderada, con métodos separados para trivia, cartas, lanzamiento y movimiento. `GameService` valida acceso, conserva recibos y confirma mediante el repositorio CAS. El handler WebSocket autentica y despacha; delega la autorización de participantes al servicio. `GameScheduler` separa mantenimiento por partida y cambios de presencia. Las interfaces de persistencia y JWT, el runner de bots y las configuraciones operativas se conservan. No se añadió un framework de reglas ni una nueva jerarquía de servicios. Detalles y pruebas en [CLEAN_CODE.md](CLEAN_CODE.md).
