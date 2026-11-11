# Revisión SOLID y de MFA

Revisión del 10 de noviembre de 2026 sobre el árbol posterior al segundo corte. La [arquitectura](second-cut/architecture.md), los [contratos de inicio](second-cut/start-saga.md) y las pruebas del corte anterior siguen siendo la referencia para EDA y SAGA.

## MFA: evidencia y límite

La búsqueda en código, tests, documentación e historial disponible no encontró registros de una sesión real en la que se comprobara el segundo factor. Sí hay pruebas de redirección OAuth, `state`, PKCE S256, callback loopback, emisión y validación JWT, refresh y logout. La prueba anterior del callback usa un principal construido con `sub`, `email` y `name`: prueba la integración de Auth, no un desafío de Google. Las notas previas ya indicaban que no se hizo el intercambio real contra Google en esas ejecuciones.

Spring Security acepta el éxito de OAuth con Google. El callback no pide un segundo factor local ni verifica una indicación del método o nivel de autenticación, y el JWT de PixelChiss solo emite `sub`, `email`, `iat` y `exp`. Por eso una verificación en dos pasos que Google haya exigido a una cuenta es MFA en el proveedor; no demuestra una política exigida y comprobada por PixelChiss. Ver [Google OIDC](https://developers.google.com/identity/openid-connect/openid-connect) y [verificación en dos pasos de Google](https://support.google.com/accounts/answer/185839).

**Estado de MFA exigida por PixelChiss: pendiente.** No se contó con una cuenta y sesión real autorizadas para probar el desafío. Para la sustentación, una persona del equipo puede iniciar sesión con una cuenta de prueba con verificación en dos pasos en una sesión nueva de navegador y registrar, sin mostrar códigos ni tokens, el desafío de Google y el resultado del callback. Esto evidencia el segundo factor del proveedor para esa cuenta. Para afirmar MFA obligatoria en PixelChiss falta una política aplicable a todas las cuentas admitidas y evidencia de que se bloquea la emisión de tokens cuando no se satisface.

## Cambios SOLID

| Principio | Cambio o decisión | Regresión |
|---|---|---|
| S | FastAPI ensambla `StartGame` en `app/api/lobbies.py` y `LobbyService` conserva la coordinación de operaciones. `events/workers.py` conecta el consumidor con una función de confirmación; `game_initialized.py` conserva lectura, validación, ACK y recuperación. | Suite de Lobby: inicio, confirmación por Streams, fallos, pendientes y duplicados. |
| O | Se mantienen las reglas de Game, `DiceSource`, `GameCatalog` y `AccessTokenIssuer`. El nuevo puerto `ActiveGames` permite cambiar el origen del índice sin tocar el planificador; el consumidor recibe la confirmación como función sin acoplarse al coordinador. | Game: bot, temporizadores, turnos y reconexión. Lobby: Game real y consumidores Redis. |
| L | `GameRepository` documenta lectura separada y CAS. `MemoryGames` ahora devuelve `false` ante un ID inexistente, como Redis, sin crear estado ni publicar. | `GameRepositoryContractTest` y el mismo caso de contrato con Redis en `InfrastructureIntegrationTest`. |
| I | `BotRunner` y `GameScheduler` consumen `ActiveGames`, no escritura de partidas. En `app/application/ports.py`, `LobbyStore` no hereda del contrato de SAGA y el consumidor recibe solo `ConfirmStart`. | Suites de Game y Lobby, con Redis y WebSocket reales. |
| D | `UserService` consume `UserAccounts`; `JpaUserAccounts` delega en el repositorio existente. Se conserva la entidad JPA compartida y el repositorio de refresh. El ensamblaje de Lobby elige el adaptador y la función de confirmación. | Auth HTTP y PostgreSQL; recorrido completo por Gateway y Eureka. |

No se cambiaron el callback, los tokens, las reglas, los scripts Lua, las estructuras persistidas ni el esquema de eventos.

## Resultados de la ejecución

| Suite final | Superadas | Fallidas | Omitidas |
|---|---:|---:|---:|
| Auth | 75 | 0 | 0 |
| Game | 76 | 0 | 1 |
| Lobby | 13 | 0 | 0 |

Game y Auth compilaron y se probaron contra bases aisladas; Lobby probó Streams y SAGA contra el Game compilado. Se deshabilitó el seeder en la suite y en el Game de pruebas. La omisión corresponde a la prueba de idempotencia de seeding, que escribe por diseño. Dos ejecuciones preliminares de Lobby se interrumpieron al quedar detenidas durante una prueba ASGI; la suite completa repetida con el entorno aislado terminó con 13 aprobadas.

El recorrido real con un usuario sintético comprobó el registro de cuatro servicios en Eureka, redirección OAuth sin completar login externo, refresh, perfil, sala, inicio, dado, ACK duplicado, bot, reconexión y logout por Gateway. Abrieron tres interfaces Swagger y cuatro especificaciones. Prometheus obtuvo cinco targets `UP` y Grafana cargó ocho paneles. Se reutilizó el [probe del segundo corte](../infrastructure/tests/smoke_second_cut.py). El cliente Godot no se ejecutó.

El resultado del mantenimiento se registra aparte en el [inventario del catálogo](mantenimiento-catalogo.md).

## Archivos de esta intervención

Se excluyen cambios ya presentes al comenzar que permanecieron intactos. El único ajuste fuera de Git fue `GAME_SEED_ENABLED=false` en el `.env` local ignorado de desarrollo.

| Archivo | Estado respecto al inicio de esta revisión |
|---|---|
| `README.md` | Modificado |
| `auth-service/docs/architecture.md` | Modificado |
| `auth-service/docs/authentication.md` | Nuevo |
| `auth-service/src/main/kotlin/com/pixelchiss/auth/application/UserAccounts.kt` | Nuevo |
| `auth-service/src/main/kotlin/com/pixelchiss/auth/repository/JpaUserAccounts.kt` | Nuevo |
| `auth-service/src/main/kotlin/com/pixelchiss/auth/service/UserService.kt` | Modificado |
| `docs/MANUAL_INSTALACION.md` | Modificado |
| `docs/mantenimiento-catalogo.md` | Nuevo |
| `docs/revision-solid-mfa.md` | Nuevo |
| `docs/second-cut/architecture.md` | Modificado |
| `docs/second-cut/evidence.md` | Modificado |
| `game-service/src/main/java/com/pixelchiss/gameservice/application/ActiveGames.java` | Nuevo |
| `game-service/src/main/java/com/pixelchiss/gameservice/application/GameRepository.java` | Modificado |
| `game-service/src/main/java/com/pixelchiss/gameservice/bot/BotRunner.java` | Modificado |
| `game-service/src/main/java/com/pixelchiss/gameservice/persistence/RedisGameRepository.java` | Modificado |
| `game-service/src/main/java/com/pixelchiss/gameservice/service/GameScheduler.java` | Modificado |
| `game-service/src/test/java/com/pixelchiss/gameservice/GameRepositoryContractTest.java` | Nuevo |
| `game-service/src/test/java/com/pixelchiss/gameservice/GameServiceApplicationTests.java` | Modificado |
| `game-service/src/test/java/com/pixelchiss/gameservice/InfrastructureIntegrationTest.java` | Modificado |
| `game-service/src/test/java/com/pixelchiss/gameservice/MemoryGames.java` | Modificado |
| `infrastructure/docker/.env.example` | Modificado |
| `infrastructure/docker/docker-compose.yml` | Modificado |
| `lobby-service/app/api/lobbies.py` | Modificado |
| `lobby-service/app/application/ports.py` | Modificado |
| `lobby-service/app/events/game_initialized.py` | Modificado |
| `lobby-service/app/events/workers.py` | Modificado |
| `lobby-service/app/services/lobby_service.py` | Modificado |
| `lobby-service/tests/test_lobby_regressions.py` | Modificado |
| `lobby-service/tests/test_start_saga_integration.py` | Modificado |
