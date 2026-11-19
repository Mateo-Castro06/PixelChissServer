## Reglas definitivas v4-100

Salidas 5/22/39/56, capacidades diferenciadas, captura múltiple/SOPLAR, tres intentos HOME, suma de dados y trivia probabilística. El cliente debe confirmar cada animación antes del plazo de movimiento de 30 segundos; la confirmación vence a los 10 segundos. Véanse [matriz y estado de validación](docs/AUDITORIA_REGLAS_DEFINITIVAS.md), [motor](game-service/docs/ENGINE.md) y [contrato Godot](game-service/docs/WEBSOCKET.md). Las evidencias anteriores corresponden a despliegues anteriores y no acreditan esta revisión.

# PixelChiss Server

Backend multijugador de Ingeniería de Software II. Godot utiliza la API HTTP y el WebSocket del Gateway. El cliente se desarrolla en otro repositorio.

| Servicio | Responsabilidad | Tecnología y almacenamiento |
|---|---|---|
| API Gateway | Entrada HTTP y puente WebSocket; usa Eureka | Java, Spring Cloud Gateway WebMVC |
| Eureka | Registro y descubrimiento | Java, Spring Cloud Netflix |
| Auth | Identidad, OAuth con Google, JWT, refresh y perfil | Kotlin, Spring Security, Hibernate/JPA y PostgreSQL |
| Lobby | Salas, miembros, bots, preparación, votación e inicio | Python, FastAPI y Redis DB 1 |
| Game | Motor, tablero, turnos, cartas, trivia, bots y WebSocket | Java; estado en Redis DB 0 y catálogo en MongoDB |
| Observabilidad | Scraping de métricas y dashboard | Prometheus y Grafana, perfil Compose `monitoring` |

El núcleo de Game y las políticas de Lobby se prueban sin servidores. Los casos de uso consumen puertos implementados por adaptadores. DDD distingue los contextos Identity, Lobby y Game; una partida se confirma como un agregado, con versión y compare-and-set. La SAGA de inicio conserva el POST síncrono y registra un intento recuperable. Game produce `GameInitialized` en Redis Streams y Lobby puede confirmar la asociación aunque se pierda la respuesta HTTP. Redis Pub/Sub conserva la difusión en vivo hacia sockets. Game escribe el stream `pixelchiss:game:integration` en Redis DB 0 y Lobby lo consume desde esa base; sus salas y sus intentos viven en DB 1.

El catálogo trae **32 preguntas** (16 de cultura general en `classic` y 16 de ciencia en `science`) y **6 cartas**. Ambos mapas comparten tablero **v4-100: 100 casillas físicas, 68 comunes (cuatro sectores de 17) y 32 finales (cuatro corredores de ocho, incluida la corona)**. HOME es estado con `position:null` y `progress:-1`; salir exige dobles. Salidas especiales: 5/22/39/56, capacidad cuatro. Seguros normales: capacidad dos. Seguros: 0, 5, 12, 17, 22, 29, 34, 39, 46, 51, 56, 63. Corredores 68–75, 76–83, 84–91, 92–99; coronas 75/83/91/99, con dado exacto. Los estados Redis anteriores se conservan pero se rechazan explícitamente; no reutilizar sus IDs ni coordenadas. Cada ficha vuelve al seguro de salida propio tras una vuelta completa (`p=68`), entra al corredor en `p=69` y corona exactamente en `p=76`. Las suites completas y dos clientes automatizados por Gateway/WebSocket pasaron con infraestructura Docker aislada; Godot real sigue pendiente: véase [estado de la corrección](docs/BOARD100_VALIDACION.md). Consulta el [catálogo](docs/CATALOGO.md), [reglas](game-service/docs/BOARD.md) y [efectos](game-service/docs/ENGINE.md).

**Si ya tienes el repositorio y datos, sigue la [guía de actualización](docs/GUIA_ACTUALIZACION.md) antes de recrear Redis o iniciar Game.**

## Instalación y ejecución

Requisitos: Docker y Docker Compose v2, OpenSSL para claves de desarrollo y un cliente OAuth de Google para login real. Fuera de Docker: JDK 21 y Maven para Java/Kotlin; Python 3.12 para Lobby. Copia `infrastructure/docker/.env.example` a `infrastructure/docker/.env` solo en una instalación nueva. Configura PostgreSQL, Google, rutas a las claves PEM y un `GAME_LOBBY_TOKEN` aleatorio de al menos 32 caracteres. Configura la URI de callback en Google de acuerdo con el punto de entrada utilizado. La clave privada se monta únicamente en Auth; los verificadores reciben la pública. Consulta el [manual completo](docs/MANUAL_INSTALACION.md#56-despliegue-completo).

Desde la raíz:

```sh
docker compose -f infrastructure/docker/docker-compose.yml config --quiet
docker compose -f infrastructure/docker/docker-compose.yml up -d --build
docker compose -f infrastructure/docker/docker-compose.yml ps
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8083/health/redis
```

En Windows, usa Docker Desktop con contenedores Linux. Git conserva los scripts
`.sh` con finales de línea LF y el arranque de Redis también normaliza CRLF para
admitir copias existentes. Si Redis fallaba con `set: Illegal option`, después de
actualizar el repositorio recréalo conservando su volumen:

```sh
docker compose -f infrastructure/docker/docker-compose.yml up -d --no-deps --force-recreate redis
docker compose -f infrastructure/docker/docker-compose.yml up -d
```

Para monitoreo, define en el mismo `.env` `PIXELCHISS_METRICS_KEY` (mínimo 32 caracteres) y `GRAFANA_ADMIN_PASSWORD` y ejecuta:

```sh
docker compose -f infrastructure/docker/docker-compose.yml --profile monitoring up -d --build
```

[Prometheus](http://localhost:9090/targets) y [Grafana](http://localhost:3000/d/pixelchiss-services) escuchan en loopback. Los endpoints de métricas requieren una clave independiente de JWT y de `GAME_LOBBY_TOKEN`.

- [Auth Swagger](http://localhost:8081/swagger-ui/index.html) · [Lobby Swagger](http://localhost:8083/docs) · [Game Swagger por Gateway](http://localhost:8080/api/game/swagger-ui/index.html) · [Eureka](http://localhost:8761/).
- [Arquitectura y DDD](docs/second-cut/architecture.md) · [SAGA y contratos de eventos](docs/second-cut/start-saga.md) · [Decisión del Event Bus](docs/decisions/ADR-002-streams-start-saga.md).
- [Rutas y DTO](docs/MANUAL_INSTALACION.md#operaciones-rest) · [Protocolo WebSocket](game-service/docs/WEBSOCKET.md) · [Manual de usuario](docs/MANUAL_USUARIO.md) · [Instalación y configuración](docs/MANUAL_INSTALACION.md).
- [Matriz inicial](docs/second-cut/baseline.md) · [Evidencia y ejecución de pruebas](docs/second-cut/evidence.md) · [Alcance de seguridad y MFA](docs/second-cut/security.md).

OAuth con Google y JWT están implementados. El backend no exige ni verifica segundo factor. El flujo de login con una cuenta Google real y la aplicación Godot deben probarse aparte. Redis es una sola instancia en Compose; AOF cada segundo mejora la recuperación, pero puede perder escrituras recientes ante una caída abrupta.

Las instalaciones nuevas siembran el catálogo final. En una base existente, el script de actualización hace copia y retira solo los IDs antiguos autorizados. Para administrar el catálogo sin repoblarlo al arrancar, usa `GAME_SEED_ENABLED=false`. Ver la [revisión SOLID y de MFA](docs/revision-solid-mfa.md).

Pruebas: `mvn verify` por módulo Java/Kotlin, `python -m unittest discover -s tests -v` desde Lobby y [pruebas aisladas de integración](game-service/README.md#catálogo-y-pruebas).

[Verificación de esta entrega y datos migrados](docs/EVIDENCIA_ENTREGA.md).


### Actualización de pares y temporizadores

Un par concede otra tirada una vez resuelto. Si quedan fichas HOME, libera obligatoriamente hasta 4 con (1,1), 3 con (6,6) o 2 con los demás pares; consume ambos dados incluso si libera menos. No se puede mover con esos dados. Sin HOME, admite suma o uso separado. Se conservan los tres intentos HOME y la captura especial de salida. Tres pares conceden coronación de una ficha propia activa previa (recorrido o corredor); el jugador conserva la selección CROWN_REQUEST y después termina el turno, sin cuarta tirada ni penalización por tres pares.

Movimiento: 30 segundos tras confirmar cada animación (máximo 10 segundos para confirmar). Las liberaciones no abren reloj de movimiento. Trivia: 15 segundos independientes; pausa el saldo de movimiento y lo reanuda sin reiniciarlo. Sin respuesta se resuelve como incorrecta; se rechazan respuestas tardías. Al vencer movimiento se conservan jugadas aceptadas, se descartan dados pendientes, se evalúa SOPLAR solo por oportunidades reales omitidas y pasa turno sin lanzamiento extra pendiente.

Godot debe reconocer HOME_RELEASE_RESOLVED, los dados consumidos y ROLL_AVAILABLE, además de MOVEMENT_TIMER_PAUSED/RESUMED y trivia.deadline. Reconectar mantiene contadores, saldo y plazos del servidor. Véase [contrato WebSocket](game-service/docs/WEBSOCKET.md). No cambia el tablero v4-100, el catálogo ni las acciones existentes. Los campos nuevos son aditivos; tiradas/trivias ya persistidas antes de actualizar conservan su estado, y las nuevas reglas se aplican desde la siguiente activación, sin borrar partidas o volúmenes.
