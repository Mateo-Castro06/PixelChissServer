# Auditoría de reglas definitivas


## Actualización de pares y temporizadores — 2026-11-19

Esta revisión parte de `b496033` en main, sincronizado con origin/main; el único archivo ajeno detectado fue el directorio no seguido demo-rum/, que se preserva. Sustituye las afirmaciones históricas de abajo sobre liberación sin consumo y plazos de trivia limitados por movimiento.

| Área | Antes de esta revisión | Resultado |
|---|---|---|
| Par con HOME | Liberaba 4/3/2, pero dejaba dados disponibles para avance | Liberación obligatoria; consume ambos incluso si sale una sola ficha; no hay movimientos ordinarios ni SOPLAR ficticio |
| Par sin HOME | Dados individuales/suma y repetición | Se conserva; otro UUID y confirmación por cada tirada |
| Lanzamiento adicional | Disponible al agotar movimientos | Disponible tras resolver movimiento o confirmar liberación; persiste extraRollAvailable; no se concede por dados vencidos |
| Tres pares | Selección CROWN_REQUEST de activa previa, pero podía continuar hacia cuarta tirada | Conserva selección humana y política del bot; consume dados, corona solo propia activa previa y cierra sin cuarta tirada. Nunca penaliza por tres pares |
| HOME/capturas/tablero | Tres intentos HOME, captura múltiple, seguros/salidas, corredores y corona exacta | Se conservan; captura especial de salida satisface obligación del turno |
| Movimiento | 30 s después de confirmación, espera de animación 10 s | Se conserva; liberar HOME no abre movimiento; confirmación repetida no reinicia plazo |
| Trivia | Hasta 15 s limitados por movimiento; ambos relojes corrían | 15 s independientes; pausa saldo, reanuda exactamente ese saldo; no acepta respuesta tardía |
| Recuperación | Fechas absolutas y CAS | Persiste también saldo/rollId pausado; timeout de trivia reanuda desde su deadline, incluso tras caída. Si venció el saldo, cierra en el mismo commit |

Inicio y vencimiento de animación/movimiento se calculan con una sola lectura del reloj; una prueba con reloj que avanza por lectura verifica exactamente 10000/30000 ms.

Si la trivia se activa al consumir el último dado, no se publica una reanudación de movimiento obsoleta. Las cartas de selección conservan sus reglas y su propio límite; no se cambia el catálogo ni la trivia probabilística. El efecto explícito de la carta doubles-death sigue siendo distinto del premio de tres pares y permanece intacto.

El timeout de movimiento conserva jugadas confirmadas, descarta dados pendientes, evalúa SOPLAR sobre oportunidades reales omitidas y avanza atómicamente. No concede tirada adicional a un par sin resolver. La liberación, el premio, las respuestas y los vencimientos utilizan los recibos/CAS existentes; un reintento no duplica fichas, dados ni premios.

### Archivos de esta revisión

Dominio: GameState.Turn agrega homeRelease, extraRollAvailable, thirdDoubleReward, movementRemainingMillis y pausedMovementRollId. RuleEngine controla consumo exclusivo, tercer premio y relojes; TriviaRules controla pausa, plazo independiente y recuperación. No hay cambios arquitectónicos ni nuevos servicios. GameService, RedisGameRepository, BotPolicy, JWT y la SAGA conservan sus contratos; las opciones del bot cambian a través del mismo dominio.

Pruebas nuevas: PairsAndTriviaTimersTest y PairsPersistenceTest. Se adaptan los escenarios de movimiento de EngineFixture, RuleEngineTest, CardsTriviaTest, BotTest, LapEntryTest, TurnDeadlineServiceTest, TurnOrderTest e InfrastructureIntegrationTest para que no dependan de reutilizar dados de HOME. Nuevo probe infrastructure/tests/pairs_and_trivia.py; los probes two_humans.py y definitive_timeouts.py se ejecutan también.

Documentación: README, ENGINE, WEBSOCKET, BOARD, WS_DICE_EXAMPLE, manual, guía, arquitectura y ADR-004. Los nuevos campos son aditivos; no cambia boardLayout v4-100 ni Redis Streams. Una tirada/trivia ya persistida antes del despliegue conserva su estado: no se infiere retroactivamente una liberación sin marcador fiable. Las reglas nuevas rigen desde la siguiente activación sin borrar partidas.

### Godot

Procesar HOME_RELEASE_RESOLVED y PIECE_RELEASED; con homeRelease y consumed=[true,true] no ofrecer movimientos. Confirmar la animación del rollId y esperar ROLL_AVAILABLE o la selección de coronación. Mostrar consecutiveDoubles y extraRollAvailable desde snapshot; el tercer premio no habilita cuarta tirada. Durante trivia mostrar trivia.deadline y congelar movimiento en movementRemainingMillis; MOVEMENT_TIMER_RESUMED aporta deadline actualizado. TURN_EXPIRED descarta dados pendientes; siempre aplicar el snapshot final del lote. No cambian rutas ni acciones.

### Verificación ejecutada de esta revisión

| Suite | Ejecutadas | Fallos | Errores | Omitidas |
|---|---:|---:|---:|---:|
| Game con Redis/Mongo/HTTP/WebSocket reales | 257 | 0 | 0 | 0 |
| Auth con PostgreSQL aislado | 75 | 0 | 0 | 0 |
| Gateway | 12 | 0 | 0 | 0 |
| Eureka | 1 | 0 | 0 | 0 |
| Lobby con EDA/SAGA reales | 13 | 0 | 0 | 0 |

Total: **358 pruebas**. Además, **16 resultados de probes**: ocho de pares/trivia/reinicio pausado, dos de dos humanos e inicio desde Lobby y seis de bots/vencimientos/reinicio, todos con ambos mapas y dos JWT por Gateway. Se verificaron respuestas tardías, confirmaciones duplicadas/antiguas, pérdida de dados pendientes, conservación de movimientos y SOPLAR real. No se declara ejecución de Godot.

La imagen final se reconstruyó y desplegó en el proyecto docker; coincide con la imagen utilizada por las pruebas aisladas. Los diez servicios de desarrollo permanecen activos y sus healthchecks disponibles están saludables. Se comprobó Redis PONG, Mongo ping=1, PostgreSQL aceptando conexiones, servicios registrados UP en Eureka, REST protegido por JWT y puente WS de desarrollo alcanzando Game y rechazando token inválido. Consumidores de Streams de desarrollo/aislado: pendientes=0 y lag=0. El entorno aislado se detiene después de las pruebas conservando volúmenes; desarrollo queda activo.

Los primeros intentos no se cuentan como aprobados: hubo escenarios de pruebas que asumían reutilización de dados HOME, una carrera con el consumidor aislado de Lobby y dependencias del entorno del probe incompletas. Tras recrear contenedores se observaron 500/502/503 transitorios por propagación de descubrimiento; se esperó disponibilidad antes de repetir. Un probe detectó 1 ms de diferencia entre dos lecturas del reloj; se corrigió usando una sola muestra, con regresión específica y repetición satisfactoria de toda la suite y probes finales. No quedan verificaciones críticas fallidas.

### Reproducción y límites

Ejecutar suites con infraestructura aislada (16379/17017/55432), como se describe abajo. El nuevo probe exige PROOF_ISOLATED=1, PROOF_REDIS_URL del Redis aislado, dos refresh sintéticos y PROOF_LOBBY_TOKEN; PROOF_RESTART_PAUSED=1 añade recuperación real del proceso con trivias pausadas en ambos mapas. Las fixtures solo afectan IDs nuevos proof-pairs-*. Las credenciales no forman parte del repositorio. El consumidor de Lobby aislado debe detenerse durante su suite de recuperación SAGA y levantarse después: de otro modo puede resolver un caso antes de que la prueba inspeccione el estado intermedio.

No se ejecutó Godot real; se usan clientes WebSocket simulados con JWT distintos. No se sobrescribió .env ni se eliminaron datos o volúmenes. Desarrollo usa el proyecto Compose existente docker; el otro proyecto de la máquina no se modifica.

Commit funcional verificado: `749f457bcc81ebafdaaec350d21ce943b54150c4`. Los resultados actuales se registran en [pares-temporizadores-validation.json](pares-temporizadores-validation.json). La evidencia de la sección siguiente pertenece al despliegue anterior.

---

## Auditoría histórica del despliegue anterior

Inspección inicial: main sincronizado con origin/main; único cambio previo: demo-rum/ sin seguimiento. No se incluye en commits. Esta matriz se escribe antes de implementar; «pendiente» no significa verificado.

| Regla | Implementación actual inspeccionada | Comportamiento esperado | Estado inicial | Acción necesaria |
|---|---|---|---|---|
| Tablero | 100 físicas, 68 comunes, 32 finales, doce seguros | Conservar | Cumple por inspección | Probar rutas |
| Salidas y recorrido | Inicio seat*17; retorno progreso 68 y finales 69–76 | Salidas 5/22/39/56 y acceso documentado | No cumple | Desplazar ruta completa y versionar |
| HOME | home(): null/-1/HOME | Conservar en todas las devoluciones | Cumple por inspección | Regresión |
| Inicio y orden | Creador y asientos horarios; identidad externa autenticada | Conservar | Cumple por inspección | Regresión |
| Tres intentos | No existe contador | Hasta tres fallos si no hay fichas activas | Falta | Persistir por turno |
| Liberación | Una ficha por dado | 4/3/2 según par, atómica | No cumple | Operación de liberación |
| Normales/cartas | Fichas propias bloquean; captura no filtra propietario | Apilamiento propio y captura múltiple rival | No cumple | Corregir ocupación/captura |
| Seguros | Capacidad dos, protegidos | Conservar seguros normales | Parcial | Diferenciar salidas |
| Salidas especiales | No existen | Capacidad cuatro y captura solo al liberar HOME | Falta | Implementar |
| Dados | Consumo por índice, sin suma | Individuales y suma sin reutilización | Parcial | Índice de suma y validación |
| Captura obligatoria/SOPLAR | No existen | Una captura satisface; penalizar omisión inequívoca | Falta | Persistir candidatos y cumplimiento |
| Tres pares | Solo turno extra | Coronar activa, nunca HOME | Falta | Contador persistido y elección |
| Corredores/victoria | Exclusivos, exactos, cuatro coronadas | Conservar | Cumple por inspección | Regresión |
| Trivia bot | Precisión fija configurada | p nueva por pregunta y distribución ponderada | No cumple | Aleatoriedad inyectable |
| Temporizador | Plazo al iniciar turno | 30 s tras confirmación, espera separada 10 s | No cumple | Estado de tirada, confirmación y expiración |
| Timeout | tick avanza; no informa dados ni SOPLAR | Conservar movimientos, descartar dados, cierre CAS | Parcial | Cierre explícito y eventos |
| Idempotencia | Recibos y commit CAS Redis con publicación atómica | Conservar y cubrir nuevas acciones | Base existente | Pruebas de carreras/recuperación |
| Cartas/mapas | Catálogo classic/science y efectos de cartas | Conservar catálogo y efectos | Por verificar | Regresiones de interacciones |
| JWT/WS/REST | AUTH JWT; actor separado del comando; CAS versión | Confirmación vinculada a tirada/actor | Parcial | Extender contrato |
| EDA/SAGA | Redis Streams y coordinación Lobby–Game existentes | Integración real sin regresión | Por verificar | Pruebas aisladas |
| Docker | Contexto default; daemon no accesible incluso fuera del sandbox | Reconstruir y dejar desarrollo activo | Bloqueo de entorno | Reintentar disponibilidad; no publicar sin integración |

## Decisiones que evitan reglas inventadas

Si hay varias fichas candidatas a SOPLAR, emitir un evento de ambigüedad y no elegir una arbitrariamente. Conservar el inicio por el creador. Las geometrías previas no se migran silenciosamente ni se borran.

## Resultado de implementación y validación

Fecha: 2026-11-18. Se completó la implementación y la validación automatizada de las reglas críticas. Resultado estructurado: [definitive-validation.json](definitive-validation.json). Las columnas anteriores describen la inspección inicial, no el estado final.

| Área | Resultado final | Evidencia ejecutada |
|---|---|---|
| 100 casillas/rutas/salidas | v4-100; salidas 5/22/39/56, retorno 0/68 de ruta, finales 69–76 | BoardTest, Board100RulesTest, LapEntryTest y FinishPathsTest; cuatro colores y ambos mapas |
| HOME/liberaciones/intentos | Estado null/-1; liberación automática 4/3/2; tres fallos incluyendo tres coronadas | DefinitiveRulesTest y regresiones de motor |
| Ocupación/protección | Cuatro propias en comunes; seguros normales dos; salidas cuatro; captura especial HOME | DefinitiveRulesTest y pruebas de Board/carta |
| Dados | Índices 0/1 individuales; índice 2 suma; consumo irreversible y movimientos sucesivos | DefinitiveRulesTest, RuleEngineTest y suite WS |
| Captura/SOPLAR | Captura múltiple rival; una satisface turno; candidata inequívoca o evento de ambigüedad | Pruebas de dominio y probe real con timeout en ambos mapas |
| Tres pares/corona/victoria | Contador persistido; selección de activa previa propia; exactitud y victoria | DefinitiveRulesTest y FinishPathsTest |
| Trivia probabilística | p nueva por pregunta, pesos double, selección inyectable | Extremos 0/1, p .20/.60, 2/3/4/7 opciones, 20000 muestras por distribución y posición correcta; bot real sobre dos WS en ambos mapas |
| Confirmación/temporizadores | UUID por tirada; espera 10000 ms y movimiento 30000 ms; bots sin confirmación visual | Pruebas de dominio, TurnDeadlineServiceTest y probe real con reinicio de proceso |
| Timeout | Movimientos aceptados intactos; dados descartados; no avance automático; SOPLAR cuando corresponde | definitive_timeouts.py con dos identidades, ambos mapas, ambos motivos de vencimiento y acciones tardías |
| Concurrencia/idempotencia | CAS, recibos y fechas absolutas; un cierre ganador | Carreras en memoria, CAS Redis real, WS duplicados, reconexión y proceso reiniciado |
| Catálogo | 32 preguntas y seis cartas conservadas | MongoDB real, siembra idempotente y prueba de cada carta |
| Lobby/EDA/SAGA | Sin cambios de esquema; consumidor sin pendientes ni lag al consultar | 13 pruebas Lobby, creación normal/duplicada, respuesta perdida, reclamación y mensaje inválido |
| Gateway/identidades | Dos JWT emitidos por Auth/PostgreSQL aislado; dos conexiones simultáneas por Gateway | two_humans.py en classic/science: ambos lanzan, mueven, reciben jugada rival y reconectan |
| Docker | Daemon iniciado, contexto default; Game reconstruido y desplegado en proyecto docker | Healthchecks saludables; PostgreSQL acepta conexiones; Redis PONG y Mongo ping=1 |

### Suites finales

| Suite | Ejecutadas | Fallos | Errores | Omitidas |
|---|---:|---:|---:|---:|
| Game, incluida integración Redis/Mongo/HTTP/WS | 240 | 0 | 0 | 0 |
| Auth con PostgreSQL aislado | 75 | 0 | 0 | 0 |
| Gateway | 12 | 0 | 0 | 0 |
| Eureka | 1 | 0 | 0 | 0 |
| Lobby, incluidas EDA/SAGA reales | 13 | 0 | 0 | 0 |

Total: 341 pruebas, además de los probes operativos. Los primeros ensayos detectaron expectativas de las reglas antiguas y restricciones de sockets/Mockito del sandbox; se corrigieron o repitieron fuera del sandbox. El primer ensayo adicional de trivia agotó HTTP porque la compilación reemplazó el JAR montado en un proceso vivo. Se corrigió el entorno usando la imagen Docker final sin ese montaje; el probe completo de trivia, temporizadores y reinicio pasó después. Ninguno de esos intentos se registra como aprobado.

### Archivos y responsabilidades

- Dominio/motor: Board, GameState, DiceRules, RuleEngine, TurnManager y TriviaRules.
- Bots: BotPolicy y nuevo TriviaSelection; Config actualiza inyección y valores por defecto.
- Contratos: resumen OpenAPI de GameController, documentación WS/REST y ejemplo completo WS_DICE_EXAMPLE.json.
- Infraestructura: application.yaml y variables de los dos plazos en Compose. GameService, repositorio Redis, JWT, catálogo y SAGA conservan sus mecanismos existentes.
- Pruebas: regresiones adaptadas al recorrido desplazado; nuevas DefinitiveRulesTest y TurnDeadlineServiceTest; integración real actualizada y nuevo probe definitive_timeouts.py.
- Documentación: README, BOARD, ENGINE, WEBSOCKET, INTEGRATIONS, manual, guía, validación, arquitectura y ADR-004.

### Reproducción

Usar un proyecto Compose exclusivo para infrastructure/tests/compose.databases.yml, nunca desarrollo. Configurar GAME_TEST_REDIS_URL y GAME_TEST_MONGO_URI contra sus puertos 16379/17017 y ejecutar `mvn -o -Dmaven.repo.local=.build/m2 -Dgame.integration=true verify` en Game. Auth usa PostgreSQL de pruebas en 55432. Lobby necesita SAGA_TEST_LOBBY_REDIS, SAGA_TEST_GAME_REDIS, SAGA_TEST_GAME_HTTP y GAME_LOBBY_TOKEN del runtime aislado. Las claves y refresh sintéticos permanecen fuera de Git.

Los probes two_humans.py y definitive_timeouts.py exigen PROOF_ISOLATED=1 y dos refresh sintéticos. El segundo usa fixtures únicamente en partidas nuevas de Redis aislado y reinicia solo Game de pixelchiss-definitive. Su runtime temporal se preparó en /tmp/pixelchiss-definitive-runtime/runtime.json. No utilizar estas fixtures en desarrollo. El probe no certifica un cliente Godot real.

### Godot y límites reales

Godot debe actualizar las salidas/rutas, HOME por estado, capacidad visual y eventos de liberación automática; admitir dieIndex=2; confirmar cada animación con challengeId=turn.rollId; mostrar plazos del servidor, descartar dados vencidos y procesar SOPLAR/coronación. Ejemplos completos en [WEBSOCKET](../game-service/docs/WEBSOCKET.md).

No se ejecutó el binario Godot ni se comprobó un plano visual externo: el repositorio conserva la topología previa de retorno al propio seguro, trasladada cinco posiciones. Esa comprobación visual sigue pendiente del cliente. Varias candidatas SOPLAR quedan documentadas con BLOW_AMBIGUOUS, sin penalización arbitraria, conforme al requisito. La liberación automática no consume dados de avance. La espera previa a lanzar conserva la protección de 60 segundos, separada de los dos nuevos plazos.

No se borraron partidas, claves de desarrollo ni volúmenes; no se sobrescribió .env. demo-rum/ quedó fuera del trabajo. El daemon arrancó los servicios que tenían política de reinicio; las operaciones Compose posteriores se limitaron a PixelChiss y al proyecto aislado. No se modificó la configuración de otros proyectos.

## Publicación

Commit funcional verificado: `9fb4d4645ad4397e2dda43a6125eb383357f5f12`. La publicación normal a origin/main se realiza después del commit documental y de comprobar nuevamente la referencia remota. El hash final publicado se informa al cerrar la entrega. No se incluye demo-rum/.

Estado final operativo: proyecto aislado detenido con volúmenes conservados; desarrollo permanece activo y saludable. Se verificó Gateway UP, REST sin JWT devuelve 401 y el puente WS de desarrollo alcanza Game y rechaza un JWT inválido. No se crearon partidas de prueba en desarrollo.
