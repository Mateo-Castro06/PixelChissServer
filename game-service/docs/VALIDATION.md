# Validación operativa — 10 de octubre de 2026

**Informe histórico del cierre anterior a la corrección de turnos.** La comprobación de `firstPlayerId` de este informe fue sustituida por la regla definitiva del creador original y los asientos explícitos. Las 46 pruebas y las imágenes citadas corresponden a aquel artefacto, no a la refactorización actual. Los resultados actuales están en [CLEAN_CODE.md](CLEAN_CODE.md).

Se verificó Game con la infraestructura existente, sin editar Auth, Gateway, Eureka,
Lobby, Compose, archivos `.env` ni Godot. No se hicieron commits ni pushes.
El motor y las reglas de negocio se conservaron. **La integración completa desde
Godot por Gateway y Lobby continúa pendiente.**

## Resultados comprobados

| Verificación | Resultado real |
|---|---|
| Compilación, pruebas y empaquetado inicial | BUILD SUCCESS; 43 pruebas, 0 fallos, 0 errores, 0 omitidas |
| Verificación final después de corregir un defecto descubierto en ejecución | BUILD SUCCESS; **46 pruebas**, 0 fallos, 0 errores, 0 omitidas; terminó a las 13:57:54 de Bogotá |
| Arranque del JAR con MongoDB y Redis existentes | Salud UP en 8084; catálogo Mongo consultado e inicializaciones/actualizaciones Redis realizadas |
| Clave pública de Auth | JAR e imagen arrancaron con `auth-service/keys/public_key.pem`; coincide con la de Gateway; no se leyó la clave privada de Auth |
| Registro efectivo en Eureka | Instancia local `192.168.1.18:game-service:8084` UP; instancia Docker final `999bc4a3698f:game-service:18085` UP; registro HTTP 204 en logs |
| Resolución desde la red de Gateway | `docker exec pixelchiss-gateway getent hosts 999bc4a3698f` resolvió 172.19.0.8; esto comprueba DNS, no una ruta HTTP/WebSocket de Gateway |
| Emisión y validación JWT | Se cargó el **JwtService compilado existente de Auth**, se emitieron tokens con una clave RSA aislada y se validaron con JwtDecoder de Game |
| WebSocket directo al JAR y a Docker | AUTH, dos participantes, dados de servidor, movimiento, publicación al otro socket, reintento idempotente y sincronización; conexión sin AUTH rechazada |
| Tablero por HTTP autenticado | IDs 0–95 sin duplicados; 12 seguros, 16 salidas, 4 metas y 16 recorridos válidos; cuatro fichas por participante |
| Primer turno | Se proporcionó el segundo participante como `firstPlayerId`: Game comenzó con él y rechazó un primer jugador ajeno con INVALID_FIRST_PLAYER |
| Temporizadores reales | Con turno configurado a 6000 ms, un turno humano expiró sin intervención del cliente y pasó al siguiente participante |
| Bots reales | Con schedulers habilitados en el JAR y Docker, generaron dados, liberaron/movieron fichas y avanzaron turnos |
| Recuperación del defecto descubierto | La partida de validación previamente bloqueada pasó de versión **24 a 123** con la imagen corregida; observación de 30 segundos, sin borrar ni migrar su estado |
| Imagen Docker | Se construyeron y arrancaron la imagen inicial y la corregida; se repitieron los probes sobre la corregida |

Las 46 pruebas incluyen cinco de integración real con MongoDB/Redis/HTTP/WebSocket
y dos regresiones nuevas de serialización. Los probes del JAR y Docker **no son
SpringBootTest**: arrancan el artefacto real sin deshabilitar schedulers ni sustituir
SecureDiceSource. Para abreviar la observación se configuró el plazo del turno de
las instancias aisladas; el valor predeterminado del producto sigue siendo 60000 ms.

## Defecto detectado y corrección

La primera suite de 43 pruebas pasó, pero la observación posterior del contenedor
mostró `MismatchedInputException` en BotRunner y GameScheduler después de aplicar
un efecto. Jackson 3 infería como creador JSON el constructor auxiliar
`Effect(String code, int turns, int start, int max)`. El agregado persistido contiene
`remainingTurns`, `startsAtTurn` y `maxValue`, no aquellos nombres de parámetros.

Se marcó exclusivamente ese constructor con
`@JsonCreator(mode = JsonCreator.Mode.DISABLED)` para reconstruir efectos mediante
su constructor sin argumentos y sus campos públicos. No se relajó globalmente la
validación de Jackson, no se cambiaron los valores de los efectos ni su formato JSON.

Se ejecutaron primero dos regresiones: ambas fallaron con el error observado.
Después de la corrección pasaron junto con el resto de la suite y una prueba nueva
de efectos guardados/recuperados desde Redis real. El mismo agregado bloqueado se
leyó y continuó normalmente con la imagen corregida, sin intervención manual en Redis.

También hubo denegaciones iniciales del sandbox para Docker y conexiones locales;
esas operaciones se repitieron con autorización. Los avisos de Springdoc, caché
predeterminada de LoadBalancer y carga dinámica de Mockito no impidieron las pruebas
ni el arranque. En los logs finales de las instancias corregidas no se observaron
los errores de deserialización anteriores.

## Límites que siguen pendientes

- **JWT de sesión real:** no hubo un access token vigente proporcionado por un login
  real de Auth. La comprobación del emisor usa su clase existente con una clave de
  prueba, no un token emitido por el contenedor Auth mediante Google OAuth.
- **Gateway:** el handshake real `/ws/game` sin Bearer responde 401. La consulta
  `/api/game/board` sin Bearer también responde 401. El YAML actual contiene rutas
  de Auth y no de Game; además debe resolverse el soporte de proxy WebSocket de su
  variante WebMVC. No se comprobó una ruta autenticada Gateway → Game.
- **Lobby:** no hay servicio escuchando en localhost:8083. Su implementación contiene
  endpoints de ejemplo y no la llamada real de inicialización. El probe simula al
  servicio autorizado usando el contrato existente de Game; no demuestra Lobby → Game.
- **Godot:** no se ejecutó un cliente Godot. Los sockets de validación ejercitan el
  contrato JSON directamente contra Game, sin pasar por Gateway.
- **Regla original del primer turno:** el prompt y los documentos disponibles no
  especifican sorteo, comparación de dados ni desempate. Se comprobó la decisión ya
  documentada de recibir `firstPlayerId` autorizado por Lobby. Su conformidad con
  cualquier regla original adicional requiere esa especificación; no se inventó
  ni implementó un sorteo nuevo.
- **Durabilidad tras caída de Redis:** no se reinició Redis ni se cambiaron AOF/RDB.
  El probe demuestra recuperación del agregado existente y control de versión,
  no supervivencia a una caída de infraestructura.

Los archivos externos y cambios recomendados permanecen en [INTEGRATIONS.md](INTEGRATIONS.md).

## Comandos ejecutados y evidencia

Desde `game-service/`:

```bash
# Verificación inicial: 43 pruebas aprobadas.
mvn -Dmaven.repo.local=.build/m2 -o -Dgame.integration=true verify

# Regresiones antes de corregir: 2 errores que reprodujeron el defecto.
mvn -Dmaven.repo.local=.build/m2 -o -Dtest=GameStateSerializationTest test

# Verificación final: 46 pruebas aprobadas, JAR empaquetado.
mvn -Dmaven.repo.local=.build/m2 -o -Dgame.integration=true verify

# Probes antes y después de corregir; cada ejecución crea identificadores nuevos.
python3 tools/validate_runtime.py

docker build -t pixelchiss-game:validation-20261010 .
docker build -t pixelchiss-game:validation-fixed-20261010 .
```

El probe inicia `java -jar target/game-service-0.0.1-SNAPSHOT.jar` en 8084 con la
clave pública real, y en 18084 con la clave aislada. Configura MongoDB en
`mongodb://localhost:27017/pixelchiss`, Redis en `redis://localhost:6379/0`, Eureka
en `http://localhost:8761/eureka/` y un prefijo Redis único. También ejecuta `javac`
y `java AuthIssuerProbe` con las clases de Auth leídas de su directorio `target/classes`.

Los contenedores corregidos se iniciaron con estos comandos; los archivos `.vars`
son credenciales efímeras de validación dentro de `.build/`, no modificaciones de `.env`:

```bash
docker run -d --name pixelchiss-game-fixed-validation-0eb5664bcd0d \
  --network docker_pixelchiss-network -p 127.0.0.1:18085:18085 \
  --env-file .build/runtime-validation/docker-real.vars \
  --mount type=bind,source=/home/mateo/codes/MyProyects/PixelChissServer/auth-service/keys/public_key.pem,target=/app/auth-public.pem,readonly \
  pixelchiss-game:validation-fixed-20261010 \
  --server.port=18085 --game.redis-prefix=pixelchiss:game:validation:docker:0eb5664bcd0d

docker run -d --name pixelchiss-game-fixed-ws-validation-0eb5664bcd0d \
  --network docker_pixelchiss-network -p 127.0.0.1:18086:18086 \
  --env-file .build/runtime-validation/docker-ws.vars \
  --mount type=bind,source=/home/mateo/codes/MyProyects/PixelChissServer/game-service/.build/runtime-validation/issuer-public.pem,target=/app/fixture-public.pem,readonly \
  pixelchiss-game:validation-fixed-20261010 \
  --server.port=18086 --spring.application.name=game-service-validation \
  --game.redis-prefix=pixelchiss:game:validation:docker-ws:0eb5664bcd0d

python3 tools/validate_runtime.py --external-base http://localhost:18085 \
  --prefix pixelchiss:game:validation:docker:0eb5664bcd0d \
  --lobby-token-file .build/runtime-validation/docker-lobby-token \
  --recover-game validation-aaf418a2715a-bots --baseline-version 24

python3 tools/validate_runtime.py --external-base http://localhost:18086 \
  --prefix pixelchiss:game:validation:docker-ws:0eb5664bcd0d \
  --lobby-token-file .build/runtime-validation/docker-lobby-token \
  --tokens-file .build/runtime-validation/issuer-tokens.properties

docker exec pixelchiss-redis redis-cli ping
docker exec pixelchiss-mongodb mongosh --quiet --eval 'db.getSiblingDB("pixelchiss").runCommand({ping:1}).ok'
docker exec pixelchiss-gateway getent hosts 999bc4a3698f
git --no-optional-locks diff --check
```

Se consultaron también `docker ps`, `docker images`, `docker inspect`, logs de los
contenedores nuevos y las URLs reales de Eureka, Auth, Gateway y Lobby. Auth sin
JWT respondió 401; Lobby respondió conexión rechazada. Se detuvieron los cuatro
contenedores nuevos mediante `docker stop`; los contenedores preexistentes no se
reiniciaron ni se reconfiguraron. Los procesos JAR iniciados por el probe se detienen
en su bloque de cierre.

Evidencia local, ignorada por Git:

- `.build/validation-maven.log`, `.build/validation-regression-before.log`, `.build/validation-maven-final.log`.
- `target/surefire-reports/`: 46 pruebas finales.
- `.build/validation-docker-build-final.log`.
- `.build/validation-runtime-final.log`, `.build/validation-docker-runtime-final.log`, `.build/validation-docker-websocket-final.log`.
- `.build/runtime-validation/host-7192f55195c2.json`: PASS del JAR corregido.
- `.build/runtime-validation/docker-3c439765c283.json`: PASS de Docker, temporizadores, bots y recuperación.
- `.build/runtime-validation/docker-6c2e3fe4be9d.json`: PASS de HTTP/tablero/WebSocket en Docker.
- `.build/runtime-validation/eureka-final.json`, `docker-real-final.log`, `docker-ws-final.log`.
- `.build/runtime-validation/recovery-3c439765c283.json`: muestras de la partida recuperada.

## Cambios de este cierre

- `domain/GameState.java`: corrección puntual del creador JSON de Effect.
- `GameStateSerializationTest.java`: dos regresiones nuevas.
- `InfrastructureIntegrationTest.java`: prueba nueva de persistencia de efectos en Redis.
- `tools/AuthIssuerProbe.java` y `tools/validate_runtime.py`: comprobaciones reproducibles.
- Documentación: este informe y actualización de README, DELIVERY e INTEGRATIONS.

No se repitió ni refactorizó el motor. Las partidas de los probes se conservaron
con sus prefijos e IDs en los reportes. Las pruebas de integración limpian solamente
sus claves nuevas y aleatorias; no borran estados anteriores ni vacían Redis. La
semilla Mongo utiliza `$setOnInsert`, conservando documentos preexistentes.
