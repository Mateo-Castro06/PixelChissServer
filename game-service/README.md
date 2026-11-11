# PixelChiss Game Service

Java 21, Spring Boot **4.1.1**, Spring Cloud **2025.1.3**, Maven. Mantiene la organización y el puerto real **8084**, registrado en Eureka como `game-service`. No administra salas, votaciones, login ni inventarios.

## Ejecutar

Desde `game-service/`, con la infraestructura existente disponible:

```bash
export MONGODB_URI='mongodb://localhost:27017/pixelchiss'
export REDIS_URL='redis://localhost:6379/0'
export EUREKA_URL='http://localhost:8761/eureka/'
export JWT_PUBLIC_KEY_PATH='/ruta/a/la/clave/publica/de/Auth.pem'
export GAME_LOBBY_TOKEN='configurar-una-credencial-de-servicio-segura'
mvn verify
mvn spring-boot:run
# Alternativa después de compilar:
java -jar target/game-service-0.0.1-SNAPSHOT.jar
# Imagen local, después de producir el JAR:
docker build -t pixelchiss-game:latest .
```

En ejecución directa configura las variables en el entorno. En una base existente, aplica la [guía de actualización](../docs/GUIA_ACTUALIZACION.md) antes de habilitar la semilla. Game necesita la misma clave RSA pública X.509 PEM de Auth, nunca la privada. Boot4 utiliza `spring.mongodb.uri`; se conserva `MONGODB_URI`.

| Configuración | Predeterminado |
|---|---:|
| `GAME_TURN_MILLIS` | 60000 |
| `GAME_TRIVIA_MILLIS` | 15000 |
| `GAME_CARD_MILLIS` | 15000 |
| `GAME_DISCONNECT_GRACE_MILLIS` | 60000 |
| `GAME_ALLOWED_ORIGINS` | `http://localhost:8080` |
| `game.scheduler-millis` | 1000 |
| `game.bot-step-millis` | 500 |
| `game.bot-trivia-accuracy` | 0.65 |

Los plazos de desafíos no exceden el turno. Los jobs del servidor usan un pool de cuatro hilos. El bot realiza una acción por partida y ejecución programada, sin esperas ni búsquedas extensas en el hilo HTTP/WebSocket.

## API

| Método y ruta | Autenticación | Uso |
|---|---|---|
| `POST /internal/games` | `X-Lobby-Token` | Inicio autorizado, reintento idempotente |
| `GET /api/game/games/{gameId}` | Bearer JWT + pertenencia | Snapshot consistente |
| `GET /api/game/board` | Bearer JWT | Las 96 casillas y todos los recorridos |
| `GET /actuator/health` | Pública | Salud estándar |
| `/ws/game` directo; `/api/game/ws` por Gateway | Primer mensaje `AUTH` con JWT | Ciclo interactivo |

Swagger directo: **http://localhost:8084/swagger-ui/index.html**. OpenAPI completo: **http://localhost:8084/v3/api-docs**. Mediante Gateway: **http://localhost:8080/api/game/swagger-ui/index.html** y **http://localhost:8080/api/game/openapi**, especificación pública que excluye el inicio interno. Los diagnósticos originales se conservan y se habilitan solamente con `game.diagnostics.enabled=true`; quedan autenticados y documentados.

## Documentación

- [Integración Gateway, auditoría Swagger y bots automáticos](docs/GATEWAY.md).
- [Corrección de turnos y refactorización, contratos y pruebas](docs/CLEAN_CODE.md).
- [Tablero, dados y reglas asumidas](docs/BOARD.md).
- [WebSocket y consumo desde Godot](docs/WEBSOCKET.md).
- [Cartas, trivia, bots y persistencia](docs/ENGINE.md).
- [Integración Lobby y cambios externos recomendados](docs/INTEGRATIONS.md).
- [Informe de entrega, archivos, pruebas y propuesta de commits](docs/DELIVERY.md).
- [Validación real del JAR y Docker, defecto corregido y límites de integración](docs/VALIDATION.md).

## Catálogo y pruebas

Hay seis cartas (3 BUFF, 3 DEBUFF) y 16 preguntas por mapa. `classic` es cultura general y `science`, ciencia; comparten el tablero. Los efectos y el tratamiento de objetivos vacíos están en [ENGINE.md](docs/ENGINE.md).

```bash
# Desde game-service/
mvn test
# MongoDB nuevo y Redis aislados, nunca la base de desarrollo:
GAME_TEST_MONGO_URI=mongodb://127.0.0.1:17017/pixelchiss_pruebas \
  GAME_TEST_REDIS_URL=redis://127.0.0.1:16379/0 \
  mvn -Dgame.integration=true verify
```

La integración siembra dos veces la base MongoDB de prueba para verificar idempotencia y usa prefijo Redis aleatorio. Prueba reglas, REST, OpenAPI, WebSocket, concurrencia y resolución de las seis cartas. Las pruebas deterministas de coronación cubren los cuatro asientos y ambos mapas; el [probe de dos humanos](../infrastructure/tests/two_humans.py) prueba el flujo por Gateway. No ejecuta Godot.
