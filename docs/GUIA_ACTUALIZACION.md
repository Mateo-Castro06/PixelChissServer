## Actualización v4-100

Actualizar Game y Godot conjuntamente: salidas 5/22/39/56, rutas `(17*s+5+p)%68` hasta p=68 y corredor p=69..76. Consultar [BOARD](../game-service/docs/BOARD.md). Los agregados antiguos se conservan y rechazan con UNSUPPORTED_BOARD_LAYOUT; crear otro gameId, sin vaciar Redis ni borrar volúmenes.

Godot debe enviar DICE_ANIMATION_COMPLETED con challengeId=turn.rollId tras mostrar los dados y consumir MOVEMENT_TIMER_STARTED, TURN_EXPIRED, PIECE_RELEASED, PIECE_BLOWN, BLOW_AMBIGUOUS, ROLL_AVAILABLE y los eventos de coronación. [Ejemplos completos](../game-service/docs/WEBSOCKET.md). GAME_TURN_MILLIS=30000 y GAME_ANIMATION_WAIT_MILLIS=10000 por defecto; no sobrescribir .env.

Antes de publicar: ejecutar las suites, integrar dos identidades por Gateway y WS en ambos mapas, probar plazos y reinicio, reconstruir Game y levantar con el mismo nombre Compose existente. No usar down -v. La [auditoría](AUDITORIA_REGLAS_DEFINITIVAS.md) distingue evidencia actual de la histórica siguiente.

# Actualizar PixelChiss Server en Linux Mint / Ubuntu

Esta guía parte de la versión publicada anterior a Hexagonal (la referencia remota verificada al preparar la entrega fue `f477367`). El último commit de esa versión retiró el catálogo de rutas antiguo; usa el [manual](MANUAL_INSTALACION.md#operaciones-rest) y Swagger como referencias vigentes.

**Un `git pull` no basta.** Hay que reconstruir imágenes, convertir Redis si usa solo RDB y migrar el catálogo MongoDB si ya existe. Las bases y los volúmenes se conservan.

## Antes de actualizar

Desde la raíz de tu clon:

```bash
cd /ruta/a/PixelChissServer
git status --short
git fetch origin
git log --oneline --left-right HEAD...origin/main
```

Si tienes cambios locales o commits propios, guárdalos en una rama de trabajo antes de actualizar; revisa qué archivos pertenecen a cada integrante. No uses `reset --hard`, no fuerces la actualización y no sobrescribas el `.env` ni claves. Con el árbol limpio y la rama local sin commits divergentes:

```bash
git switch main
git pull --ff-only
```

Usa el **mismo nombre de proyecto Compose** que creó los volúmenes. Los ejemplos usan `docker`; si usas otro, sustitúyelo en todos los comandos y en el script de catálogo. Verifícalo con `docker compose ls -a` y `docker volume ls`. Trabaja con una ventana de mantenimiento: detén Game y Lobby y cualquier otro escritor. Guarda las copias fuera del repositorio.

```bash
compose() { docker compose -p docker -f infrastructure/docker/docker-compose.yml "$@"; }
compose config --quiet
compose stop game-service lobby-service
backup_dir=$(mktemp -d "$HOME/pixelchiss-backup.XXXXXX")
```

## Configuración y dependencias

| Área | Qué cambió y qué revisar |
|---|---|
| Carpetas | Game tiene puertos y comandos internos; Lobby separa puertos, dominio, EDA y SAGA. Auth usa un puerto de cuentas. Cada microservicio sigue desplegándose por separado. |
| Construcción | No se requiere instalar librerías del servicio en el host si se usa Docker. Los POM incorporan Micrometer para Prometheus y Lobby incorpora `prometheus-client`. Los Dockerfiles siguen instalando/compilando sus dependencias; reconstruye con `--build`. Fuera de Docker usa JDK 21/Maven o Python 3.12 y el `requirements.txt` actualizado. |
| Identidad y rutas | Conserva las variables Google y las claves existentes. `OAUTH_REDIRECT_URI` es opcional; el predeterminado conserva el callback directo de Auth. Gateway y Eureka mantienen rutas y puertos. El inicio de Game sigue siendo privado. |
| PostgreSQL | Mismos datos, credenciales y volumen. No hay migración SQL del dominio. |
| MongoDB | Mismos `MONGODB_URI`, base y colecciones. Debes actualizar el catálogo anterior por IDs. Usa `GAME_SEED_ENABLED=false` durante el mantenimiento; puede quedar así si administras el catálogo explícitamente. |
| Redis y Streams | Conserva `REDIS_URL` de Game (por defecto DB 0) y `LOBBY_REDIS_URL` (por defecto DB 1). Compose deriva `GAME_EVENTS_REDIS_URL` del Redis de Game. El stream predeterminado es `pixelchiss:game:integration`. No cambies DB ni prefijo para intentar recuperar datos. |
| Durabilidad | `REDIS_APPENDONLY=auto` conserva RDB en volúmenes antiguos sin AOF, y usa AOF en volúmenes nuevos o ya convertidos. Las instrucciones de conversión están abajo. |
| Monitoreo | Es opcional. Agrega `PIXELCHISS_METRICS_KEY` (mínimo 32 caracteres) y `GRAFANA_ADMIN_PASSWORD` solo para usarlo. Prometheus y Grafana se publican en loopback en 9090 y 3000. |
| Esquemas | Se mantienen CAS, DTO, JWT y evento `GameInitialized`. Para el tablero v3-100 se añade el marcador persistido `boardLayout` y se rechazan partidas antiguas sin modificar sus claves. Véase la sección de tablero al final de esta guía. La migración de catálogo descrita aquí pertenece a la entrega anterior y no se repite por el cambio geométrico. |

Edita solo las líneas necesarias de tu `infrastructure/docker/.env`. Mantén el mismo `GAME_LOBBY_TOKEN` en los dos servicios (mínimo 32 caracteres) y no compartas valores. Para comparar nombres sin mostrar secretos, ejecuta:

```bash
sed -n 's/^\([A-Z_][A-Z0-9_]*\)=.*/\1/p' infrastructure/docker/.env.example
sed -n 's/^\([A-Z_][A-Z0-9_]*\)=.*/\1/p' infrastructure/docker/.env
```

## Conservar Redis al activar AOF

En un volumen antiguo con solo RDB, activar AOF al reiniciar puede omitir el snapshot. Convierte **en el Redis que ya cargó los datos**. El modo automático evita que Compose arranque con AOF sobre un `dump.rdb` sin AOF. [Procedimiento oficial de Redis](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/).

```bash
compose up -d --no-recreate redis
compose exec -T redis redis-cli CONFIG GET appendonly
compose exec -T redis redis-cli INFO keyspace
compose exec -T redis redis-cli SAVE
compose cp redis:/data/dump.rdb "$backup_dir/redis-before.rdb"
```

Si `appendonly` es `no`, ejecuta `compose exec -T redis redis-cli CONFIG SET appendonly yes`. Espera con `compose exec -T redis redis-cli INFO persistence` hasta ver `aof_enabled:1`, `aof_rewrite_in_progress:0`, `aof_rewrite_scheduled:0` y `aof_last_bgrewrite_status:ok`. Conserva `REDIS_APPENDONLY=auto`. No reinicies durante la escritura inicial ni sigas si hay error.

```bash
compose up -d --force-recreate redis
compose exec -T redis redis-cli CONFIG GET appendonly
compose exec -T redis redis-cli INFO keyspace
```

Verifica AOF activo y los datos esperados en DB 0 y DB 1. Las claves con TTL pueden vencer durante la ventana. Conserva el respaldo y no cambies a RDB sobre un volumen con AOF sin un procedimiento específico.

## Migrar MongoDB

Mantén Game detenido y `GAME_SEED_ENABLED=false` en el `.env` de una base existente. Ejecuta desde la raíz con Python 3.12 y Docker; el script usa `mongosh` del contenedor, sin dependencias Python externas.

```bash
compose up -d mongodb
python3 infrastructure/catalog/update_catalog.py --project docker
python3 infrastructure/catalog/update_catalog.py --project docker --apply \
  --backup "$backup_dir/catalog-before.ejson"
```

La primera ejecución solo revisa. La segunda guarda Extended JSON antes de escribir, comprueba que no haya cambios concurrentes, inserta IDs nuevos y retira solo las cartas y preguntas antiguas identificadas. Si `classic-general-08` contiene el enunciado exacto de la primera entrega, actualiza solo ese campo. Si encuentra IDs ajenos o definiciones editadas, se detiene para revisión. No borra colecciones. En caso de interrupción, conserva Game detenido y repite con **un nombre de respaldo nuevo**. No cargues un respaldo completo encima de una base activa. No arranques un Game antiguo contra el catálogo nuevo: desconoce los efectos nuevos y su semilla podría reinsertar IDs retirados.

Verifica que la salida indique 32 preguntas, 16 por mapa, y 6 cartas (3 BUFF, 3 DEBUFF). Conserva intactos los cuatro documentos `science-1` a `science-4`. Las definiciones antiguas del JAR solo resuelven desafíos pendientes; no se reinsertan ni se sortean.

## Reconstruir y verificar

```bash
compose up -d --build
compose ps
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8083/health/redis
curl -fsS http://localhost:8080/api/game/openapi
```

Comprueba el registro en [Eureka](http://localhost:8761/) y Swagger de [Auth](http://localhost:8081/swagger-ui/index.html), [Lobby](http://localhost:8083/docs) y [Game](http://localhost:8080/api/game/swagger-ui/index.html). Usa dos cuentas, crea una sala, marca listo al invitado, vota un mapa, inicia y conecta ambos sockets. Si el inicio retorna indisponibilidad, consulta la sala existente: puede estar recuperándose por SAGA.

Para monitoreo agrega las dos variables indicadas y ejecuta:

```bash
compose --profile monitoring up -d --build
```

Comprueba los cinco objetivos en [Prometheus](http://localhost:9090/targets) y el [dashboard Grafana](http://localhost:3000/d/pixelchiss-services). No abras los puertos de métricas a Internet ni uses el JWT como credencial de scraping.

## Instalación nueva y cliente Godot

Una instalación nueva sigue el [manual](MANUAL_INSTALACION.md): prepara `.env` a partir del ejemplo **solo si no existe**, configura claves y OAuth, y arranca con `GAME_SEED_ENABLED=true` y `REDIS_APPENDONLY=auto`. La semilla idempotente crea las 32 preguntas y 6 cartas sin migración previa.

Godot conserva login, JWT, REST, handshake, acciones y snapshots. Para usar todas las cartas nuevas, presenta una selección por casilla cuando una opción de `card.choices` trae solo `targetPosition`, y procesa el snapshot tras `CARD_PIECE_RETURNED`. El evento nuevo permite animar una devolución sin atacante; puede ignorarse si el cliente renderiza correctamente los snapshots y tolera tipos desconocidos. Revisa [el protocolo](../game-service/docs/WEBSOCKET.md) si el cliente valida una lista cerrada de eventos. La prueba de backend no sustituye probar el cliente real.

## Tablero de 100 casillas

Esta actualización introduce `v3-100`: 68 comunes y cuatro corredores de ocho. No ejecutar el procedimiento de catálogo anterior por este cambio: **no se modifica ninguna pregunta ni carta**. Los requisitos de Redis/AOF anteriores siguen aplicando si también vienes de aquella versión.

El agregado `GameState` persiste un campo `boardLayout`. Solo se acepta `v3-100`. Un estado sin campo, con `v1`, con el borrador `v2-100` o cualquier valor desconocido se conserva y se rechaza con `UNSUPPORTED_BOARD_LAYOUT` (HTTP 409 o WebSocket `ERROR`). La comprobación precede a snapshots públicos, reconexión, acciones humanas/bots, mantenimiento, reintentos de creación y recuperación de recibos. La versión numérica CAS no se usa como versión de geometría.

1. Programa mantenimiento y toma una copia de Redis DB 0 y DB 1 y de la versión anterior del servidor. No uses `FLUSHDB`, `FLUSHALL`, `down -v` ni borres volúmenes.
2. Detén la aceptación de inicios en Lobby y deja resolver las SAGA pendientes con el despliegue anterior. Revisa salas STARTING/STARTED y sus intentos: un `GameInitialized` antiguo pendiente puede confirmar una sala antigua, pero no convierte su partida al layout nuevo. No lo vuelvas a producir con índices nuevos ni cambies sus IDs.
3. Finaliza las partidas antiguas con el servidor anterior o archívalas conservando sus claves. Para reanudarlas, usa el build antiguo en un entorno separado con una copia de sus datos y el cliente anterior; no conectes escritores de ambos layouts al mismo conjunto de partidas.
4. Detén **todas** las instancias antiguas de Game (incluidos bots y scheduler), cierra sockets y conserva Redis intacto. No hagas despliegue gradual mezclando versiones: un Game antiguo no conoce la guarda nueva y podría escribir geometría antigua en un estado nuevo.
5. Tras verificar la integración en tu entorno de actualización, desplegar Game nuevo y el cliente con coordenadas v3-100 y el acceso propio descrito en [BOARD.md](../game-service/docs/BOARD.md). Crear salas y gameIds nuevos. Una creación con ID antiguo falla sin sobrescribirlo, aun si su fingerprint coincide.
6. Verifica `/api/game/board` (`layout:v3-100`, `cellCount:100`), las doce seguras, las salidas 0/17/34/51 y dos conexiones humanas. Consulta una partida anterior: debe rechazar explícitamente y conservar el JSON y recibos originales.

Los estados antiguos en el set `active` se omiten por rechazo de mantenimiento; no se eliminan automáticamente. Su presencia puede producir avisos hasta que el operador defina una política de archivo. No se ha agregado limpieza destructiva.

Los recibos de idempotencia viven dentro del agregado: no se trasladan ni reejecutan con la geometría nueva. Los desafíos/carta pendientes contienen posiciones y se conservan en ese estado rechazado. Redis Pub/Sub no almacena mensajes pendientes: cerrar conexiones y detener productores antiguos evita mezclar publicaciones en tránsito; los clientes descartan cachés locales de rutas y vuelven a sincronizar. Redis Streams `GameInitialized` no contiene coordenadas y mantiene esquema 1, grupo, correlación y deduplicación; no se purga ni se reescribe el stream. Su confirmación de asociación no autoriza interpretar posiciones antiguas.

Godot mantiene rutas HTTP/WS, JWT, nombres de mensajes y DTOs; debe actualizar posiciones/progresos y eliminar sus salidas físicas independientes. `HOME` sigue con posición `null`, progreso `-1`; `legalMoves.from` sigue `null` al liberar. `PIECE_CAPTURED` y `CARD_PIECE_RETURNED` conservan semántica. La migración rompe inevitablemente el contrato geométrico anterior; no se afirma compatibilidad completa sin probar el cliente real.

El layout v3-100 añade el regreso al mismo seguro propio al completar la vuelta. Posiciones físicas idénticas distinguen fases por progreso (salida 0, acceso 68); primera final 69, corona 76. Se incrementa el marcador respecto del borrador v2-100 para evitar reanudar sus progresos con otra semántica. No cambies manualmente `boardLayout` en un snapshot: sus fichas, desafíos y recibos deben conservar la versión original. Las rutas REST ya no son listas sin repeticiones; `[0]==[68]` y contienen 77 entradas.
