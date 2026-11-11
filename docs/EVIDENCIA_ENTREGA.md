# Verificación del catálogo y del flujo de juego

Base remota de comparación: `f477367453f8cb474efbc75332cd623f306b1be4`. Se incorporó por avance rápido; era un cambio de documentación que retiraba el catálogo antiguo de rutas.

## Regresión ejecutada

| Suite | Superadas | Fallidas | Omitidas |
|---|---:|---:|---:|
| Game, integración activada con MongoDB nuevo y Redis aislado | 102 | 0 | 0 |
| Auth, PostgreSQL aislado | 75 | 0 | 0 |
| Gateway | 12 | 0 | 0 |
| Eureka | 1 | 0 | 0 |
| Lobby, con Game HTTP y Redis Streams aislados | 13 | 0 | 0 |

Game comprobó las seis cartas contra definiciones reales de MongoDB, persistencia CAS en Redis, resolución de objetivos y repetición idempotente. `ReleaseCardsTest` cubre reglas y ausencia de objetivos; `FinishPathsTest` cubre los cuatro asientos y ambos mapas, todas las distancias del carril final, dado excedido, intentos de entrar en carril ajeno, fichas ya coronadas y victoria con dos humanos en estados avanzados. La rotación de tres y cuatro participantes se verifica en `TurnOrderTest`.

[Prueba de dos humanos](../infrastructure/tests/two_humans.py): con dos JWT emitidos por Auth, Lobby real inició `classic` y `science` por Gateway con asientos 0 y 2. En ambos mapas se rechazó una acción fuera de turno, los dos lanzaron dados, liberaron fichas con movimientos válidos, observaron por WebSocket el movimiento del otro y se reconectaron. El probe no juega una partida completa hasta ganar: el tramo y victoria se verifican con estados avanzados deterministas en Game. No se ejecutó Godot.

El [recorrido de servicios](../infrastructure/tests/smoke_second_cut.py) comprobó además redirección OAuth, refresh, logout, bots, ACK duplicado, registro Eureka, tres interfaces Swagger, cuatro especificaciones OpenAPI, cinco targets de Prometheus activos y ocho paneles de Grafana. No completó un intercambio real con Google ni verificó MFA.

La migración se ensayó dos veces en un MongoDB aislado, primero desde el catálogo anterior y luego desde el catálogo actualizado; los cuatro documentos originales de Science resultaron idénticos. Redis también se probó en un volumen separado: arranque de datos RDB antiguos, conversión en caliente, recreación con AOF y confirmación de los datos de DB 0 y DB 1.

## Desarrollo

Se abrió únicamente MongoDB de desarrollo en mantenimiento, sin conectar Game. La copia anterior a la escritura se guardó en `game-service/.build/catalog-backups/catalog-development-before-v1.ejson` (ignorada por Git). Se observaron 8 preguntas y 0 cartas antes de la migración; después, una lectura independiente confirmó:

- `classic`: 16 preguntas de cultura general; se retiraron `classic-1` a `classic-4`.
- `science`: 16 preguntas; los cuatro documentos originales completos no cambiaron.
- 6 cartas, 3 BUFF y 3 DEBUFF; no hay IDs de carta retirados.
- La huella de la colección de diagnóstico ajena a la migración no cambió.

Ver [preguntas y respuestas](CATALOGO.md), [reglas de cartas](../game-service/docs/ENGINE.md), [posiciones del tablero](../game-service/docs/BOARD.md) y [actualización de instalaciones](GUIA_ACTUALIZACION.md).
