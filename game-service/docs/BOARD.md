# Tablero v5-100

El tablero conserva 100 posiciones físicas: 68 comunes (IDs 0–67) y 32 finales
(68–99), cuatro corredores privados de ocho posiciones incluida la corona. HOME
es un estado, no una casilla. `classic` y `science` usan la misma geometría.

| Asiento/color | Salida, p=0 | Seguro de entrada, p=63 | Corredor, p=64–71 | Corona, p=71 |
| --- | ---: | ---: | --- | ---: |
| 0 rojo | 5 | 0 | 68–75 | 75 |
| 1 azul | 22 | 17 | 76–83 | 83 |
| 2 verde | 39 | 34 | 84–91 | 91 |
| 3 amarillo | 56 | 51 | 92–99 | 99 |

## Posición física y progreso lógico

Para asiento `s` y progreso `p`:

- Comunes, `0 <= p <= 63`: `(17*s + 5 + p) % 68`.
- Finales, `64 <= p <= 71`: `68 + 8*s + (p - 64)`.
- HOME: `status="HOME"`, `position=null`, `progress=-1`.
- Corona: `status="CROWNED"`, `progress=71`, posición `75 + 8*s`.

La ficha abandona la pista en el seguro situado cinco casillas antes de su salida.
No vuelve a recorrer esas cinco casillas. Cada ruta contiene **72 IDs distintos**,
indexados de 0 a 71: 64 visitas a comunes y ocho finales. Eso no reduce el tablero:
`cells` sigue conteniendo los 100 IDs y 68 casillas TRACK; la unión de las rutas
abarca las 100 posiciones. Las cuatro fichas de un mismo asiento comparten ruta.

| Color | Final de pista y entrada, progresos 61 → 62 → 63 → 64 → 65 |
| --- | --- |
| Rojo | 66 → 67 → 0 → 68 → 69 |
| Azul | 15 → 16 → 17 → 76 → 77 |
| Verde | 32 → 33 → 34 → 84 → 85 |
| Amarillo | 49 → 50 → 51 → 92 → 93 |

Ejemplo rojo: desde `progress=62, position=67`, un dado 3 pasa por 0, 68 y 69;
termina en `progress=65, position=69`. Desde progreso 63 un dado 1 entra directamente
al corredor. No pasa por 1, 2, 3, 4 ni 5 antes de entrar.

## Llegada y reglas conservadas

La llegada exige cantidad exacta: 70+1, 69+2 y 68+3 coronan en progreso 71;
70+3 devuelve `OVERSHOOT`, sin rebote ni consumo del dado. Una ficha coronada no
puede moverse. El premio por tercer par usa la elección vigente y coloca la ficha
elegida en la misma corona, sin requerir un desplazamiento por dado.

Los seguros siguen siendo 0/5/12/17/22/29/34/39/46/51/56/63. Seguros normales,
incluidos los de entrada: capacidad dos; salidas 5/22/39/56: cuatro. Se conservan
protección, capturas ordinarias, SOPLAR y bloqueo de la capturadora. Cada posición
intermedia del corredor conserva capacidad uno; cada corona admite las cuatro
fichas propias. Se valida el destino: cruzar una casilla intermedia llena no crea
una barrera nueva. Trivia continúa en 8/25/42/59.

La liberación automática desde HOME conserva sus límites por par y la captura
especial en la salida. Una liberación única consume el dado 0 y reserva el otro
para fichas activas anteriores; la recién liberada no puede usarlo. Liberaciones
múltiples consumen ambos dados. Los pares, el tercer par, los temporizadores,
cartas y bots mantienen sus reglas. Las cartas que avanzan por la pista común
usan el nuevo límite 63 y no continúan por el tramo omitido ni salen del corredor.

## Godot y contrato

`GET /api/game/board` conserva sus campos `layout`, `cellCount`, `safe`, `trivia`,
`cells` y `routes`, con `layout="v5-100"`. Godot debe invalidar la caché de rutas
anteriores y usar la del asiento correspondiente. Para animar un movimiento ordinario,
recorrer `routes[seat][piece][oldProgress+1 .. newProgress]`, incluyendo el extremo
final. No incrementar IDs físicos como si fueran progreso.

`PIECE_MOVED` mantiene `from` y `to`; el snapshot contiene el nuevo `progress` y
`position`. No se añade un campo de ruta ni eventos: el tablero y los snapshots ya
permiten reconstruir los pasos. HOME usa su evento de liberación, sin caminar desde
una posición nula. Ejemplos y reconexión en [WEBSOCKET.md](WEBSOCKET.md).

## Versiones y partidas guardadas

`v4-100` exigía regreso a la salida en progreso 68, primera final 69 y corona 76.
`v5-100` cambia esa semántica: no se reutiliza el identificador anterior.
Redis persiste `boardLayout`, progreso, posición, versión y recibos. MongoDB contiene
catálogos; no mantiene una segunda copia de las partidas.

No hay migración automática ni soporte simultáneo de ambos recorridos. Los estados
v4 y anteriores, incluidos los que no tienen marcador, se preservan y se rechazan
con `UNSUPPORTED_BOARD_LAYOUT` (REST 409 o ERROR WebSocket), antes de leer recibos,
reconectar o mutar. No cambiar a mano `boardLayout` ni restar cinco a todos los progresos:
las fichas v4 en 64–68 no tienen una correspondencia segura con el recorrido nuevo.

Una partida antigua requiere conservar su motor compatible o resolver explícitamente
su migración antes de reanudarla; una nueva partida usa otro ID y v5. No se borra ni
reinicializa nada. Un despliegue debe revisar índices y estados persistidos, coordinar
la actualización del cliente y obtener autorización antes de interrumpir partidas o
sustituir Game sobre estados incompatibles.

Pruebas, inventario y evidencia de integración en [BOARD100_VALIDACION.md](BOARD100_VALIDACION.md).
