# Cartas, trivia, bots y almacenamiento

## MongoDB

Colecciones `game_cards` y `game_questions`. Cada carta incluye ID estable, nombre, descripción, tipo BUFF/DEBUFF, código, parámetros, condiciones, necesidad de objetivo y mapa (`*` significa todas las temáticas). Cada pregunta incluye mapa, texto, opciones e índice correcto; no existe endpoint público de banco de respuestas.

`seed/catalog.json` contiene **4 cartas y 8 preguntas**, cuatro para `classic` y cuatro para `science`. `CatalogSeeder` hace upsert por `_id` con `$setOnInsert`, sin duplicar ni sobrescribir modificaciones administrativas. Agregar un ID nuevo lo carga; cambiar una definición sembrada requiere una migración intencional. No modificar preguntas mientras haya desafíos activos que las referencien.

Las cartas se leen de MongoDB. Los parámetros controlan alcance y duración. `conditions` describe las restricciones que verifica cada código; no es un intérprete de reglas arbitrarias. No hay inventario ni petición cliente para obtener una carta concreta.

## Catálogo y resolución

| Carta / código | Condiciones y efecto |
|---|---|
| `capture-next` / `CAPTURE_NEXT` | Selecciona ficha propia `BOARD`. En su recorrido hacia delante, máximo 6 pasos dentro del tramo común, calcula **el primer rival no protegido**. Es el único rival autorizado para esa ficha. Ignora rivales protegidos; excluye casa, coronados y carriles privados. Devuelve el rival a casa; la propia permanece donde estaba. No consume un dado adicional. |
| `move-safe` / `MOVE_SAFE` | Selecciona ficha propia `BOARD`. Calcula **el primer seguro con espacio** hacia delante hasta el final del tramo común. No admite seguros anteriores, otro recorrido ni fichas de casa. Actualiza posición y progreso al seguro. |
| `dice-limit` / `DICE_MAX_LIMIT` | Fallo de trivia: máximo 3 por dado durante los siguientes 3 turnos completos del afectado. `remainingTurns=3`, `startsAtTurn=turnsStarted+1`. |
| `choose-dice` / `CHOOSE_DICE` | Derecho de un uso para elegir ambos dados del siguiente turno propio. Al comenzar publica `DICE_SELECTION_REQUIRED`. Valida y consume al lanzar. Si ese turno vence sin lanzar, expira. |

Un acierto obtiene buff y un fallo/timeout debuff, elegidos aleatoriamente entre documentos del tipo y mapa. Las cartas de dados se aplican inmediatamente como efectos programados; no cambian los resultados actuales. Reaplicar el mismo código reemplaza la programación para los próximos turnos, sin acumulación. Al finalizar cada turno afectado se descuenta uno, con `EFFECT_UPDATED` o `EFFECT_EXPIRED`. Turnos extra por dobles también cuentan.

**Precedencia:** el límite de3 restringe la elección del buff a `1–3` por dado. Elegir 4–6 se rechaza sin consumir el derecho; sin debuff admite 1–6. No se recortan valores silenciosamente.

Las cartas con objetivo se resuelven inmediatamente mediante `card.choices`, plazo máximo15 segundos y pausa de movimientos ordinarios. El motor recalcula las opciones al recibir `CARD_TARGET`. Una carta imposible o vencida se descarta. Esta espera de selección no permite guardarla para otro turno. Tras ejecutar se elimina `card`; solo permanecen efectos temporales ya aplicados.

## Trivia

Movimiento ordinario a 8,24,40,56 selecciona pregunta temática. `TRIVIA_QUESTION` y snapshot incluyen texto, opciones, challengeId y deadline, **sin `correctIndex`**. Se vincula al jugador activo y pausa acciones ordinarias.

`TRIVIA_ANSWER` verifica identidad, challenge e índice antes del plazo. `TRIVIA_RESOLVED` revela acierto y respuesta correcta solo después de resolver; luego aplica o descarta carta. Timeout cuenta como fallo. Al vencer el turno se resuelven pendientes y avanza sin premio por dobles. Las preguntas pueden repetirse; no se agregó sistema de exclusión. Si el catálogo deja de estar disponible durante un timeout, el motor resuelve el fallo sin revelar una respuesta desconocida, descarta la carta con motivo `CATALOG_UNAVAILABLE` y permite avanzar el turno; no mantiene al jugador bloqueado esperando MongoDB.

## Bot estándar

`BotPolicy` consulta `RuleEngine.legalMoves`, genera el mismo `Action` y pasa por el mismo motor y CAS. Solo el ejecutor interno controla IDs marcados bot; conexiones humanas no pueden controlarlos.

Puntuación: progreso+10, corona+1000, captura+400, seguro+250, liberación+140; exposición a un rival entre 1 y6 posiciones detrás resta100. Se eligen aleatoriamente opciones dentro de 180 puntos de la mejor, con pesos positivos crecientes según puntuación. Es una heurística local, sin búsqueda de futuros turnos.

Reevalúa después de cada dado confirmado, pudiendo combinar captura con otro dado y seguridad de otra ficha. No mueve siempre la primera. Si no hay movimientos, el motor avanza; el fallback termina turno. Para cartas elige solo opciones autorizadas; para buff de dados elige los máximos permitidos. Trivia simula acierto con probabilidad configurable 0.65 y, en fallo, elige otra opción. El servidor conoce la respuesta para simular la probabilidad, pero no la publica antes de resolver.

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

Durabilidad ante pérdida/reinicio de **Redis** depende de AOF/RDB externos. El volumen de Compose no garantiza guardar la última acción; no se modificó esa configuración. No hay TTL de partidas finalizadas ni copia histórica en MongoDB. La futura retención debe definirse explícitamente. Los scripts están diseñados para Redis standalone existente, no para Cluster.

## Desconexiones

Cierre WebSocket elimina lease. Las sesiones abiertas renuevan cada 5 segundos un lease de 15 segundos. Ping/Pong detecta inactividad; tras 30 segundos sin Pong se cierra. Expirar JWT también cierra y exige renovar mediante Auth y reconectar.

Una sesión válida en cualquier instancia basta para considerar conectado al participante. La caída de un proceso deja vencer leases. Sin sesiones, el scheduler inicia gracia de 60 segundos y publica `PLAYER_DISCONNECTED` con `retireAt`. Reconectar cancela mediante actualización versionada y publica `PLAYER_RECONNECTED`.

Al vencer la gracia aplica retiro idempotente, devuelve fichas a casa, elimina efectos y omite turnos. Quien nunca conectó recibe gracia desde el primer mantenimiento. No se reincorpora un retirado; los bots no necesitan WebSocket. No se confunde cierre de una de varias sesiones con retiro inmediato.

## Responsabilidades tras la refactorización

`RuleEngine` coordina validaciones, movimientos, capturas, coronación y victoria, sin transportes ni almacenamiento. `DiceRules` concentra autorización, rango y consumo de los dos dados y comparte el cálculo de efectos con el bot. `TurnManager` gestiona inicio/finalización, plazos y duración de efectos propios; `TurnOrder` selecciona por asiento horario, nunca por índice del array. `CardRules` calcula objetivos y aplica cartas inmediatas; `TriviaRules` conserva el ciclo de preguntas y recompensa sobre el motor existente.

`BotPolicy` conserva puntuaciones y selección ponderada, con métodos separados para trivia, cartas, lanzamiento y movimiento. `GameService` valida acceso, conserva recibos y confirma mediante el repositorio CAS. El handler WebSocket autentica y despacha; delega la autorización de participantes al servicio. `GameScheduler` separa mantenimiento por partida y cambios de presencia. Las interfaces de persistencia y JWT, el runner de bots y las configuraciones operativas se conservan. No se añadió un framework de reglas ni una nueva jerarquía de servicios. Detalles y pruebas en [CLEAN_CODE.md](CLEAN_CODE.md).
