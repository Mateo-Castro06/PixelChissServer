# Inventario del segundo corte

Excluye la modificación previa en `AuthServiceApplication.kt` y el directorio no rastreado `demo-rum/`. Los puertos `GameCatalog` y `GameRepository` aparecen como traslado de su ubicación anterior y archivo nuevo.

| Archivo | Estado | Propósito |
|---|---|---|
| `README.md` | Modificado | Describe el backend vigente y enlaza ejecución, pruebas y manuales. |
| `api-gateway/docs/GAME_INTEGRATION.md` | Modificado | Actualiza el contrato vigente o identifica el resultado histórico. |
| `api-gateway/pom.xml` | Modificado | Declara el registro Micrometer Prometheus. |
| `api-gateway/src/main/java/com/pixelchiss/gateway/security/SecurityConfig.java` | Modificado | Protege métricas sin alterar rutas existentes. |
| `api-gateway/src/main/resources/application.yaml` | Modificado | Expone salud existente y métricas protegidas de Actuator. |
| `auth-service/docs/architecture.md` | Modificado | Actualiza el contrato vigente o identifica el resultado histórico. |
| `auth-service/docs/verification.md` | Modificado | Actualiza el contrato vigente o identifica el resultado histórico. |
| `auth-service/pom.xml` | Modificado | Declara el registro Micrometer Prometheus. |
| `auth-service/src/main/kotlin/com/pixelchiss/auth/security/JwtService.kt` | Modificado | Implementa el puerto de firma sin cambiar claims. |
| `auth-service/src/main/kotlin/com/pixelchiss/auth/security/OAuth2LoginSuccessHandler.kt` | Modificado | Usa el puerto de firma conservando la secuencia OAuth. |
| `auth-service/src/main/kotlin/com/pixelchiss/auth/security/SecurityConfig.kt` | Modificado | Protege métricas de Auth con una clave separada. |
| `auth-service/src/main/kotlin/com/pixelchiss/auth/service/AuthService.kt` | Modificado | Usa el puerto de firma para refresh. |
| `auth-service/src/main/kotlin/com/pixelchiss/auth/service/RefreshTokenService.kt` | Modificado | Aplica política de vigencia y conserva la revocación. |
| `auth-service/src/main/kotlin/com/pixelchiss/auth/service/UserService.kt` | Modificado | Aplica el valor de nombre al editar el perfil. |
| `auth-service/src/main/resources/application.yaml` | Modificado | Expone salud existente y métricas protegidas de Actuator. |
| `docs/MANUAL_INSTALACION.md` | Modificado | Conserva los capítulos académicos y añade Event Bus, SAGA y monitoreo. |
| `docs/api-request-mapping.md` | Modificado | Enlaza el contrato interno sin presentarlo como REST público. |
| `docs/architecture/api-routing-convention.md` | Modificado | Distingue rutas públicas, privadas y operativas. |
| `docs/architecture/ports-and-services.md` | Modificado | Registra servicios, puertos y fronteras de datos vigentes. |
| `eureka-server/pom.xml` | Modificado | Declara el registro Micrometer Prometheus. |
| `eureka-server/src/main/resources/application.yaml` | Modificado | Expone salud existente y métricas protegidas de Actuator. |
| `game-service/docs/BOARD.md` | Modificado | Actualiza el contrato vigente o identifica el resultado histórico. |
| `game-service/docs/CLEAN_CODE.md` | Modificado | Actualiza el contrato vigente o identifica el resultado histórico. |
| `game-service/docs/DELIVERY.md` | Modificado | Actualiza el contrato vigente o identifica el resultado histórico. |
| `game-service/docs/GATEWAY.md` | Modificado | Actualiza el contrato vigente o identifica el resultado histórico. |
| `game-service/docs/INTEGRATIONS.md` | Modificado | Actualiza el contrato vigente o identifica el resultado histórico. |
| `game-service/docs/VALIDATION.md` | Modificado | Actualiza el contrato vigente o identifica el resultado histórico. |
| `game-service/docs/WEBSOCKET.md` | Modificado | Actualiza el contrato vigente o identifica el resultado histórico. |
| `game-service/pom.xml` | Modificado | Declara el registro Micrometer Prometheus. |
| `game-service/src/main/java/com/pixelchiss/gameservice/bot/BotPolicy.java` | Modificado | Emite comandos internos sin cambiar decisiones. |
| `game-service/src/main/java/com/pixelchiss/gameservice/bot/BotRunner.java` | Modificado | Usa el repositorio de aplicación. |
| `game-service/src/main/java/com/pixelchiss/gameservice/config/GameConfig.java` | Modificado | Ensamblaje Spring del motor, fuentes y aplicación. |
| `game-service/src/main/java/com/pixelchiss/gameservice/config/SecurityConfig.java` | Modificado | Protege métricas sin alterar rutas existentes. |
| `game-service/src/main/java/com/pixelchiss/gameservice/controller/GameController.java` | Modificado | Mapea snapshot de aplicación a GameView público. |
| `game-service/src/main/java/com/pixelchiss/gameservice/controller/LobbyIntegrationController.java` | Modificado | Convierte el contrato de creación al comando interno. |
| `game-service/src/main/java/com/pixelchiss/gameservice/domain/CardDefinition.java` | Modificado | Definición de carta independiente de MongoDB. |
| `game-service/src/main/java/com/pixelchiss/gameservice/domain/GameState.java` | Modificado | Quita anotación Jackson del agregado conservando campos persistidos. |
| `game-service/src/main/java/com/pixelchiss/gameservice/domain/QuestionDefinition.java` | Modificado | Definición de pregunta independiente de MongoDB. |
| `game-service/src/main/java/com/pixelchiss/gameservice/dto/Action.java` | Modificado | Añade mapeo interno sin cambiar los campos de transporte. |
| `game-service/src/main/java/com/pixelchiss/gameservice/dto/CreateGame.java` | Modificado | Añade mapeo interno y conserva validaciones del contrato. |
| `game-service/src/main/java/com/pixelchiss/gameservice/dto/GameView.java` | Modificado | Mantiene los campos públicos y mapea el snapshot. |
| `game-service/src/main/java/com/pixelchiss/gameservice/engine/RuleEngine.java` | Modificado | Consume comandos del núcleo sin cambiar reglas. |
| `game-service/src/main/java/com/pixelchiss/gameservice/engine/SecureDiceSource.java` | Modificado | Conserva aleatoriedad y mueve ensamblaje fuera del motor. |
| `game-service/src/main/java/com/pixelchiss/gameservice/engine/TriviaRules.java` | Modificado | Consume comandos y puerto de catálogo del núcleo. |
| `game-service/src/main/java/com/pixelchiss/gameservice/persistence/CatalogSeeder.java` | Modificado | Conserva la inicialización idempotente con los documentos del adaptador. |
| `game-service/src/main/java/com/pixelchiss/gameservice/persistence/GameCatalog.java` | Trasladado | Puerto de catálogo trasladado al núcleo de reglas. |
| `game-service/src/main/java/com/pixelchiss/gameservice/persistence/GameRepository.java` | Trasladado | Puerto de persistencia trasladado a aplicación con confirmación tipada. |
| `game-service/src/main/java/com/pixelchiss/gameservice/persistence/MongoGameCatalog.java` | Modificado | Traduce documentos de Mongo a definiciones. |
| `game-service/src/main/java/com/pixelchiss/gameservice/persistence/RedisGameRepository.java` | Modificado | Publica GameInitialized al crear y conserva CAS y Pub/Sub. |
| `game-service/src/main/java/com/pixelchiss/gameservice/service/GameScheduler.java` | Modificado | Usa puerto de presencia y repositorio en mantenimiento. |
| `game-service/src/main/java/com/pixelchiss/gameservice/service/GameService.java` | Modificado | Orquesta acciones y creación mediante puertos; repite creación sin reiniciar. |
| `game-service/src/main/java/com/pixelchiss/gameservice/service/PresenceService.java` | Modificado | Implementa el puerto con leases de Redis. |
| `game-service/src/main/java/com/pixelchiss/gameservice/websocket/GameWebSocketHandler.java` | Modificado | Convierte el DTO de acción y usa el puerto de presencia. |
| `game-service/src/main/resources/application.yaml` | Modificado | Expone salud existente y métricas protegidas de Actuator. |
| `game-service/src/test/java/com/pixelchiss/gameservice/BotTest.java` | Modificado | Adapta la prueba o fixture a comandos y puertos preservando la cobertura. |
| `game-service/src/test/java/com/pixelchiss/gameservice/CardsTriviaTest.java` | Modificado | Adapta la prueba o fixture a comandos y puertos preservando la cobertura. |
| `game-service/src/test/java/com/pixelchiss/gameservice/EngineFixture.java` | Modificado | Adapta la prueba o fixture a comandos y puertos preservando la cobertura. |
| `game-service/src/test/java/com/pixelchiss/gameservice/GameServiceApplicationTests.java` | Modificado | Adapta la prueba o fixture a comandos y puertos preservando la cobertura. |
| `game-service/src/test/java/com/pixelchiss/gameservice/GameServiceTest.java` | Modificado | Adapta la prueba o fixture a comandos y puertos preservando la cobertura. |
| `game-service/src/test/java/com/pixelchiss/gameservice/InfrastructureIntegrationTest.java` | Modificado | Exige bases aisladas y verifica Mongo, Redis y protocolo real. |
| `game-service/src/test/java/com/pixelchiss/gameservice/MemoryGames.java` | Modificado | Adapta la prueba o fixture a comandos y puertos preservando la cobertura. |
| `game-service/src/test/java/com/pixelchiss/gameservice/OpenApiDocumentationTest.java` | Modificado | Ejecuta verificaciones OpenAPI con configuración aislada. |
| `game-service/src/test/java/com/pixelchiss/gameservice/ServiceFixture.java` | Modificado | Adapta la prueba o fixture a comandos y puertos preservando la cobertura. |
| `game-service/src/test/java/com/pixelchiss/gameservice/TurnOrderTest.java` | Modificado | Adapta la prueba o fixture a comandos y puertos preservando la cobertura. |
| `game-service/src/test/java/com/pixelchiss/gameservice/WebSocketTest.java` | Modificado | Adapta la prueba o fixture a comandos y puertos preservando la cobertura. |
| `infrastructure/docker/.env.example` | Modificado | Documenta variables opcionales sin valores secretos. |
| `infrastructure/docker/docker-compose.yml` | Modificado | Configura AOF, conexiones del bus y perfil de monitoreo. |
| `lobby-service/README.md` | Modificado | Actualiza el contrato vigente o identifica el resultado histórico. |
| `lobby-service/app/api/lobbies.py` | Modificado | Documenta errores y condición de inicio sin cambiar la ruta. |
| `lobby-service/app/clients/auth_client.py` | Modificado | Implementa la consulta de nombres del puerto de aplicación. |
| `lobby-service/app/clients/game_client.py` | Modificado | Conserva el POST privado, traduce el plan y clasifica fallos. |
| `lobby-service/app/core/config.py` | Modificado | Consume constantes puras y elimina la tabla de asientos duplicada. |
| `lobby-service/app/core/exceptions.py` | Modificado | Separa errores del adaptador FastAPI y marca el rechazo definitivo. |
| `lobby-service/app/models/lobby.py` | Modificado | Mantiene el modelo y una generación interna para confirmar por versión. |
| `lobby-service/app/repositories/lobby_repository.py` | Modificado | Implementa snapshot coherente y transiciones Lua recuperables. |
| `lobby-service/app/schemas/lobby.py` | Modificado | Documenta STARTING ya existente. |
| `lobby-service/app/services/lobby_service.py` | Modificado | Usa puertos y delega la SAGA a StartGame. |
| `lobby-service/main.py` | Modificado | Administra los trabajadores, traduce errores y exporta métricas protegidas. |
| `lobby-service/requirements.txt` | Modificado | Añade el cliente oficial de Prometheus. |
| `lobby-service/tests/test_lobby_regressions.py` | Modificado | Conserva pruebas de rutas y actualiza fixtures al nuevo caso de uso. |
| `auth-service/src/main/kotlin/com/pixelchiss/auth/application/AccessTokenIssuer.kt` | Nuevo | Puerto saliente para la firma de access tokens. |
| `auth-service/src/main/kotlin/com/pixelchiss/auth/domain/DisplayName.kt` | Nuevo | Objeto de valor para editar el nombre. |
| `auth-service/src/main/kotlin/com/pixelchiss/auth/domain/RefreshValidity.kt` | Nuevo | Política pura de duración y expiración. |
| `docs/MANUAL_USUARIO.md` | Nuevo | Describe el recorrido de usuario sin suponer pantallas del cliente. |
| `docs/decisions/ADR-002-streams-start-saga.md` | Nuevo | Justifica Streams y orquestación en Lobby. |
| `docs/second-cut/architecture.md` | Nuevo | Contextos, puertos, invariantes, tecnologías y diagrama general. |
| `docs/second-cut/baseline.md` | Nuevo | Inventario y contratos anteriores al cambio. |
| `docs/second-cut/changes.md` | Nuevo | Registra el inventario del trabajo. |
| `docs/second-cut/evidence.md` | Nuevo | Matriz final, pruebas, pasos de reproducción y catálogo. |
| `docs/second-cut/runtime.json` | Nuevo | Salida sin secretos de la demostración integral. |
| `docs/second-cut/security.md` | Nuevo | Audita JWT, OAuth, MFA y protección de métricas. |
| `docs/second-cut/start-saga.md` | Nuevo | Contratos, transiciones, compensación y recuperación. |
| `eureka-server/src/main/java/com/pixelchiss/eurekaserver/MetricsAccessFilter.java` | Nuevo | Protege el endpoint nuevo de Eureka. |
| `game-service/src/main/java/com/pixelchiss/gameservice/application/GameRepository.java` | Nuevo | Puerto de persistencia trasladado a aplicación con confirmación tipada. |
| `game-service/src/main/java/com/pixelchiss/gameservice/application/GameSnapshot.java` | Nuevo | Modelo de salida de aplicación sin secretos ni recibos. |
| `game-service/src/main/java/com/pixelchiss/gameservice/application/GameUpdate.java` | Nuevo | Notificación tipada, separada de su serialización. |
| `game-service/src/main/java/com/pixelchiss/gameservice/application/PayloadFingerprints.java` | Nuevo | Puerto para generar huellas compatibles. |
| `game-service/src/main/java/com/pixelchiss/gameservice/application/Presence.java` | Nuevo | Puerto de presencia por sesión. |
| `game-service/src/main/java/com/pixelchiss/gameservice/domain/GameAction.java` | Nuevo | Comando de juego independiente de WebSocket. |
| `game-service/src/main/java/com/pixelchiss/gameservice/domain/GameCatalog.java` | Nuevo | Puerto de catálogo trasladado al núcleo de reglas. |
| `game-service/src/main/java/com/pixelchiss/gameservice/domain/GameSetup.java` | Nuevo | Comando de creación y participante independientes de HTTP. |
| `game-service/src/main/java/com/pixelchiss/gameservice/persistence/CatalogDocuments.java` | Nuevo | Mapea documentos a modelos puros sin cambiar colecciones. |
| `game-service/src/main/java/com/pixelchiss/gameservice/persistence/JsonPayloadFingerprints.java` | Nuevo | Implementa el algoritmo de huella preexistente. |
| `game-service/src/test/java/com/pixelchiss/gameservice/CommandCompatibilityTest.java` | Nuevo | Comprueba que DTO y comandos producen la misma huella. |
| `infrastructure/monitoring/grafana/dashboards/pixelchiss.json` | Nuevo | Aprovisiona objetivos, datasource, dashboard o paneles de monitoreo. |
| `infrastructure/monitoring/grafana/provisioning/dashboards/pixelchiss.yml` | Nuevo | Aprovisiona objetivos, datasource, dashboard o paneles de monitoreo. |
| `infrastructure/monitoring/grafana/provisioning/datasources/prometheus.yml` | Nuevo | Aprovisiona objetivos, datasource, dashboard o paneles de monitoreo. |
| `infrastructure/monitoring/prometheus.yml` | Nuevo | Aprovisiona objetivos, datasource, dashboard o paneles de monitoreo. |
| `infrastructure/tests/compose.databases.yml` | Nuevo | Define bases con volúmenes y puertos exclusivos de prueba. |
| `infrastructure/tests/smoke_second_cut.py` | Nuevo | Verifica recorrido por Gateway, Swagger y observabilidad. |
| `lobby-service/app/application/ports.py` | Nuevo | Invierte dependencias de casos de uso respecto a adaptadores. |
| `lobby-service/app/application/start_game.py` | Nuevo | Orquesta reserva, confirmación, compensación y recuperación. |
| `lobby-service/app/domain/capacity.py` | Nuevo | Aísla cupos del entorno de ejecución. |
| `lobby-service/app/domain/start.py` | Nuevo | Plan y asientos inmutables; selección de mapa y creador. |
| `lobby-service/app/events/game_initialized.py` | Nuevo | Procesa, reclama y confirma eventos; aísla fallidos. |
| `lobby-service/app/events/workers.py` | Nuevo | Gestiona tareas de consumo y recuperación. |
| `lobby-service/app/observability/metrics.py` | Nuevo | Define series de Lobby con etiquetas acotadas. |
| `lobby-service/tests/test_start_policy.py` | Nuevo | Prueba política sin Redis ni FastAPI. |
| `lobby-service/tests/test_start_saga_integration.py` | Nuevo | Ejercita Game, Streams, Redis y fallos controlados. |
| `lobby-service/tools/verify_restart.py` | Nuevo | Prueba que un consumidor real confirma después de reiniciar. |
