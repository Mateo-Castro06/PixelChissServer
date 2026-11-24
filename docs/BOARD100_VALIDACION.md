## Validación de la revisión v4-100

Las tablas y evidencias v3 que siguen son históricas. El contrato vigente **v4-100**
usa salidas/regresos 5/22/39/56, conservando entradas finales 68/76/84/92.
Para integrar Godot utilizar [BOARD.md](../game-service/docs/BOARD.md) y la
[auditoría de corredor y cierre automático](../game-service/docs/TURN_RESOLUTION_VALIDATION.md).

La evidencia histórica que sigue corresponde a v3-100. No demuestra las reglas definitivas. La matriz inicial y los resultados actuales están en [AUDITORIA_REGLAS_DEFINITIVAS](AUDITORIA_REGLAS_DEFINITIVAS.md). Las nuevas pruebas cubren plazos, omisiones y probabilidades, además del traslado completo de las rutas. Godot real y la correspondencia visual no se han ejecutado.

# Tablero de 100 casillas — recorrido confirmado y evidencia

Fecha: 2026-11-17. **Verificación del backend completada:** suites completas, bases reales aisladas, EDA/SAGA y dos identidades mediante Gateway/WebSocket pasan en ambos mapas. La geometría sigue la regla confirmada por Godot. Se usaron clientes automatizados; **no se ejecutó el cliente Godot real**. Resultados estructurados, hashes de catálogo y JARs en [board100-runtime.json](board100-runtime.json).

## Posiciones definitivas y transición

El layout **v3-100** tiene 100 posiciones físicas únicas: 68 comunes `0–67` (cuatro sectores de 17) y 32 finales `68–99` (cuatro corredores de ocho, incluida la corona). HOME no ocupa casilla.

| Asiento / color | Sector | Salida de las cuatro fichas | Seguro de acceso tras vuelta completa | Secuencia al terminar la vuelta y entrar | Corredor completo | Corona |
|---|---|---:|---:|---|---|---:|
| 0 / rojo | 0–16 | 0 | 0 | 65 → 66 → 67 → **0** → 68 → 69 | 68 → 69 → 70 → 71 → 72 → 73 → 74 → 75 | 75 |
| 1 / azul | 17–33 | 17 | 17 | 14 → 15 → 16 → **17** → 76 → 77 | 76 → 77 → 78 → 79 → 80 → 81 → 82 → 83 | 83 |
| 2 / verde | 34–50 | 34 | 34 | 31 → 32 → 33 → **34** → 84 → 85 | 84 → 85 → 86 → 87 → 88 → 89 → 90 → 91 | 91 |
| 3 / amarillo | 51–67 | 51 | 51 | 48 → 49 → 50 → **51** → 92 → 93 | 92 → 93 → 94 → 95 → 96 → 97 → 98 → 99 | 99 |

La salida y el seguro de acceso son **la misma casilla física**, visitada al salir de HOME y al terminar 68 pasos de vuelta. Las anteriores últimas comunes 67/16/33/50 son las predecesoras del seguro, no el acceso directo al corredor.

Seguros: **0, 5, 12, 17, 22, 29, 34, 39, 46, 51, 56, 63**. El 16 no es seguro. Se conserva capacidad dos en seguros, uno en casillas finales intermedias y cuatro fichas coronadas en corona. Una tercera liberación exige cupo en el seguro compartido. La capacidad se comprueba en el destino; cruzar un seguro lleno sin aterrizar conserva la regla existente sin barreras intermedias.

## Fórmula corregida, pasos y coronación

Con asiento `s`, número de ficha `n` y progreso `p`:

- HOME: `status="HOME"`, `position=null`, `progress=-1`; `Piece.home()` conserva su semántica.
- Liberación con dobles: `p=0`, posición `17*s`; consume un dado y `legalMoves.from=null`.
- Común `0<=p<=68`: posición `(17*s+p)%68`. `p=0` es salida y `p=68` el regreso al mismo seguro propio.
- Final `69<=p<=76`: posición `68+8*s+(p-69)`; primeras finales 68/76/84/92 y coronas físicas 75/83/91/99.
- Cada movimiento normal usa `p'=p+dieValue`. Corona únicamente en `p'=76`; un exceso produce `OVERSHOOT`, una coronada rechaza `CROWNED_PIECE` y cuatro coronadas declaran victoria.

Ejemplo rojo: desde posición 67 (`p=67`), 1 alcanza el seguro 0 (`p=68`) y 2 alcanza la primera final 68 (`p=69`). Desde el seguro de regreso, 1 entra a la final 68. Desde la penúltima final (`p=75`), 1 corona y 2 falla; desde `p=74`, 2 corona y 3 falla. El destino siempre se calcula sobre el corredor propio; un índice de otro corredor se rechaza.

Se corrigió el límite anterior `p<68` a `p<=68` en el tramo común y se desplazó el inicio final de progreso 68 a 69; la corona pasó de progreso 75 a 76. `LAST_COMMON_PROGRESS` y `FIRST_FINISH_PROGRESS` separan progreso de índice físico. Las coordenadas físicas finales no cambian.

Cada ruta REST contiene **77 visitas ordenadas a 76 casillas distintas**: 69 visitas comunes (68 IDs únicos, porque `[0]==[68]`) y ocho finales. El índice de ruta coincide con el progreso. `cells` contiene exactamente 100 IDs únicos y el seguro aparece una sola vez allí, sin crear casilla ni paso artificial. La visita al regreso es necesaria para cerrar la vuelta circular, no una novena posición final.

## Cartas, trivia y compatibilidad de estados

Los archivos `seed/catalog.json` y `seed/legacy-challenges.json` se compararon con HEAD sin cambios de bytes: siguen 16 preguntas classic, 16 science, tres BUFF y tres DEBUFF. La trivia conserva el desplazamiento relativo ocho por sector: **8, 25, 42, 59** (`17*sector+8`). No se editaron preguntas ni cartas.

Las búsquedas de `kill-next`, su equivalente legado y `advance-safe` ahora incluyen el progreso común 68 y se detienen antes de 69. `advance-safe` puede llegar al seguro de acceso, pero no ofrecerlo de nuevo tras el regreso ni salir del corredor. `kill-next` puede capturar rivales en ese seguro según su regla existente de ignorar protección; `kill-chosen` conserva selección de comunes por posición física. `instant-death`, `skip-turn` y `doubles-death` conservan efectos y plazos. Capturas, cartas, penalizaciones y retirada mantienen HOME con posición nula y progreso -1; `PIECE_CAPTURED` y `CARD_PIECE_RETURNED` conservan semántica.

El agregado persiste `boardLayout="v3-100"`. Se rechazan estados sin versión, v1, **el borrador v2-100** y desconocidos con `UNSUPPORTED_BOARD_LAYOUT` (HTTP 409 o WebSocket ERROR), antes de recuperar recibos, reconectar o mutar. El cambio de marcador es necesario porque el borrador empezaba el corredor un paso antes. No se deben cambiar a mano los marcadores de snapshots ni reproducir sus recibos. No se borraron datos, claves o volúmenes.

Las pruebas de persistencia en memoria y reconexión comprueban que el mismo ID físico en `p=68` conserva su fase y el siguiente paso entra al corredor, en vez de volver a recorrer la pista como si fuera `p=0`. La suite con Redis real también pasó; adicionalmente se rechazaron cuatro layouts antiguos a través de Gateway REST/WS conservando íntegros sus hashes y recibos, y cuatro estados nuevos se recuperaron sin cambios tras reiniciar Game aislado.

REST, métodos HTTP, DTOs, autenticación, rutas WS, tipos de mensajes y contratos EDA/SAGA no se renombraron. `GameInitialized` mantiene esquema 1 y no contiene coordenadas; no se cambia la versión de evento por cambiar el layout. Pub/Sub no es historial durable. La actualización requiere detener escritores antiguos, cerrar conexiones y conservar las partidas anteriores, reanudables solo con su servidor anterior aislado. Procedimiento en [GUIA_ACTUALIZACION.md](GUIA_ACTUALIZACION.md#tablero-de-100-casillas).

## Pruebas realmente ejecutadas

Docker `default` usa `unix:///var/run/docker.sock` y daemon **29.8.1**. `desktop-linux` continúa sin socket y no se utilizó. Se creó el proyecto independiente **pixelchiss-board100-final**, con volúmenes, red, claves e identidades exclusivos. Desarrollo y `demo-rum/` no se modificaron.

Maven/Python se ejecutaron en el host con conexiones a PostgreSQL, MongoDB y Redis reales en Docker. El recorrido operativo utilizó Auth, Game, Lobby, Gateway y Eureka en contenedores aislados, con los JARs recién construidos montados de solo lectura y el código actual de Lobby. No se construyó ni sustituyó ninguna imagen de desarrollo. Logs y scripts auxiliares locales en `/tmp/pixelchiss-board100-final/`.

| Suite / comando desde su módulo | Aprobadas | Fallos/errores | Omitidas |
|---|---:|---:|---:|
| Auth: `mvn -o verify -B`, PostgreSQL de pruebas | 75 | 0 | 0 |
| Game: `mvn -o -Dmaven.repo.local=.build/m2 -Dgame.integration=true verify -B` | 225 | 0 | 0 |
| Lobby: `.venv/bin/python -m unittest discover -s tests -v`, con `SAGA_TEST_*` | 13 | 0 | 0 |
| Gateway: `mvn -o -Dmaven.repo.local=.build/m2 verify -B` | 12 | 0 | 0 |
| Eureka: `mvn -o -Dmaven.repo.local=../api-gateway/.build/m2 verify -B` | 1 | 0 | 0 |
| **Total** | **326** | **0** | **0** |

Los errores previos de Auth y las omisiones anteriores se debían a infraestructura ausente; esta ejecución completa no los reproduce. No se necesitó corregir código del backend.

`BoardTest`, `Board100RulesTest`, `LapEntryTest` y `FinishPathsTest` verifican las 100 posiciones, doce seguros, vuelta completa, acceso propio, ocho finales, movimiento exacto, victoria, HOME, las seis cartas y trivia para ambos mapas y cuatro asientos. `LapEntryTest` incluye 48 casos parametrizados y 42 combinaciones de dado/origen por mapa/asiento en el caso de cruce. Las pruebas de turnos cubren 2/3/4 participantes, bots, retirada y reconexión. `InfrastructureIntegrationTest` pasó con MongoDB/Redis/WebSocket reales, incluidas cartas desde Mongo, persistencia, rechazo de layouts antiguos, CAS, recibos y dos sockets con salas de 2/3/4 participantes.

## Bases, EDA/SAGA y dos jugadores

- PostgreSQL aislado: `pg_isready` acepta conexiones. `auth_refactor_tests` se usa para la suite y `auth_runtime` para las identidades del ensayo operativo; no se usó PostgreSQL de desarrollo.
- Redis aislado: `PING` devuelve PONG. Game vive en DB 0 y Lobby en DB 1; la suite de Game usa prefijos UUID exclusivos. No hubo FLUSHDB/FLUSHALL ni eliminación de partidas antiguas.
- MongoDB aislado: ping correcto. Bases separadas para suite y runtime. Se verificaron **todos los campos** de las 32 preguntas (16/16) y seis cartas contra `seed/catalog.json`, sin diferencias.
- Eureka aislado registra Auth, Game, Lobby y Gateway; los servicios alcanzaron salud UP y se utilizaron por descubrimiento, no mediante rutas estáticas sustituidas.

Las siete pruebas de SAGA con infraestructura pasaron: creación normal y duplicada; pérdida de respuesta HTTP recuperada por Streams; interrupción/reintento con Game temporalmente indisponible; fallo de confirmación y exclusión de reintentos concurrentes; compensación de rechazo definitivo y confirmación obsoleta; reserva concurrente; recuperación de mensajes pendientes y aislamiento de contrato inválido. El mensaje inválido deliberado fue enviado al stream de fallidos, como exige la prueba.

`infrastructure/tests/two_humans.py` se ejecutó por Gateway en **classic y science** con dos usuarios distintos de la base Auth aislada. Cada JWT fue emitido por Auth usando un refresh de prueba; no se simuló OAuth con Google. Ambos se unieron a una sala, obtuvieron el mismo gameId, mantuvieron dos WebSocket simultáneos, lanzaron dados reales, liberaron fichas, observaron el movimiento del otro, alternaron turnos, rechazaron acción fuera de turno y reconectaron con estado sincronizado. Asientos del Lobby: 0 y 2. Ambos consultaron después la misma sala STARTED y su partida.

Se ejecutó además un cliente auxiliar por Gateway/WS con **posiciones y dados autorizados preparados únicamente en partidas nuevas del Redis aislado**. Es distinto del flujo ordinario anterior. En cada mapa se probaron parejas de asientos [0,2] y [1,3], cubriendo todos los colores: regreso al seguro, entrada con un paso, cruce 67→69 con dado 2, rechazo del corredor ajeno, exceso rechazado sin mutación, movimiento de coronada rechazado, coronación exacta y victoria. Ambos clientes recibieron la misma actualización y pudieron reconectar. Las otras tres fichas se prepararon coronadas para el caso específico de victoria; no se afirma que estos escenarios jugaran una partida completa desde HOME.

En cuatro partidas adicionales se prepararon layouts ausente, v1, v2-100 y desconocido. Gateway respondió HTTP 409 y WS ERROR `UNSUPPORTED_BOARD_LAYOUT`; el hash original, versión y recibos permanecieron idénticos. No se borraron esas partidas.

El chequeo auxiliar de salas se repitió tras corregir su supuesto sobre nombres/tipos de claves de Lobby; no requirió cambios del backend. Se reinició **solo Game aislado**: cuatro partidas finalizadas se recuperaron con snapshots idénticos por REST y con ambos clientes reconectados por Gateway. Repetir la creación idéntica devolvió el estado persistido sin publicar una segunda inicialización. El stream registró **17 eventos GameInitialized esquema 1**, con **0 mensajes pendientes y lag 0** en el consumidor de Lobby. No se purgó el stream.

El runtime aislado desactivó el scheduler para que los escenarios preparados no compitieran con vencimientos o bots. Las suites completas verifican por separado plazos, bots y retiradas. El proyecto de pruebas se detiene al terminar, conservando sus volúmenes; el despliegue de desarrollo permanece activo.

Comandos esenciales para repetir la infraestructura de las suites:

```sh
docker compose -p pixelchiss-board100-final -f infrastructure/tests/compose.databases.yml up -d
```

Desde game-service:

```sh
GAME_TEST_REDIS_URL=redis://127.0.0.1:16379/0 \
GAME_TEST_MONGO_URI=mongodb://127.0.0.1:17017/pixelchiss_board100_integration \
mvn -o -Dmaven.repo.local=.build/m2 -Dgame.integration=true verify -B
```

La suite completa de Lobby requiere `SAGA_TEST_LOBBY_REDIS`, `SAGA_TEST_GAME_REDIS`, `SAGA_TEST_GAME_HTTP` y un GAME_LOBBY_TOKEN de prueba coincidente con Game. Se ejecutó antes de iniciar el worker operativo de Lobby para evitar que dos consumidores del mismo grupo compitieran por los eventos del ensayo. El flujo de dos humanos requiere el stack aislado completo y `PROOF_*` del script. Las claves, refresh y credencial interna de prueba permanecen fuera de Git. No apuntar pruebas a desarrollo ni usar `down -v`.

## Godot y documentación

Godot debe actualizar la caché al layout v3-100, aceptar rutas con 77 entradas y el seguro repetido en índices 0/68, usar progreso 69–76 para finales, corona exacta en 76 y HOME por estado. El movimiento desde el regreso usa el índice siguiente de ruta, no una deducción basada únicamente en `position`. El cliente puede dibujar la misma casilla con dos momentos del recorrido; no debe crear un segundo seguro ni una cárcel física. No se ejecutó el cliente real y no se afirma compatibilidad total.

Actualizados README raíz y Game, BOARD (tabla definitiva y diagrama), ENGINE, WEBSOCKET, manuales, guía de actualización, documentación arquitectónica y [ADR-003](decisions/ADR-003-board-layout-100.md). Se conservan los cambios previos relacionados con la guarda de layout. `demo-rum/` permanece ajeno a esta tarea.

## Revisión y publicación

La implementación verificada corresponde a `de6add4`, con documentación inicial `f1ec721`. No se añadieron refactorizaciones ni correcciones de backend durante esta verificación. Esta entrega añade únicamente la evidencia de integración y actualiza las referencias que indicaban Docker pendiente.

Se revisaron el diff completo y los 42 archivos a publicar, con enlaces locales resueltos, catálogo idéntico respecto de `005bda8`, sin errores en `git diff --check` y sin coincidencias en los patrones de secretos comprobados (claves privadas, AWS, GitHub y JWT). `demo-rum/` queda fuera de los commits. Las condiciones técnicas para un push normal están satisfechas; se verifica origin/main antes de publicar y se confirma después que coincide con HEAD. El resultado efectivo del push y los hashes finales se informan al cerrar la entrega. No se usa force push ni se despliega automáticamente sobre desarrollo.
