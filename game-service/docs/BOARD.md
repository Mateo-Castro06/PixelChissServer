# Tablero v1 y decisiones explícitas

La inspección no encontró diseños o reglas previos. Esta distribución admite **2–4 jugadores** y cuatro fichas por participante. Las plazas libres conservan sus casillas: el tablero siempre tiene96.

| IDs inclusivos | Cantidad | Función |
|---|---:|---|
| `0–63` | 64 | Recorrido común circular |
| `64–67` | 4 | Salidas de rojo, una por ficha |
| `68–71` | 4 | Salidas de azul |
| `72–75` | 4 | Salidas de verde |
| `76–79` | 4 | Salidas de amarillo |
| `80–83` | 4 | Carril rojo, corona 83 |
| `84–87` | 4 | Carril azul, corona 87 |
| `88–91` | 4 | Carril verde, corona 91 |
| `92–95` | 4 | Carril amarillo, corona 95 |
| Total | **96** | Salidas y metas incluidas |

Las **12 seguras** son `0,5,10,16,21,26,32,37,42,48,53,58`. Trivia ocurre en `8,24,40,56`, dentro del recorrido. No se agregan casillas.

Lobby envía `seat` explícito: rojo 0, azul 1, verde 2, amarillo 3, en sentido horario. El orden del array no asigna asientos; se admiten huecos y los asientos no se cambian durante la partida. `number=0..3` identifica la ficha; su ID es `seat-{seat}-piece-{number}`, único dentro de la partida. Cada operación también identifica `gameId`.

## Recorrido y posición real

| Progreso relativo | Posición física y estado |
|---|---|
| `-1` | `HOME`, posiciónnull, casa fuera de las 96 casillas |
| `0` | Salida propia `64 + 4*seat + number`, estado `BOARD` |
| `1–64` | `(16*seat + progress - 1) % 64` |
| `65–68` | Carril privado `80 + 4*seat + progress - 65` |
| `68` | Meta exacta, estado `CROWNED` |

Recorridos comunes: rojo `0..63`; azul `16..63,0..15`; verde `32..63,0..31`; amarillo `48..63,0..47`. Cada recorrido incluye su salida individual, 64 posiciones comunes y 4 posiciones finales. `GET /api/game/board` devuelve todos los recorridos y los atributos de las 96 casillas.

Dos fichas coinciden únicamente por **ID físico**, no por progreso. Rojo progreso17 y azul progreso1 comparten casilla16. Las casas no constituyen una casilla compartida; carriles de colores distintos tienen IDs diferentes.

## Capacidad y protección

- Seguro: capacidad 2, permite coexistencia de colores; protege de captura normal y carta de captura.
- Salida: capacidad 1, reservada a la ficha asignada, protegida. No coincide con los 12 seguros ni aumenta su cantidad.
- Carril final: capacidad 1 por casilla intermedia, privado del color.
- Meta: admite las 4 fichas coronadas propias, excluidas de bloqueo y movimiento ordinario; conservan su posición física final.
- Casilla común ordinaria: capacidad 1. Una ficha propia bloquea entrada; una rival vuelve a casa antes de colocar la atacante.
- No hay barreras intermedias: se comprueba el destino. Al abandonar un seguro se pierde protección, porque deriva de la posición.

## Dados y turnos

El creador original de la sala, `creatorPlayerId` autenticado mediante el contrato interno de Lobby, siempre tiene el primer turno. Debe pertenecer a la partida; no se elige otro si falta. Después se avanza por `seat` en sentido horario `0→1→2→3→0`, omitiendo asientos vacíos y retirados. Si C ocupa2 en una partida completa, la secuencia es `C→D→A→B→C`. Bots comparten esta secuencia. Dobles repiten el mismo asiento y luego continúa su sucesor horario; una reconexión o el orden de llegada de sockets no reorganiza los asientos. Contrato exacto en [INTEGRATIONS.md](INTEGRATIONS.md).

El servidor genera **dos dados**, normalmente 1–6, mediante `SecureRandom` encapsulado en `DiceSource`. El cliente nunca crea resultados ordinarios. `dieIndex=0|1` identifica los consumos: incluso con `[4,4]`, cada resultado solo se usa una vez. `dieValue` debe coincidir. No se permite sumar dados como un tercer resultado ni relanzar.

Se puede mover la misma ficha dos veces o fichas diferentes. Cada movimiento se recalcula sobre el estado confirmado. Salir de casa requiere dobles autorizados: **cada dado libera una ficha** en su salida, sin avance adicional en esa acción. El segundo puede liberar otra o avanzar una ficha en tablero; no se liberan automáticamente las cuatro.

En tablero, avance=progreso+valor. El motor verifica origen, propietario, consumo, destino y capacidad. Superar 68 se rechaza; exactamente 68 corona. Si no queda movimiento legal, descarta resultados restantes y finaliza automáticamente.

Dobles conceden otro turno después de gastar o descartar automáticamente los resultados. No se añade penalización por tres dobles, porque no fue especificada. Timeout o `END_TURN` voluntario avanzan sin premio de dobles; `END_TURN` exige lanzar y resolver desafíos pendientes.

El plazo predeterminado es 60 segundos. Acciones vencidas se rechazan y el scheduler avanza. Turnos adicionales, sin movimientos y vencidos cuentan como turnos efectivos de efectos; los de rivales no descuentan duración propia.

## Coronación, victoria y retiro

La cuarta coronación declara un único ganador y `FINISHED` en la misma actualización. Se rechazan acciones nuevas posteriores; reintentos de acciones ya confirmadas solo recuperan ACK sin mutación.

El retiro devuelve **todas** las fichas a casa, incluidas coronadas, y elimina efectos. Con dos o más activos continúa y omite retirados. Con menos de dos termina `ABANDONED`, sin ganador, preservando la condición de victoria por cuatro coronaciones.

La geometría está aislada en `Board`; `classic` y `science` usan layout v1 con bancos temáticos diferentes. Otra geometría requiere implementación explícita y migración/versionado de layout; nunca cambiar recorridos de partidas activas.
