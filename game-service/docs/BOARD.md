# Tablero v1 y decisiones explícitas

Esta distribución admite **2–4 jugadores** y cuatro fichas por participante. El tablero conserva sus 96 casillas aunque haya asientos libres.

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
| `-1` | `HOME`, posición `null`, casa fuera de las 96 casillas |
| `0` | Salida propia `64 + 4*seat + number`, estado `BOARD` |
| `1–64` | `(16*seat + progress - 1) % 64` |
| `65–68` | Carril privado `80 + 4*seat + progress - 65` |
| `68` | Meta exacta, estado `CROWNED` |

Recorridos comunes: rojo `0..63`; azul `16..63,0..15`; verde `32..63,0..31`; amarillo `48..63,0..47`. Cada recorrido incluye su salida individual, 64 posiciones comunes y 4 posiciones finales. `GET /api/game/board` devuelve todos los recorridos y los atributos de las 96 casillas.

Dos fichas coinciden únicamente por **ID físico**, no por progreso. Rojo progreso17 y azul progreso1 comparten casilla16. Las casas no constituyen una casilla compartida; carriles de colores distintos tienen IDs diferentes.

## Capacidad y protección

- Seguro: capacidad 2 y coexistencia de colores. Protege de la captura normal. `kill-next` y `kill-chosen` ignoran esta protección en el recorrido común.
- Salida: capacidad 1, reservada a la ficha asignada, protegida. No coincide con los 12 seguros ni aumenta su cantidad.
- Carril final: capacidad 1 por casilla intermedia, privado del color.
- Meta: admite las 4 fichas coronadas propias, excluidas de bloqueo y movimiento ordinario; conservan su posición física final.
- Casilla común ordinaria: capacidad 1. Una ficha propia bloquea entrada; una rival vuelve a casa antes de colocar la atacante.
- No hay barreras intermedias: se comprueba el destino. Al abandonar un seguro se pierde protección, porque deriva de la posición.

## Dados y turnos

El participante indicado en `creatorPlayerId` mediante el contrato interno de Lobby tiene el primer turno. Lobby envía al creador si permanece en la sala o al anfitrión actual si salió. Game exige que ese participante exista. Después se avanza por `seat` en sentido horario `0→1→2→3→0`, omitiendo asientos vacíos y retirados. Si C ocupa2 en una partida completa, la secuencia es `C→D→A→B→C`. Bots comparten esta secuencia. Dobles repiten el mismo asiento y luego continúa su sucesor horario; una reconexión o el orden de llegada de sockets no reorganiza los asientos. Contrato exacto en [INTEGRATIONS.md](INTEGRATIONS.md).

El servidor genera **dos dados**, normalmente 1–6, mediante `SecureRandom` encapsulado en `DiceSource`. El cliente nunca crea resultados ordinarios. `dieIndex=0|1` identifica los consumos: incluso con `[4,4]`, cada resultado solo se usa una vez. `dieValue` debe coincidir. No se permite sumar dados como un tercer resultado ni relanzar.

Se puede mover la misma ficha dos veces o fichas diferentes. Cada movimiento se recalcula sobre el estado confirmado. Salir de casa requiere dobles autorizados: **cada dado libera una ficha** en su salida, sin avance adicional en esa acción. El segundo puede liberar otra o avanzar una ficha en tablero; no se liberan automáticamente las cuatro.

En tablero, avance=progreso+valor. El motor verifica origen, propietario, consumo, destino y capacidad. Superar 68 se rechaza; exactamente 68 corona. Si no queda movimiento legal, descarta resultados restantes y finaliza automáticamente.

Dobles conceden otro turno después de gastar o descartar automáticamente los resultados. No se añade penalización por tres dobles, porque no fue especificada. Timeout o `END_TURN` voluntario avanzan sin premio de dobles; `END_TURN` exige lanzar y resolver desafíos pendientes.

El plazo predeterminado es 60 segundos. Acciones vencidas se rechazan y el scheduler avanza. Turnos adicionales, sin movimientos y vencidos cuentan como turnos efectivos de efectos; los de rivales no descuentan duración propia.

## Coronación, victoria y retiro

La cuarta coronación declara un único ganador y `FINISHED` en la misma actualización. Se rechazan acciones nuevas posteriores; reintentos de acciones ya confirmadas solo recuperan ACK sin mutación.

El retiro devuelve **todas** las fichas a casa, incluidas coronadas, y elimina efectos. Con dos o más activos continúa y omite retirados. Con menos de dos termina `ABANDONED`, sin ganador, preservando la condición de victoria por cuatro coronaciones.

La geometría está aislada en `Board`; `classic` y `science` usan layout v1 con bancos temáticos diferentes. Otra geometría requiere implementación explícita y migración/versionado de layout; nunca cambiar recorridos de partidas activas.

## Posiciones auditadas en ambos mapas

`classic` y `science` comparten `Board`; cambia únicamente el catálogo temático de preguntas. Los índices físicos empiezan en cero.

| Tipo | Posiciones físicas | Regla |
|---|---|---|
| Pista normal | `0–63`, excluyendo seguros y trivia | Recorrido común; captura ordinaria al llegar a un rival en casilla desprotegida. |
| Seguros | `0, 5, 10, 16, 21, 26, 32, 37, 42, 48, 53, 58` | Capacidad dos. Protegen de captura ordinaria, no de las cartas de captura en casilla común. |
| Trivia | `8, 24, 40, 56` | Una llegada ordinaria abre pregunta del mapa. |
| Salida individual | `64 + 4 × asiento + númeroDeFicha` | Progreso 0. Desde casa se libera con dobles y se consume el dado elegido. |
| Recorrido común | `(16 × asiento + progreso − 1) mod 64` | Progresos 1 a 64. |
| Tramo privado | `80 + 4 × asiento + progreso − 65` | Progresos 65 a 68. Casillas previas protegidas, capacidad uno; la cuarta es la corona. |

| Asiento | Color | Salidas por ficha | Primera / última común | Tramo privado, en orden | Corona |
|---:|---|---|---|---|---:|
| 0 | Rojo | 64–67 | 0 / 63 | 80 → 81 → 82 → 83 | 83 |
| 1 | Azul | 68–71 | 16 / 15 | 84 → 85 → 86 → 87 | 87 |
| 2 | Verde | 72–75 | 32 / 31 | 88 → 89 → 90 → 91 | 91 |
| 3 | Amarillo | 76–79 | 48 / 47 | 92 → 93 → 94 → 95 | 95 |

Ejemplo: verde pasa de 31 a 88 con 1; desde 89 necesita 2 para coronar en 91. Un dado puede recorrer varias casillas consecutivas; nunca cambia la ruta, omite distancia ni rebota al superar la corona. Si excede el progreso 68, `RuleEngine.calculate` conserva `OVERSHOOT`. La ficha coronada ya no se puede mover (`CROWNED_PIECE`). El motor valida el destino y su cupo; no agrega un bloqueo de paso por casillas intermedias.

Lobby asigna los asientos `[0,2]` con dos participantes, `[0,1,2]` con tres y `[0,1,2,3]` con cuatro. Los asientos no se compactan por retirada. Si no quedan movimientos válidos, se avanza automáticamente, con turno extra solo cuando corresponde por dobles y sin perjuicio de un efecto explícito de salto.
