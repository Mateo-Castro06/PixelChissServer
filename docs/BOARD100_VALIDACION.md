# Tablero de 100 casillas — recorrido confirmado y evidencia

Fecha: 2026-11-17. Geometría y recorrido corregidos según la regla confirmada por el equipo de Godot y verificados en dominio para ambos mapas y los cuatro colores. **La entrega completa sigue pendiente de integración real:** Docker no está accesible, no se ejecutó Godot ni el ensayo de dos humanos por Gateway. No publicar hasta cerrar esas verificaciones de integración.

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

Las pruebas de persistencia en memoria y reconexión comprueban que el mismo ID físico en `p=68` conserva su fase y el siguiente paso entra al corredor, en vez de volver a recorrer la pista como si fuera `p=0`. La comprobación equivalente con Redis real está preparada y pendiente.

REST, métodos HTTP, DTOs, autenticación, rutas WS, tipos de mensajes y contratos EDA/SAGA no se renombraron. `GameInitialized` mantiene esquema 1 y no contiene coordenadas; no se cambia la versión de evento por cambiar el layout. Pub/Sub no es historial durable. La actualización requiere detener escritores antiguos, cerrar conexiones y conservar las partidas anteriores, reanudables solo con su servidor anterior aislado. Procedimiento en [GUIA_ACTUALIZACION.md](GUIA_ACTUALIZACION.md#tablero-de-100-casillas).

## Pruebas realmente ejecutadas en esta continuación

Las suites se ejecutaron en el host; **no en Docker**. Logs locales en `/tmp/pixelchiss-board100-lap/`.

| Suite / comando desde su módulo | Resultado |
|---|---|
| Game: `mvn -o -Dmaven.repo.local=.build/m2 verify -B` | **220 descubiertas: 207 ejecutadas sin fallos, 13 omitidas**, BUILD SUCCESS |
| Gateway: `mvn -o -Dmaven.repo.local=.build/m2 verify -B` | **12 ejecutadas sin fallos**, BUILD SUCCESS; ejecución ampliada para sockets locales |
| Eureka: `mvn -o -Dmaven.repo.local=../api-gateway/.build/m2 verify -B` | **1 ejecutada sin fallos**, BUILD SUCCESS |
| Lobby: `timeout 90 .venv/bin/python -m unittest discover -s tests -v` | **13 descubiertas: 6 ejecutadas sin fallos, 7 omitidas** por requerir infraestructura |
| Auth: `mvn -o verify -B` | **75 descubiertas: 41 sin errores y 34 errores de infraestructura**, BUILD FAILURE; PostgreSQL aislado `127.0.0.1:55432` rechaza conexión |

`BoardTest` verifica las 100 casillas únicas, 68 comunes, cuatro corredores disjuntos, doce seguros y la repetición exclusiva del seguro en las rutas. `Board100RulesTest` recorre la vuelta completa y las ocho finales para los cuatro asientos y ambos mapas, y comprueba HOME, liberación, capturas, capacidad, seis cartas y trivia.

`LapEntryTest` cubre ambos mapas y cuatro colores en 48 casos parametrizados: distinción salida/regreso con igual posición; dados 1–6 desde cada progreso 62–68; protección/capacidad al aterrizar y cruce sin parada; cartas sobre el seguro de acceso; corte de búsquedas antes del corredor; y restauración de fase mediante serialización, reconexión y acción posterior. El caso de cruce contiene 42 escenarios de dados por combinación mapa/asiento.

`FinishPathsTest` verifica distancias finales 1–8 con dados 1–6, rechaza excesos, bloqueo de finales y entrada ajena, y confirma victoria para todos los colores y mapas. Las pruebas de turnos existentes cubren 2/3/4 participantes, asientos dispersos, bots, retirada y reconexión. Esas pruebas de dominio no acreditan todavía una sesión real de dos clientes humanos por Gateway.

## Docker, bases, EDA/SAGA y dos jugadores: pendiente

Se volvió a comprobar `docker compose ls` con ejecución ampliada: **no existe `/var/run/docker.sock`**. El contexto `desktop-linux` tampoco dispone de su socket. No se pudo iniciar infraestructura aislada ni ejecutar las pruebas dependientes. No se tocaron volúmenes de desarrollo.

Quedan pendientes PostgreSQL/MongoDB/Redis reales, Game con `game.integration=true`, recuperación EDA/SAGA, inicio desde Lobby y dos identidades humanas simultáneas por Gateway/WebSocket en classic y science, incluidos movimientos visibles, turnos y reconexión. Las pruebas preparadas son `InfrastructureIntegrationTest` (incluye salas de 2/3/4 humanos), `lobby-service/tests/test_start_saga_integration.py` e `infrastructure/tests/two_humans.py`. No se acredita su ejecución por el mero hecho de compilar.

Con Docker disponible, usar únicamente proyecto y bases aislados:

```sh
docker compose -p pixelchiss-board100 -f infrastructure/tests/compose.databases.yml up -d
```

Desde game-service:

```sh
GAME_TEST_REDIS_URL=redis://127.0.0.1:16379/0 \
GAME_TEST_MONGO_URI=mongodb://127.0.0.1:17017/pixelchiss_board100_tests \
mvn -o -Dmaven.repo.local=.build/m2 -Dgame.integration=true verify -B
```

Para EDA/SAGA y dos humanos por Gateway se requiere además stack completo aislado e identidades de prueba, con las variables `SAGA_TEST_*` y `PROOF_*` descritas por esos scripts. No apuntar las pruebas a desarrollo ni usar `FLUSHDB`, `FLUSHALL` o `down -v`.

## Godot y documentación

Godot debe actualizar la caché al layout v3-100, aceptar rutas con 77 entradas y el seguro repetido en índices 0/68, usar progreso 69–76 para finales, corona exacta en 76 y HOME por estado. El movimiento desde el regreso usa el índice siguiente de ruta, no una deducción basada únicamente en `position`. El cliente puede dibujar la misma casilla con dos momentos del recorrido; no debe crear un segundo seguro ni una cárcel física. No se ejecutó el cliente real y no se afirma compatibilidad total.

Actualizados README raíz y Game, BOARD (tabla definitiva y diagrama), ENGINE, WEBSOCKET, manuales, guía de actualización, documentación arquitectónica y [ADR-003](decisions/ADR-003-board-layout-100.md). Se conservan los cambios previos relacionados con la guarda de layout. `demo-rum/` permanece ajeno a esta tarea.

## Revisión y publicación

Se revisaron el diff completo y los 41 archivos de la tarea: `git diff --check` sin errores, sintaxis de scripts Python correcta, enlaces locales resueltos, catálogo idéntico y sin coincidencias en los patrones comprobados de claves privadas/AWS/GitHub/JWT. El primer commit local es `de6add4` (`fix(game): enforce 100-cell board and full-lap safe entry`); esta documentación se organiza en el commit siguiente. El informe final indica ambos hashes y el estado de Git. El remoto comprobado sigue en `005bda88aeedfdbd15c1527974e7f67a8acf35d3`. **Push retenido:** la geometría está resuelta, pero la integración y el ensayo real de dos humanos siguen sin verificarse por falta de Docker. No se publicará ni se hará force push mientras persistan esos bloqueos.
