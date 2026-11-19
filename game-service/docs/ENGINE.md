# Motor y reglas definitivas

El creador abre la partida y los asientos rotan en orden horario, omitiendo retirados. Humanos y bots ejecutan el mismo RuleEngine. El dominio conserva geometría, dados, ocupación, capturas, turnos y cartas; la aplicación persiste agregados aislados con CAS y publicación atómica Redis. Adaptadores REST/WS resuelven la identidad JWT. No se altera la SAGA de inicio ni los esquemas de Redis Streams.

Véanse [tablero](BOARD.md), [protocolo Godot](WEBSOCKET.md) y [matriz inicial y evidencia](../../docs/AUDITORIA_REGLAS_DEFINITIVAS.md).

Cuando no queda ninguna ficha BOARD (las coronadas se excluyen), cada tirada sin par consume uno de hasta tres intentos HOME. Tras la confirmación de animación puede repetirse la tirada hasta el tercer fallo. Un par con alguna ficha HOME libera obligatoriamente según la tabla de BOARD y consume ambos dados por completo, incluso si solo libera una ficha. Ningún dado de esa tirada puede mover fichas en tablero. La liberación y captura especial son atómicas; su reintento usa el recibo de la acción original. Hay liberación durante toda la partida. Intentos y pares consecutivos se persisten; reconectar no los reinicia.

Cuando no hay HOME, cada dado se consume por separado, en cualquier orden. La misma ficha puede usar ambos sucesivamente. `dieIndex=2` representa la suma y exige ambos disponibles; consume los dos. No hay rebote: exceder progreso 76 produce OVERSHOOT. Coronadas no se mueven; cuatro coronadas propias producen victoria atómica.

Un par permite otra tirada al agotar movimientos; END_TURN y timeout renuncian al premio. Las tiradas adicionales conservan el número de turno y el cumplimiento de captura, pero los efectos de duración por turno propio conservan el descuento anterior por repetición con par. Tres pares consecutivos habilitan CROWN_REQUEST sobre una ficha propia BOARD que estaba activa antes de liberar HOME en esa tirada. No se corona HOME, rival o coronada; el contador se consume una vez. Si no había elegible no se ofrece selección. El tercer par sustituye el movimiento por el premio, consume sus dados y cierra el turno tras elegir (o tras confirmar si no hay elegibles); nunca se habilita un cuarto lanzamiento. No existe penalización por tres pares.

La captura ordinaria legal por dado o suma crea una obligación para ese turno. Se guardan las fichas que podían capturar y las víctimas/posiciones que acreditan esa oportunidad. Capturar una vez satisface toda la obligación, también durante tiradas adicionales. Los bots priorizan capturas; el servidor permite la omisión humana para evaluar SOPLAR al cerrar, nunca antes de una omisión efectiva. Una carta por sí sola no crea obligación. Los dados consumidos por HOME no generan oportunidades ficticias ni SOPLAR; la captura especial de salida satisface la obligación del turno.

SOPLAR devuelve a HOME la única candidata identificable si no se capturó. Si hay varias, emite BLOW_AMBIGUOUS sin elegir arbitrariamente. Si las víctimas que acreditaban la oportunidad desaparecieron por otra causa, no se aplica. Antes de confirmar animación no hay oportunidad ejercitable ni penalización por esa tirada. Las decisiones de cartas pueden requerir revisión funcional en casos que eliminen oportunidades; la matriz conserva los límites de validación.

## Plazos persistidos

- Espera de lanzamiento: se conserva la protección de 60 segundos para quien aún no lanza, también entre tiradas adicionales. No es el reloj de movimiento.
- Espera de animación: GAME_ANIMATION_WAIT_MILLIS, 10000 por defecto desde la tirada preparada para publicación atómica. movementStartedAt permanece null. Ningún movimiento se acepta durante WAITING.
- Movimiento: GAME_TURN_MILLIS, 30000 por defecto desde confirmación válida del jugador activo para rollId. Una confirmación duplicada no cambia deadline. Los bots inician directamente este plazo solo para dados utilizables en movimiento. Las liberaciones no emiten MOVEMENT_TIMER_STARTED: su confirmación habilita ROLL_AVAILABLE, salvo premio del tercer par.

Cada tirada lleva UUID nuevo, rollPublishedAt, animationDeadline, animationStatus, movementStartedAt y deadline. La confirmación identifica rollId en el campo challengeId del protocolo existente. La versión CAS vincula la petición al estado actual. El actor se obtiene del JWT, nunca del payload. Un requestId repetido recupera el recibo y no ejecuta reglas de nuevo.

Al vencer se conservan movimientos aceptados, se descartan índices pendientes, se evalúa SOPLAR y se avanza sin movimientos automáticos. TURN_EXPIRED informa motivo, tirada y descartes; el mismo commit contiene penalizaciones, TURN_CHANGED y snapshot definitivo. Confirmaciones tardías no reactivan tiradas. La espera de animación vencida no inicia artificialmente el reloj de movimiento.

El scheduler recorre el índice persistido de partidas activas; tras reinicio usa fechas absolutas. Las acciones comparan reloj del servidor antes de mutar. CAS resuelve carreras con scheduler, bots, reconexión y END_TURN: solo una versión puede confirmarse. Los desafíos pendientes se descartan al vencer el turno sin ejecutar nuevos efectos de carta en el cierre.

## Cartas y trivia

Se conserva el catálogo de 32 preguntas y las seis cartas kill-next, kill-chosen, advance-safe, skip-turn, instant-death y doubles-death. No se modifica la siembra ni las preguntas. Las cartas con captura explícita sobre seguros siguen ignorando protección; no entran en corredores rivales. Cada trivia tiene 15000 ms independientes desde su activación. Si hay reloj de movimiento activo, se conserva `movementRemainingMillis = deadline - ahora` y `pausedMovementRollId`; el reloj de movimiento deja de vencer mientras esa trivia esté pendiente. Al responder o vencer, se publica TRIVIA_RESOLVED y se reanuda con exactamente el saldo guardado, sin reiniciar 30 segundos. El vencimiento cuenta como incorrecta y ejecuta las consecuencias habituales una sola vez mediante CAS. Una respuesta en o después de trivia.deadline es CHALLENGE_EXPIRED. Reconectar/reiniciar no altera fechas ni saldo. Nunca se reanuda un rollId o turno obsoleto. Las cartas con selección conservan su límite por el deadline reanudado.

Los nuevos campos del turno son aditivos, con false/null al cargar JSON previo: homeRelease, extraRollAvailable, thirdDoubleReward, movementRemainingMillis y pausedMovementRollId. No cambia boardLayout v4-100 ni el esquema de Streams. Las reglas se aplican en la siguiente tirada/activación de trivia; una tirada previa al despliegue conserva sus dados ya persistidos. No se reinterpreta retroactivamente una liberación antigua sin un marcador fiable.

En cada pregunta el bot obtiene p uniforme en [0,1) de una DoubleSupplier inyectable. TriviaSelection asigna p al índice correcto y (1-p)/(N-1) a cada incorrecto, sin redondeo decimal. Otra muestra selecciona por acumulación de pesos; luego TRIVIA_ANSWER se evalúa por TriviaRules como para humanos. La precisión media esperada es 50 %, no una precisión fija configurada. La selección admite p=1 en pruebas. No se expone la respuesta correcta pendiente por WS.

La compilación o los tests con repositorio en memoria no acreditan integración Redis/MongoDB, recuperación de procesos, Gateway ni Godot real. La evidencia de ejecución se registra separadamente.

Si el scheduler o proceso vuelve después del vencimiento de trivia, el saldo de movimiento se reanuda desde trivia.deadline, no desde la hora tardía del procesamiento. Si también se agotó ese saldo, el mismo commit resuelve trivia y cierra el turno. Un reinicio no regala segundos adicionales.

Si la trivia se resuelve con ambos dados consumidos, no se reanuda una ventana de movimiento: se resuelve la carta pendiente, si existe, y el flujo continúa hacia lanzamiento adicional o cierre.
