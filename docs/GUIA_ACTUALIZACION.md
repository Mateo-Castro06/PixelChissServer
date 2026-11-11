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
| Esquemas | Se mantienen estado de partida, CAS, versiones, DTO, JWT y evento `GameInitialized`. La migración modifica únicamente el catálogo de Game y Redis cambia de formato de persistencia, no de claves. |

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

La primera ejecución solo revisa. La segunda guarda Extended JSON antes de escribir, comprueba que no haya cambios concurrentes, inserta IDs nuevos sin sobreescribir documentos y retira solo las cartas y preguntas antiguas identificadas. Si encuentra IDs ajenos o definiciones editadas, se detiene para revisión. No borra colecciones. En caso de interrupción, conserva Game detenido y repite con **un nombre de respaldo nuevo**: los pasos se pueden repetir. No cargues un respaldo completo encima de una base activa. No arranques un Game antiguo contra el catálogo nuevo: desconoce los efectos nuevos y su semilla podría reinsertar IDs retirados.

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
