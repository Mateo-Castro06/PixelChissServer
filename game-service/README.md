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

No es necesario editar `.env`. Game necesita la misma clave RSA pública X.509 PEM de Auth, nunca la privada. Boot4 utiliza `spring.mongodb.uri`; se conserva `MONGODB_URI`.

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

## Pruebas

```bash
mvn test
# Pruebas reales HTTP/WebSocket/MongoDB/Redis; requiere localhost:27017 y localhost:6379.
mvn -Dgame.integration=true verify
```

La integración usa la base existente `pixelchiss`, colecciones propias de Game, clave pública de prueba en `target/` y prefijo Redis aleatorio. Limpia sus claves sin vaciar Redis; la semilla de Game permanece. Eureka se deshabilita para no registrar instancias de prueba.

En este workspace se copió la caché Maven a `.build/m2` y se usó `mvn -Dmaven.repo.local=.build/m2 ...`, para no escribir dependencias fuera de Game. `.build/` y `target/` están ignorados. El wrapper original se conserva; `./mvnw` puede necesitar red para descargar Maven.

La última verificación ejecutó **74 pruebas, sin fallos, errores ni omisiones**,
incluida auditoría OpenAPI con los diagnósticos habilitados y deshabilitados.
El creador original inicia y la rotación es horaria por asientos explícitos; Lobby debe
enviar `creatorPlayerId` y `seat` por participante. Ver [CLEAN_CODE.md](docs/CLEAN_CODE.md).
El JAR refactorizado comprobó registroUP en Eureka, rotación horaria automática de
cuatro jugadores, bots, JWT compatible y dos sockets directos. La imagen Docker se
probó en el cierre previo y no se reconstruyó en esta etapa. Evidencia actual en el
informe de refactorización. La integración Gateway→Game está implementada y probada; inicio real en Lobby y
consumo desde Godot continúan pendientes; consulte [VALIDATION.md](docs/VALIDATION.md).

Para repetir los probes del JAR: `python3 tools/validate_runtime.py`. Requieren
Java 21, Python con `websockets` y `cryptography`, MongoDB/Redis/Eureka disponibles,
la clave pública de Auth y su clase JwtService ya compilada. No compilan ni modifican
Auth y nunca leen su clave privada. Crean partidas aisladas que se conservan y
detienen solamente sus propios procesos. Si 8084 o 18084 están ocupados, liberar
esos puertos por un medio autorizado antes de ejecutar el probe.
