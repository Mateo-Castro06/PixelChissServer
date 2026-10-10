# PixelChiss API Gateway

Java21, Spring Boot4.1.1, Spring Cloud2025.1.3, Gateway Server **WebMVC**, puerto8080. Conserva las rutas de Auth. Game se descubre en Eureka como `GAME-SERVICE`, sin puerto de destino fijo.

## Rutas de Game

| Método | Ruta pública Gateway | Ruta interna Game | Seguridad |
|---|---|---|---|
| GET | `/api/game/board` | `/api/game/board` | Bearer JWT en Gateway y Game |
| GET | `/api/game/games/{gameId}` | `/api/game/games/{gameId}` | JWT y pertenencia en Game |
| GET | `/api/game/test/mongodb` | `/test/mongodb` | JWT; solo si Game habilita diagnósticos |
| GET | `/api/game/test/redis` | `/test/redis` | JWT; solo si Game habilita diagnósticos |
| GET + Upgrade | `/api/game/ws` | `/ws/game` | Handshake sin Bearer, primer mensaje AUTH obligatorio en Game |
| GET | `/api/game/openapi` | `/v3/api-docs/public-game` | Documento público, excluye inicio interno |
| GET | `/api/game/swagger-ui/index.html` | HTML del Gateway + assets `/swagger-ui/**` de Game | Documentación pública |

No se enruta `/internal/games`, ni se crea una ruta pública para ejecutar bots. Los diagnósticos están deshabilitados de forma predeterminada y sus rutas responden404 con JWT mientras no se habiliten. Sus operaciones originales escriben datos de diagnóstico; las pruebas de documentación no las ejecutan.

WebMVC5.0.3 no tiene un handler proxy ws/wss. Se añadió un puente limitado a Game usando Spring WebSocket y el cliente WebSocket de Java21; no se cambió de runtime ni se sustituyeron las rutas HTTP. `LoadBalancerClient.choose("GAME-SERVICE")` selecciona una instancia antes del upgrade. Cada conexión conserva esa instancia hasta cerrarse; no se reintentan mensajes ni se cambia de backend durante una sesión.

Primer mensaje, sin tokens en URL:

```json
{"type":"AUTH","token":"<access-token-de-Auth>"}
```

Game valida firma, identidad y expiración; luego JOIN_GAME y acciones según [contrato WebSocket](../game-service/docs/WEBSOCKET.md). Gateway no interpreta ni autoriza acciones de juego. REST conserva Bearer; el handshake anónimo no permite jugar sin autenticar. Query strings WebSocket se rechazan con400. Origin se comprueba tanto en Gateway como en Game.

El puente mantiene latidos en el tramo cliente→Gateway, porque el cliente Java responde automáticamente a pings del tramo Gateway→Game. Sin pong del cliente durante30 segundos cierra ambos tramos; cerrar el cliente también libera la conexión hacia Game. Se conservan JSON, errores, cierre por token vencido y eventos. Mensajes cliente limitados a16KiB, respuestas y buffer a64KiB, conexión al backend5 segundos y envío3 segundos.

## Configuración y ejecución

Se conservan `JWT_PUBLIC_KEY_PATH` y `EUREKA_URL`. Variable opcional `GATEWAY_GAME_ALLOWED_ORIGINS`, por defecto `http://localhost:8080`; debe coordinarse con `GAME_ALLOWED_ORIGINS` de Game. No usar comodín en despliegue web. Propiedades `gateway.game.heartbeat-millis=5000`, `gateway.game.pong-timeout-millis=30000`.

```bash
cd api-gateway
mvn -Dmaven.repo.local=.build/m2 -o verify
JWT_PUBLIC_KEY_PATH=/ruta/publica.pem EUREKA_URL=http://localhost:8761/eureka/ \
  java -jar target/api-gateway-0.0.1-SNAPSHOT.jar
```

La caché Maven de este workspace está en `.build/m2`; una instalación nueva puede necesitar omitir `-o` para descargar dependencias. Swagger público: `http://localhost:8080/api/game/swagger-ui/index.html`; documento: `http://localhost:8080/api/game/openapi`. Los paths de la especificación ya contienen `/api/game`; su servidor es `/`, para no duplicar el prefijo.

Pruebas reales repetibles: `python3 tools/validate_game_gateway.py`. Requieren MongoDB/Redis/Eureka/Auth existentes, Python `websockets`/`cryptography`, JARs compilados y la clase compilada Auth JwtService. Usan puertos18080/18081/18084, partidas y prefijos Redis únicos, y conservan sus estados. El tramo con Auth se prueba en un contenedor Gateway nuevo en la red Docker existente; no modifica ni reinicia contenedores actuales. Detiene solo sus procesos y su contenedor, que queda retenido sin volumen persistente. Variables del probe: `GATEWAY_VALIDATION_NETWORK` (aquí `docker_pixelchiss-network`) y `GATEWAY_VALIDATION_IMAGE` (aquí imagen Java21 existente `pixelchiss-game:validation-fixed-20261010`, cuyo app.jar se sustituye por un montaje del nuevo Gateway solo lectura).

Evidencia, comandos, auditoría de bots/OpenAPI y límites: [informe de integración](docs/GAME_INTEGRATION.md). El despliegue existente8080 no se reemplaza automáticamente al compilar; deberá arrancarse con el nuevo artefacto mediante el procedimiento de despliegue habitual, sin necesidad de cambiar las rutas de Compose.
