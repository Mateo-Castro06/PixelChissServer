# Tablero v4-100

Hay 100 casillas físicas: comunes 0–67 y finales 68–99. HOME no es casilla. Se conservan sectores de 17, doce seguros y trivia en 8/25/42/59, para classic y science.

| Asiento/color | Sector | Salida | Corredor propio | Corona |
|---|---|---|---|---|
| 0 rojo | 0–16 | 5 | 68–75 | 75 |
| 1 azul | 17–33 | 22 | 76–83 | 83 |
| 2 verde | 34–50 | 39 | 84–91 | 91 |
| 3 amarillo | 51–67 | 56 | 92–99 | 99 |

Para asiento `s` y progreso `p`, el recorrido común es `(17*s+5+p)%68` para `p=0..68`. La salida es `p=0`; al completar la vuelta se vuelve a esa misma salida en `p=68`. La primera final es `p=69`, con posición `68+8*s+p-69`. La corona es `p=76`, posición `75+8*s`. No se puede entrar en otro corredor ni exceder 76. El sector comienza en `17*s`, que es distinto de la salida.

Cada ruta tiene 77 visitas y 76 posiciones distintas, porque repite el seguro de salida al regresar. La unión de las rutas contiene exactamente las 100 posiciones. Godot debe consumir `GET /api/game/board`, cuyas rutas están indexadas por progreso; no debe deducir progreso a partir de posición, pues 0 y 68 de una ruta coinciden físicamente.

### Entrada al corredor: contrato vigente

| Color/asiento | Salida p=0 y regreso p=68 | Predecesora p=67 | Primera final p=69 | Corona p=76 |
| --- | ---: | ---: | ---: | ---: |
| Rojo/0 | 5 | 4 | 68 | 75 |
| Azul/1 | 22 | 21 | 76 | 83 |
| Verde/2 | 39 | 38 | 84 | 91 |
| Amarillo/3 | 56 | 55 | 92 | 99 |

Una ficha que vuelve a 5/22/39/56 en progreso 68 entra en su corredor con el siguiente
paso. Una recién liberada en esa misma posición tiene progreso 0 y sigue la pista común.
Las antiguas tablas v3 con salida 0/17/34/51 no describen v4: esos IDs siguen siendo
comienzos de sector, no salidas. No añadir ni restar un offset al destino de `PIECE_MOVED`.
Las posiciones intermedias de animación se obtienen recorriendo los índices de `routes`
desde el progreso anterior hasta el nuevo, no incrementando el ID físico ni buscando
la primera aparición de `position` en la ruta.

La [auditoría reproducible](TURN_RESOLUTION_VALIDATION.md) incluye trayectorias de uno
a cuatro pasos y evidencia por Gateway WebSocket para ambos mapas. No cambia la geometría.

El retorno al mismo seguro conserva la topología del motor anterior, trasladando todo el recorrido cinco posiciones. Esta decisión no demuestra correspondencia con el plano visual de Godot: el repositorio no contiene su geometría ejecutable y la validación visual sigue pendiente.

Seguros normales: 0/12/17/29/34/46/51/63, máximo dos fichas en total. Salidas especiales: 5/22/39/56, máximo cuatro en total. Ambas protegen de captura ordinaria, incluso con colores mezclados. Liberar HOME en la salida propia devuelve todos los rivales presentes antes de colocar las fichas propias; aterrizar allí ordinariamente no captura.

Normales y trivia permiten las cuatro fichas propias. Aterrizar exactamente captura todas las rivales; atravesar no captura. Los corredores conservan capacidad uno por posición intermedia y cuatro en la corona; son privados y protegidos. No hay barreras intermedias: se valida el destino.

HOME siempre se representa así, incluso tras cartas, captura y SOPLAR:

```json
{"id":"seat-0-piece-0","ownerId":"a","number":0,"status":"HOME","position":null,"progress":-1}
```

La liberación por pares es automática y atómica en la tirada: (1,1) libera hasta cuatro, (6,6) hasta tres, otros pares hasta dos, limitada por HOME y cupo propio de la salida después de retirar rivales. Las coronadas no se liberan. `PIECE_RELEASED.from=null`; no se envía MOVE_REQUEST para liberar HOME. La liberación consume ambos dados completamente e impide movimiento de avance con esa tirada. Esta política y el retorno a la salida se documentan explícitamente para revisión funcional.

El layout v4-100 es incompatible con v3-100 y anteriores. No se transforman ni eliminan partidas antiguas: se devuelve `UNSUPPORTED_BOARD_LAYOUT`. Debe iniciarse otra partida con ID nuevo.

Los pares con HOME se dedican íntegramente a la liberación: (1,1) hasta cuatro, (6,6) hasta tres y los demás hasta dos. Ambos dados quedan consumidos; no permiten movimientos ordinarios ni obligación ficticia de captura. La salida propia captura rivales al liberar. La geometría y capacidades no cambian.
