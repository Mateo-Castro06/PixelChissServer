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

El catálogo trae **32 preguntas** (16 de cultura general en `classic` y 16 de ciencia en `science`) y **6 cartas**. Ambos mapas comparten tablero y coronación con dado exacto. Consulta el [catálogo](docs/CATALOGO.md), [reglas](game-service/docs/BOARD.md) y [efectos](game-service/docs/ENGINE.md).

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
