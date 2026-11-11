# Evidencia del segundo corte

Ejecución del 10 de noviembre de 2026. Los datos de prueba se aislaron con el proyecto Compose `pc2jan8jl`, volúmenes propios y puertos de loopback. Java/Kotlin se compilaron con Maven y se ejecutaron JAR del árbol actualizado en contenedores; Lobby se construyó desde su Dockerfile. El despliegue principal no se arrancó ni se reinicializó.

| Requisito | Estado | Evidencia ejecutada |
|---|---|---|
| Arquitectura Hexagonal | Cumplido | Comandos y motor Game independientes de adaptadores; Lobby usa puertos y política pura. Auth conserva JPA y separa firma/políticas. Pruebas del motor y de selección sin servicios. |
| DDD | Cumplido | Agregados Game y Lobby; entidades, valores, políticas y vocabulario de [arquitectura](architecture.md). Pruebas de turnos, bots, selección de mapa, preparación y concurrencia. |
| EDA y Event Bus | Cumplido | `GameInitialized` registrado en Redis Streams y consumido por Lobby; pruebas de respuesta perdida, pendientes, duplicados y fallidos. Se verificó confirmación del consumidor real tras reiniciar. |
| SAGA | Cumplido | Reserva, transacción Game, confirmación y compensación local. Pruebas de fallo ambiguo, rechazo definitivo, concurrencia y recuperación. |
| Tokens | Cumplido | Auth emitió JWT por refresh, Gateway lo encaminó y Game/Lobby lo validaron. Logout y JWT se verificaron; OAuth redirigió a Google. |
| MFA | Pendiente | [Auditoría](security.md): no hay segundo factor exigido ni demostrado. El flujo real de Google no se ejecutó. |
| Persistencia relacional/NoSQL | Cumplido | Auth/JPA con PostgreSQL, Game con MongoDB y Redis; Lua y persistencia AOF comprobadas. JPA estándar no se aplica a Mongo/Redis. |
| Prometheus y Grafana | Cumplido | `promtool check config` válido, 5/5 targets UP, consultas a series de solicitudes/eventos/SAGA y 8 paneles aprovisionados; datasource saludable. |
| Swagger/OpenAPI | Cumplido | UIs Auth, Lobby, Game por Gateway; cuatro especificaciones consultadas y ruta de inicio privada excluida de la especificación pública. |
| Manual de usuario | Cumplido | [Guía](../MANUAL_USUARIO.md) basada en operaciones reales, sin describir pantallas no inspeccionadas. |
| Instalación/configuración | Cumplido | [Manual](../MANUAL_INSTALACION.md), capítulos anteriores conservados; Compose y perfil de monitoreo validados, ejecución en stack aislado. |
| Arquitectura y solución demostrable | Cumplido para backend | Diagramas, [ADR](../decisions/ADR-002-streams-start-saga.md), contratos, [resultado del recorrido](runtime.json). Falta ejecución con el binario real de Godot. |

## Pruebas y resultado

| Suite | Superadas | Fallos o errores finales | Omitidas |
|---|---:|---:|---:|
| Game (`verify -Dgame.integration=true`) | 75 | 0 | 0 |
| Auth (`verify`) | 75 | 0 | 0 |
| Gateway (`verify`) | 12 | 0 | 0 |
| Eureka (`verify`) | 1 | 0 | 0 |
| Lobby (`unittest discover -s tests -v`) | 13 | 0 | 0 |

La suite de Game cubre un movimiento real por WebSocket con dados fijados en el entorno de prueba, lectura Mongo, serialización, concurrencia Redis y reglas de bots y turnos. El recorrido de red utiliza dados normales y comprueba lanzamiento humano, ACK duplicado, ejecución automática del bot y reconexión. La suite SAGA hace HTTP contra Game real y manipula el momento de los fallos sobre un Redis aislado; solo sustituye el descubrimiento para apuntar a la instancia de prueba. El recorrido integral sí utiliza Eureka real.

El primer intento de Auth chocó con el directorio local del compilador Kotlin; Gateway, con permisos de sockets. Se repitieron con los recursos de prueba. Una compilación intermedia de Game detectó una fixture desactualizada, corregida antes de la verificación final. Dos casos de SAGA observaron confirmaciones demasiado pronto cuando otro consumidor del mismo entorno seguía activo; la prueba final excluyó ese consumidor. El primer probe integral esperó insuficientemente el registro Eureka; se añadió espera acotada y el siguiente pasó. Estos intentos no se contabilizan como resultados finales.

Además de las suites, se ejecutaron estas pruebas de sistema:

- Docker: se levantaron PostgreSQL, MongoDB, Redis, cuatro servicios Java, Lobby, Prometheus y Grafana. `docker compose config --quiet` y `promtool check config` pasaron.
- Recorrido por Gateway: redirección OAuth, refresh y perfil, mapa y sala, alta de bot, inicio y rechazo de inicio duplicado, WebSocket y logout. Datos de salida sin tokens en [runtime.json](runtime.json).
- Reinicio real: con Lobby detenido, Game creó la partida y emitió el evento. Tras reiniciar Redis aislado se comprobaron intento, partida y evento. Al iniciar Lobby, su consumidor confirmó la misma partida sin depender del reintento HTTP. Señales: `RESERVED`, `PUBLISHED_WHILE_LOBBY_STOPPED`, `AOF_RESTART_PRESERVED_SAGA_GAME_EVENT`, `LIVE_LOBBY_CONSUMER_CONFIRMED_AFTER_RESTART`.
- No se comprobó el intercambio contra Google, MFA, cliente Godot ni pérdida abrupta antes de fsync. El reinicio de Redis fue controlado.

## Reproducción

Desde la raíz, crea bases aisladas:

```sh
docker compose -p pixelchiss-pruebas -f infrastructure/tests/compose.databases.yml up -d
```

- Auth: `cd auth-service && mvn verify`. La suite apunta por defecto al PostgreSQL de prueba en `127.0.0.1:55432`.
- Game: `cd game-service && GAME_TEST_REDIS_URL=redis://127.0.0.1:16379/0 GAME_TEST_MONGO_URI=mongodb://127.0.0.1:17017/pixelchiss_corte_test mvn -Dgame.integration=true verify`.
- Gateway y Eureka: `mvn verify` en cada módulo.
- Lobby: instala `requirements.txt` en un entorno virtual. `python -m unittest discover -s tests -v` ejecuta las pruebas sin infraestructura; para SAGA define `SAGA_TEST_LOBBY_REDIS`, `SAGA_TEST_GAME_REDIS`, `SAGA_TEST_GAME_HTTP`, `GAME_LOBBY_TOKEN` de la instancia aislada. Mantén detenido el Lobby desplegado para que su consumidor no compita por el escenario controlado.
- El probe `infrastructure/tests/smoke_second_cut.py` requiere Python 3.12, `websockets`, servicios aislados publicados en las direcciones `PROOF_*` del script, `PROOF_REFRESH_TOKEN` para un usuario sintético en PostgreSQL aislado y `PROOF_GRAFANA_PASSWORD`. No utilizarlo con datos reales: crea una sala/partida y revoca el refresh sintético al final.
- Con la misma configuración de SAGA, `python -m tools.verify_restart <fase> <archivo-temporal>` desde Lobby permite repetir la prueba. Ejecuta `reserve`, detén Lobby de pruebas, ejecuta `publish`, espera el fsync configurado, reinicia solo Redis de pruebas, ejecuta `persisted`, inicia Lobby y ejecuta `confirmed`.

Para observar fallos y persistencia, utiliza exclusivamente el entorno aislado. El Compose de desarrollo y sus volúmenes quedan fuera de estos comandos.

## Catálogo de desarrollo al cierre del segundo corte

Registro histórico anterior al [mantenimiento posterior del catálogo](../mantenimiento-catalogo.md).

Se verificó que la configuración de Game apunta a MongoDB local de Compose y a la base `pixelchiss`. No había un contenedor activo sobre `docker_mongodb_data`. Para proteger el origen, se montó ese volumen en solo lectura, se copió a un volumen temporal y se abrió únicamente la copia, con MongoDB 7 sin puertos publicados. Se ejecutaron listados, conteos, agrupación por tipo y `distinct`; no se ejecutaron seeds ni escrituras sobre el catálogo.

| Pregunta | Resultado de lectura |
|---|---|
| ¿Existen cartas? | **Sí, 4** en `pixelchiss.game_cards`: 3 BUFF y 1 DEBUFF. Las cuatro coinciden con el filtro de catálogo de cada mapa (`classic` y `science`). |
| ¿Existen dos mapas almacenados? | **No como entidades almacenadas**. La base contiene únicamente `game_cards` y `game_questions`; no hay colección de mapas. Hay **2 definidos en código** (`classic`, `science`) en `lobby-service/app/models/game_map.py`, y ambos identificadores aparecen en `game_questions.mapId`. Las referencias temáticas no son documentos de mapa. |

No se añadieron, modificaron ni eliminaron cartas o mapas de desarrollo.
