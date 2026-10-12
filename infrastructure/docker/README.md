# Docker de PixelChiss: Auth y Game

## Configuración real

El proyecto Compose existente se llama `docker`. Su red es
`docker_pixelchiss-network` y sus volúmenes son `docker_postgres_data`,
`docker_mongodb_data` y `docker_redis_data`. Mantener el mismo nombre de proyecto
evita crear otra infraestructura con volúmenes vacíos.

| Servicio Compose | Contenedor | Puerto | Datos / conexión |
| --- | --- | --- | --- |
| `eureka-server` | `pixelchiss-eureka` | 8761, publicación existente | Registry Eureka |
| `api-gateway` | `pixelchiss-gateway` | 8080, publicación existente | Descubre `AUTH-SERVICE` y `GAME-SERVICE` en Eureka |
| `auth-service` | `pixelchiss-auth` | 8081, publicación existente | `postgres:5432`, base y credenciales del `.env` existente |
| `game-service` | `pixelchiss-game` | 8084, **sólo red Docker** | `mongodb:27017/pixelchiss`, `redis:6379/0` |
| `postgres` | `pixelchiss-postgres` | 5432, publicación existente | Volumen PostgreSQL existente |
| `mongodb` | `pixelchiss-mongodb` | 27017, publicación existente | Volumen MongoDB existente |
| `redis` | `pixelchiss-redis` | 6379, publicación existente | Volumen Redis existente |

No se cambian los puertos publicados anteriormente. Game no publica 8084 al host;
`/internal/games` se consume exclusivamente dentro de la red Docker y no se añade
una ruta pública para ese endpoint. El contenedor Game monta **únicamente** la
clave pública RSA de Auth, en modo lectura, en `JWT_PUBLIC_KEY_PATH`.

Auth y Gateway conservan exactamente sus variables, credenciales y montajes.
Sus imágenes se construyen desde sus Dockerfiles actuales. Game conserva su
runtime Java 21 y ahora incorpora una etapa Maven/Java 21 que construye el JAR
desde `pom.xml` y `src/`. No requiere un JAR compilado previamente en el host.
Los contextos de Auth, Game y Gateway excluyen claves, `.env`, `target` e IDE.

Se mantiene el perfil Spring `default`: no existen perfiles Docker específicos
en los tres servicios. Las propiedades existentes se suministran mediante
variables de entorno. No se cambian POM, código de negocio, JWT, seguridad ni
contratos.

## Variables

Mantener las variables existentes de PostgreSQL, Google, JWT y `EUREKA_URL` del
`.env` local. No imprimir ni versionar ese archivo. La configuración efectiva
de Auth sigue usando `DB_PASSWORD: ${POSTGRES_PASSWORD}`, tal como estaba antes.

| Variable Game | Valor predeterminado en Compose / requisito |
| --- | --- |
| `EUREKA_URL` | Valor existente: `http://eureka-server:8761/eureka/` |
| `JWT_PUBLIC_KEY_PATH` | Valor existente: `/app/keys/public_key.pem` |
| `MONGODB_URI` | `mongodb://mongodb:27017/pixelchiss` |
| `REDIS_URL` | `redis://redis:6379/0` |
| `GAME_ALLOWED_ORIGINS` | `http://localhost:8080` |
| `GAME_LOBBY_TOKEN` | Secreto privado de al menos 32 caracteres; obligatorio para inicialización interna |

El `.env.example` documenta las variables sin credenciales reales. Para una
instalación nueva se debe generar `GAME_LOBBY_TOKEN`, por ejemplo con
`openssl rand -hex 32`, y guardarlo sólo en `.env`. Compose exige un valor no vacío;
el controlador de Game exige un mínimo de 32 caracteres. En este despliegue se
añadió un valor aleatorio de 64 caracteres al `.env` ignorado por Git, sin
reescribir ninguno de sus bytes anteriores.

Game espera Redis y MongoDB `healthy`. Auth, Game y Gateway tienen healthchecks
HTTP sobre `/actuator/health`, con intervalo 10 s, timeout 5 s, 6 intentos y
periodo inicial 60 s. Mantienen `restart: unless-stopped`. Un healthcheck fallido
marca el contenedor como `unhealthy`; por sí solo Docker no reinicia un proceso
que sigue ejecutándose. También se debe comprobar Eureka y las rutas funcionales.

## Construir y levantar

Desde la raíz del repositorio, con el `.env` local y las claves existentes:

```bash
docker compose -p docker -f infrastructure/docker/docker-compose.yml config --quiet
docker compose -p docker -f infrastructure/docker/docker-compose.yml up -d --build
docker compose -p docker -f infrastructure/docker/docker-compose.yml ps
```

Compose tiene contextos de build para Eureka, Auth, Game y Gateway. En una
infraestructura que ya funciona, para actualizar sólo los tres servicios de
esta tarea sin reiniciar sus dependencias:

```bash
docker compose -p docker -f infrastructure/docker/docker-compose.yml build auth-service game-service api-gateway
docker compose -p docker -f infrastructure/docker/docker-compose.yml up -d --no-deps --force-recreate auth-service game-service api-gateway
```

`--no-deps` presupone que Eureka y las bases ya están disponibles. La recreación
de Auth/Gateway interrumpe sus conexiones y sesiones en memoria en curso; los
usuarios, refresh tokens y claves persistidos permanecen. Para consultar salud
y registry sin mostrar credenciales:

```bash
curl --fail http://localhost:8080/actuator/health
curl --fail http://localhost:8081/actuator/health
curl --fail -H 'Accept: application/json' http://localhost:8761/eureka/apps
docker exec pixelchiss-gateway curl --fail http://game-service:8084/actuator/health
```

No usar `down -v`, `volume prune`, `system prune --volumes` ni eliminar bases o
volúmenes. Tampoco cambiar el nombre de proyecto para actualizar este despliegue.

## Informe del despliegue del 11 de octubre de 2026

### Estado inicial, imágenes y recreaciones

Estaban activos `pixelchiss-auth`, `pixelchiss-gateway`, `pixelchiss-eureka`,
`pixelchiss-postgres`, `pixelchiss-mongodb` y `pixelchiss-redis`. También había
un MySQL ajeno a PixelChiss, `sprintdev_db_server`, que no se modificó. Game y
Lobby no tenían contenedores activos.

La inspección del JAR inicial de Auth no encontró el nuevo `AuthService`.
El JAR inicial de Gateway no tenía `GameWebSocketProxy` y su `application.yaml`
no coincidía con el repositorio. Ambos ejecutaban imágenes antiguas.

| Imagen construida | Identificador de la imagen desplegada | Acción |
| --- | --- | --- |
| `pixelchiss-auth:latest` | `sha256:9a61e11457ac…` | Recreado sólo `pixelchiss-auth` |
| `pixelchiss-game:latest` | `sha256:ad0d0c0915df…` | Creado `pixelchiss-game` |
| `pixelchiss-gateway:latest` | `sha256:914597a4a9f3…` | Recreado sólo `pixelchiss-gateway` |

No se construyó, recreó ni reinició Eureka ni ninguna base durante esta tarea.
Se añadió el contexto Eureka en Compose para permitir builds de la infraestructura
desde el repositorio; su imagen y contenedor actuales se conservaron.

Se copiaron los JARs de los contenedores y se compararon sus clases y
`application.yaml` con la compilación local: **28 archivos Auth, 61 Game y 6
Gateway**, todos iguales. Los nuevos IDs de imagen y contenedor son diferentes
de los anteriores. Esto confirma código nuevo en ejecución, incluido el proxy
WebSocket, sin depender sólo de la etiqueta `latest`.

### Estado final y conectividad

Auth, Game, Gateway, PostgreSQL, MongoDB y Redis terminaron `healthy`. Eureka
continuó activo y respondió HTTP 200. El registry consultado desde el contenedor
Gateway mostró una instancia `UP` por cada servicio `AUTH-SERVICE`, `GAME-SERVICE`
y `API-GATEWAY`, con puertos 8081, 8084 y 8080 respectivamente.

Auth abrió conexiones Hikari/PostgreSQL y leyó un perfil existente por Gateway.
Un refresh existente válido produjo un access token que Gateway y Auth aceptaron,
sin rotación ni cambios en PostgreSQL. Game conectó con `mongodb:27017`, ejecutó
el seed idempotente existente y mantuvo health `UP`; las pruebas de integración
leyeron el catálogo real. Game creó estado en Redis y publicó eventos que llegaron
por WebSocket a otro cliente, verificando lectura/escritura y pub/sub reales.

### REST real mediante Gateway

| Comprobación | Resultado |
| --- | --- |
| `GET /api/game/board` con JWT válido | 200, campos `cellCount`, `cells`, `layout`, `routes`, `safe`, `trivia` |
| `GET /api/game/games/{gameId}` con participante y partida inicializada | 200 |
| Consulta de esa partida por un usuario ajeno | 403, `NOT_MEMBER` |
| Consulta de partida inexistente con JWT | 404, `GAME_NOT_FOUND` |
| JWT ausente, malformado o expirado en perfil/tablero/partida | 401 |
| `GET /api/auth/me` con JWT válido | 200 y contrato de cuatro campos existente |
| `POST /api/refresh` con refresh válido existente | 200, únicamente `accessToken`; perfil posterior 200 |
| Refresh inexistente / logout inexistente | 401 / 200 con mensaje original |
| Inicio OAuth sin parámetros cliente | 400 |
| Inicio OAuth válido | 302 a Google con state OAuth y PKCE S256 |
| `/api/game/openapi` | 200; sólo las dos rutas públicas de Game |
| `/api/game/swagger-ui/index.html` y CSS | 200 |
| `POST /internal/games` por Gateway con JWT | 404, no se enruta públicamente |
| Inicialización interna desde la red Docker | 403 sin credencial; 200 con credencial; reintento idéntico 200 |

### WebSocket real mediante Gateway

Se comprobó `ws://localhost:8080/api/game/ws` con clientes reales, sin cambiar
servicios ni fijar los dados de producción:

- Handshake público; query rechazada 400 y Origin ajeno rechazado 403.
- Mensaje `AUTH` inicial aceptado con JWT válido; inválidos/expirados rechazados
  con `INVALID_TOKEN` y cierre 1008.
- Acción anterior a `AUTH` rechazada con `UNAUTHENTICATED` y cierre 1008.
- Usuario ajeno no puede `JOIN_GAME`; identidad de bot tampoco puede conectarse.
- Dos participantes autenticados reciben `GAME_STATE`.
- Acción fuera de turno rechazada con `NOT_YOUR_TURN`.
- `ROLL_DICE` recibe `ACTION_ACK`; el otro cliente recibe `GAME_UPDATED`
  procedente de Redis con `DICE_ROLLED` y versión correspondiente.
- Repetir el request produce ACK con `duplicate=true`.
- La lista de participantes se envió en un orden diferente a sus asientos:
  el creador en asiento 2 comenzó primero, el turno avanzó al bot del asiento 3
  y luego al humano del asiento 0. El bot lanzó y completó su turno automáticamente.
- `SYNC_REQUEST` devuelve el estado posterior, verificando comunicación en ambas
  direcciones. Los eventos observados fueron `PLAYER_RECONNECTED`, `DICE_ROLLED`
  y `TURN_CHANGED`; movimientos/trivia/cartas están cubiertos por la suite
  automatizada, no se afirma que todos ocurrieron en esta partida aleatoria.

La validación final completó **60 comprobaciones reales**. Su script temporal
tuvo dos incompatibilidades propias (un nombre que ocultaba el módulo de cookies
y la excepción de la versión instalada de `websockets`); se corrigieron sólo
en el verificador antes de completar la ejecución. No se modificó producción
para hacer pasar las comprobaciones.

### Pruebas automatizadas

| Servicio | Comando desde su directorio | Pruebas | Fallos | Errores | Omitidas |
| --- | --- | ---: | ---: | ---: | ---: |
| Auth | `./mvnw verify` | 75 | 0 | 0 | 0 |
| Game | `./mvnw -Dgame.integration=true verify` | 74 | 0 | 0 | 0 |
| Gateway | `./mvnw verify` | 12 | 0 | 0 | 0 |
| Total | Todas terminaron BUILD SUCCESS | 161 | 0 | 0 | 0 |

Auth usó `AUTH_TEST_DATABASE_URL`, `AUTH_TEST_DATABASE_USER` y
`AUTH_TEST_DATABASE_PASSWORD` con un **esquema temporal** dentro del PostgreSQL
existente. Ese esquema exclusivo se retiró al terminar; las tablas públicas no
se tocaron. Game activó las pruebas opt-in con Redis/MongoDB reales existentes,
usando un prefijo Redis aleatorio y su cleanup específico. No se crearon otras
instancias de PostgreSQL, MongoDB o Redis.

Los reportes JUnit quedan en `target/surefire-reports/` de cada servicio. Logs
y evidencia de esta sesión se guardaron temporalmente en `/tmp/pixelchiss-deployment-*`;
los snapshots que contienen datos/credenciales tienen permiso 0600 y no deben
publicarse ni versionarse.

### Conservación de datos y logs

- PostgreSQL: snapshots completos de usuarios y refresh tokens iguales antes y
  después. Ningún esquema `auth_deploy_test_*` quedó pendiente.
- MongoDB: documentos de las dos colecciones existentes idénticos antes y después.
- Redis: **41 claves iniciales y 41 finales; ningún valor existente cambiado**.
  No hubo expiraciones durante la comparación. Sólo se limpiaron fixtures propios.
- PostgreSQL, MongoDB, Redis y Eureka conservaron sus IDs, tiempos de arranque,
  montajes y volúmenes; no se reiniciaron. La red existente también se conservó.
- Ningún comando de prune, eliminación de volúmenes o borrado de bases existentes
  fue ejecutado.
- Logs finales: 0 entradas ERROR en Auth, Game y Gateway. Permanecen warnings
  conocidos de Open-in-View, caché de LoadBalancer y documentación Springdoc;
  no hubo errores de arranque ni conexión. No se cambian dependencias para
  suprimir esos avisos.

### Lobby → Game: dependencia pendiente

El Lobby Python actual sólo implementa crear, consultar y unirse a salas.
`LobbyService` no llama a Game; no hay endpoint de inicio ni cliente Game.
Su modelo conserva `creator_id`, pero los jugadores sólo tienen `user_id`,
`display_name` y `role`: faltan `bot` y `seat`. El estado `PLAYING` está declarado,
pero no existe una operación de inicio que lo establezca y cree una partida.

El contrato existente de Game es `POST /internal/games` con `X-Lobby-Token` y
[`CreateGame`](../../game-service/src/main/java/com/pixelchiss/gameservice/dto/CreateGame.java):
`gameId`, `lobbyId`, `mapId`, `creatorPlayerId` y 2–4 participantes con `playerId`,
`bot` y `seat` único 0–3. `firstPlayerId` es opcional/deprecado y, si se suministra,
debe coincidir con el creador. Game valida creador y asientos y ya implementa
creador primero y orden horario.

**Lobby todavía no puede iniciar partidas reales.** Se validó el contrato de
Game directamente desde la red Docker, sin simular una integración Lobby que
no existe. No se modificaron archivos ni contratos de Lobby; completar esa
integración requiere una tarea y autorización independientes.

### Validación pendiente desde Godot

Repetir login real de Google con navegador, retorno loopback/state y PKCE,
almacenamiento/refresco/logout del cliente, consulta de perfil, tablero y
WebSocket con `AUTH`, sincronización y presentación de turnos/bots. Esta tarea
comprobó la redirección OAuth y un JWT emitido por el Auth desplegado, pero no
intercambió un código real de Google ni ejecutó Godot.

El inicio de partidas desde salas seguirá bloqueado por la integración ausente
de Lobby. Las credenciales, URLs y Origins específicos del cliente deben revisarse
en esa prueba; no se ampliaron Origins ni se cambiaron rutas para anticiparla.

### Archivos y estado de Git

Cambios de esta tarea:

- Modificados: `infrastructure/docker/docker-compose.yml`,
  `infrastructure/docker/.env.example`, `game-service/Dockerfile`,
  `game-service/.dockerignore`.
- Creados: `auth-service/.dockerignore`, `api-gateway/.dockerignore` y este README.
- Configuración local ignorada: se añadió `GAME_LOBBY_TOKEN` a
  `infrastructure/docker/.env`, conservando todos sus valores anteriores.

La comparación SHA-256 de los 180 archivos versionados y no ignorados existentes
al inicio confirmó que sólo cambiaron los cuatro archivos de despliegue indicados.
Todos los cambios anteriores de Auth, incluido `AuthServiceApplication.kt`,
permanecieron byte a byte. No se modificó código, seguridad, contratos, pruebas,
POM ni YAML de ningún microservicio durante esta tarea.

Al cerrar la validación del despliegue, antes de la autorización posterior para
versionar, la rama era `main`, sin staging: 11 archivos modificados y 16 nuevos
en total, incluyendo los cambios preexistentes de Auth. Durante esa fase no se
ejecutaron commits, push, pull, cambios de rama ni descartes.
