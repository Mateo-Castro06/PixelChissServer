# Actualización desde la versión anterior a PixelChiss con soporte LAN

Baseline: `5464aa22c62ce0a816612d0939ec778528f2aaf8`. Esta actualización conserva reglas, layout v4-100, estado Game, Redis Streams y SAGA. No modifica RuleEngine, GameState.Turn ni TriviaRules. Si un compañero parte de una revisión del motor anterior a esa baseline, también debe revisar el historial de compatibilidad de layouts; LAN no convierte partidas antiguas ni altera sus marcadores.

| Área | Baseline anterior | Versión LAN |
|---|---|---|
| Entrada de clientes | Gateway HTTP/WS, uso local | Mismo HTTP/WS local; HTTPS/WSS LAN opcional en el mismo Gateway |
| Puertos publicados de desarrollo | Bind sin restricción loopback explícita | Bind explícito 127.0.0.1; solo TLS de Gateway se publica en la IP privada elegida |
| OAuth nativo | Flujo Web local; tokens en callback | Nuevo Desktop + PKCE; callback del jugador con código y canje JSON; Web local cambia a handoff de un uso |
| Configuración | .env, claves JWT y Compose base | Se conservan; LAN agrega configuración Desktop y lan.env/certificados privados |
| Persistencia | Usuarios, refresh, salas/partidas/catálogo | Mismos datos y volúmenes; una tabla aditiva login_attempts en Auth |
| Motor y coordinación | Reglas verificadas, Redis Streams/SAGA | Sin cambios funcionales ni migración Game |

## Respuestas explícitas para el equipo

| Pregunta | Respuesta |
|---|---|
| ¿Basta con git pull? | Para actualizar el backend **local**, pull y reconstrucción/recreación de Auth/Gateway y servicios con publicaciones cambiadas; no se necesita una IP. Para **activar LAN**, no basta: exige Desktop OAuth, certificados, firewall y un cliente Godot actualizado. |
| ¿Editar .env? | No se sobrescribe. Local conserva las variables existentes. LAN necesita agregar GOOGLE_DESKTOP_CLIENT_ID y, si Google lo requiere para ese cliente, GOOGLE_DESKTOP_CLIENT_SECRET. La herramienta crea otro archivo privado lan-secrets/lan.env para dirección/puerto/contraseña TLS. |
| ¿Reconstruir? | Sí, Auth y Gateway. Recrear con up -d para aplicar los bindings loopback a los demás servicios; sus datos/volúmenes se conservan. Game/Lobby no necesitan reconstrucción por LAN. |
| ¿Cambiar Docker? | Solo si es demasiado antiguo: LAN exige Engine >=28 y Compose >=2.24.4. Windows usa Docker Desktop con contenedores Linux; Linux Engine/Compose. No se necesita editar daemon.json ni activar host networking. |
| ¿Configurar firewall? | Sí para LAN: alcance de IP/puerto/subred, con reglas reversibles del manual. En Linux UFW solo no filtra publicaciones Docker; usar DOCKER-USER con iptables o equivalente documentado para nftables. En Windows reglas Private con bloqueo fuera de subred. No se aplican automáticamente. |
| ¿Cambiar Google OAuth/certificados? | LAN requiere un cliente Google Desktop real, sin sustituir el Web client ni su redirect por una IP. El administrador Google lo configura; cada anfitrión instala su configuración. Generar CA/certificado por anfitrión y distribuir solo ca.crt verificada a Godot. No se requieren certificados públicos o instalación global de CA si Godot confía en ella explícitamente. |
| ¿Migrar bases de datos? | Redis/Mongo/Game: no. Auth agrega la tabla transitoria login_attempts con índice de expiración en PostgreSQL usando el ddl-auto=update existente. No cambia users/refresh_tokens. Si el despliegue administra DDL aparte, aplicar el SQL aditivo provisto, sin borrar tablas. |
| ¿Se conservan volúmenes, usuarios y partidas? | Sí en el mismo anfitrión y proyecto Compose. Conservar también las claves JWT y .env. Otro anfitrión tiene otra persistencia; no hay traslado automático de partidas/usuarios. No cambiar nombres de proyecto para actualizar. |
| ¿Se puede seguir como antes sin LAN? | Sí: mismo Compose base y HTTP/WS por 127.0.0.1:8080, bots y mapas. Los puertos de desarrollo pasan a loopback. Hay un cambio necesario en el login Godot: por seguridad ya no devuelve tokens en la URL; requiere handoff PKCE o el nuevo Desktop flow. Un binario Godot antiguo que esperaba tokens en callback debe actualizarse. |

## Variables y tareas manuales

| Variable | Predeterminado | Obligación / quién |
|---|---|---|
| GOOGLE_DESKTOP_CLIENT_ID | vacío: Desktop devuelve 503 | Obligatoria para LAN; anfitrión usa el Desktop client creado por administrador Google. No inventarlo. |
| GOOGLE_DESKTOP_CLIENT_SECRET | vacío | Solo si ese Desktop client lo requiere; privado del anfitrión, nunca Godot. |
| LAN_BIND_ADDRESS | sin valor | Obligatoria LAN; generada con la IP privada de **cada anfitrión**, no localhost ni bridge Docker. |
| LAN_HTTPS_PORT | 8443 | Opcional cambiar; el cliente y firewall deben coincidir. |
| LAN_TLS_DIRECTORY | lan-secrets | Directorio privado generado para cada anfitrión; no copiar una ruta ajena. Si se mueve el checkout, corregir esta ruta en lan.env. |
| LAN_TLS_PASSWORD | sin valor | Obligatoria LAN; generada aleatoriamente por anfitrión en lan-secrets/lan.env. No copiar a un documento. |
| GATEWAY_PORT | 8080 | Opcional local; Godot usa este puerto con host loopback. |
| AUTH_PORT / LOBBY_PORT / EUREKA_PORT | 8081 / 8083 / 8761 | Opcionales para herramientas del anfitrión; siempre loopback, no clientes LAN. |
| POSTGRES_PORT / MONGO_PORT / REDIS_PORT | 5432 / 27017 / 6379 | Opcionales para desarrollo local; siempre loopback. No cambian los puertos internos Docker. |
| GAME_ALLOWED_ORIGINS | conserva valor existente | Godot nativo puede omitir Origin. Para navegadores agregar orígenes exactos de forma consciente; no usar comodines. |
| OAUTH_REDIRECT_URI | conserva URI Web anterior | No cambiar para Desktop/LAN. Cambiarla solo si se reconfigura también el cliente Google Web local. |

No copiar .env.example sobre .env. El nuevo lan.env **complementa** el .env existente; no contiene los secretos PostgreSQL/Google/JWT previos. Cada anfitrión tiene su propia IP, contraseña/clave TLS y persistencia. Para cambiar solo la dirección en Godot, usar una CA pública común de equipo con claves de servidor distintas, siguiendo request/sign/install; su clave CA queda solo con el administrador. Con CAs independientes se necesita importar su confianza al conectar por primera vez. El Desktop client puede ser compartido por el equipo según su proyecto Google. Los invitados no necesitan .env ni Docker: necesitan Godot actualizado, la dirección, puerto y CA pública verificada.

## Actualización simple (seguir local)

Desde la raíz, conservar cambios propios y respaldos antes de actualizar. Mantener el proyecto Compose existente (`docker` por defecto si se usó este archivo); si usabas -p, repetir el mismo nombre.

```sh
git status
git pull --ff-only
docker compose -f infrastructure/docker/docker-compose.yml build auth-service api-gateway
docker compose -f infrastructure/docker/docker-compose.yml up -d
docker compose -f infrastructure/docker/docker-compose.yml restart game-service
docker compose -f infrastructure/docker/docker-compose.yml ps
```

En Windows PowerShell estos comandos son iguales. No es obligatorio editar .env para seguir local. La recreación aplica restricciones de publicación también a las bases ya existentes: no recrea sus volúmenes. Reiniciar Game después del primer up de actualización: sus conexiones persistentes pueden conservar direcciones antiguas de Redis/Mongo al recrear dependencias. No cambia su imagen ni reglas; recarga el estado persistido. Si los clientes estaban conectados, deben reconectar/sincronizar tras la ventana de mantenimiento. Actualizar Godot para el retorno OAuth seguro; el resto de acciones/WS/reglas se mantiene.

## Activar LAN: configuración manual obligatoria

1. Administrador Google: crear Desktop app, consentimiento y usuarios de prueba; cada anfitrión agrega las variables Desktop reales.
2. Anfitrión: comprobar versiones Docker, identificar IP privada y subred reales.
3. Generar certificados con `python3 infrastructure/lan/lan.py init --host DIRECCION_PRIVADA` (Windows: `py -3`). No reemplaza archivos existentes.
4. Aplicar las reglas de firewall específicas generadas por la herramienta y verificar su alcance; no basta con tener Wi-Fi con contraseña.
5. Compartir únicamente ca.crt y verificar huella por canal de confianza; configurar su confianza en Godot sin omitir TLS/SAN.
6. Ejecutar `lan.py check --lan` y los comandos con **ambos env-files y ambos archivos Compose** del [manual LAN](MANUAL_SERVIDOR_LAN.md).
7. Equipo Godot: implementar selección de conexión, HTTPS/WSS y OAuth Desktop con loopback del jugador según [GODOT_INTEGRACION_LAN.md](GODOT_INTEGRACION_LAN.md). El anfitrión del backend puede ser un jugador más.

Opcional: reservar IP por DHCP, recordar dirección/CA pública en Godot, monitorización existente y persistencia segura del refresh en el almacén del sistema operativo. No guardar refresh en ConfigFile.

## Checklist operativa de actualización

Todos los comandos se ejecutan desde la raíz. Si tu proyecto Compose tiene otro nombre, añadir el mismo `-p NOMBRE_EXISTENTE` a **cada** comando Compose. `docker compose ls -a` permite identificarlo; no crear otro proyecto para actualizar.

- [ ] **Todos los anfitriones:** revisar `git status`, conservar cambios ajenos, `.env`, claves y respaldos de volúmenes; ejecutar `git pull --ff-only`. Si hay divergencias, resolverlas sin force/reset destructivo.
- [ ] **Todos los anfitriones:** ejecutar `docker version` y `docker compose version`. Para LAN exigir Engine >=28 y Compose >=2.24.4; actualizar Docker Desktop/Engine únicamente si hace falta. Mantener contenedores Linux en Windows. No es necesario cambiar redes internas o daemon.json.
- [ ] **Actualización local:** ejecutar el bloque «Actualización simple» anterior. No añadir variables LAN ni certificados/firewall para seguir local. Actualizar también el cliente Godot si usaba el callback con tokens.
- [ ] **Anfitrión LAN:** administrador Google crea/configura Desktop app y consentimiento/usuarios de prueba; anfitrión agrega `GOOGLE_DESKTOP_CLIENT_ID` real a `.env` y `GOOGLE_DESKTOP_CLIENT_SECRET` solo si lo requiere ese cliente. Conservar el Web client, `OAUTH_REDIRECT_URI` y secretos existentes.
- [ ] **Anfitrión LAN:** preparar certificado para su IP actual. Preferir la CA común del equipo mediante request/sign/install del manual; `init` es para crear una CA nueva. No ejecutar ambos sobre el mismo directorio ni sobre certificados existentes.

Linux, para el primer anfitrión que crea la CA (variables interactivas, sin IP fija):

```bash
ip -brief address
read -r -p 'IPv4 privada del anfitrión: ' lan_host
read -r -p 'Subred CIDR permitida: ' lan_subnet
docker pull maven:3.9-eclipse-temurin-21
python3 infrastructure/lan/lan.py init --host "$lan_host"
python3 infrastructure/lan/lan.py firewall --host "$lan_host" --subnet "$lan_subnet" --platform linux
```

Windows PowerShell, equivalente:

```powershell
Get-NetIPConfiguration
$lanHost = Read-Host 'IPv4 privada del anfitrión'
$lanSubnet = Read-Host 'Subred CIDR permitida'
docker pull maven:3.9-eclipse-temurin-21
py -3 infrastructure/lan/lan.py init --host $lanHost
py -3 infrastructure/lan/lan.py firewall --host $lanHost --subnet $lanSubnet --platform windows
```

- [ ] **Firewall, manual obligatorio:** revisar y aplicar con privilegios las reglas impresas, guardar sus comandos de reversión. Linux: comprobar DOCKER-USER y backend iptables; Windows: comprobar perfil Private y reglas Docker Desktop. La herramienta no aplica ninguna regla. Si personalizas el puerto TLS, usar el mismo `--port` en certificados y firewall.
- [ ] **Certificados, manual obligatorio:** entregar únicamente `ca.crt` a los jugadores y verificar huella DER SHA256 por canal autenticado. Conservar claves/lan.env en privado. No es obligatorio instalar la CA globalmente: Godot debe cargarla para HTTP y WS.
- [ ] **Anfitrión LAN:** comprobar configuración e iniciar el mismo backend; comandos reales comunes a ambos sistemas (primera línea Windows: `py -3`):

```sh
python3 infrastructure/lan/lan.py check --lan
docker compose --env-file infrastructure/docker/.env --env-file infrastructure/docker/lan-secrets/lan.env -f infrastructure/docker/docker-compose.yml -f infrastructure/docker/docker-compose.lan.yml build auth-service api-gateway
docker compose --env-file infrastructure/docker/.env --env-file infrastructure/docker/lan-secrets/lan.env -f infrastructure/docker/docker-compose.yml -f infrastructure/docker/docker-compose.lan.yml up -d
docker compose --env-file infrastructure/docker/.env --env-file infrastructure/docker/lan-secrets/lan.env -f infrastructure/docker/docker-compose.yml -f infrastructure/docker/docker-compose.lan.yml restart game-service
docker compose --env-file infrastructure/docker/.env --env-file infrastructure/docker/lan-secrets/lan.env -f infrastructure/docker/docker-compose.yml -f infrastructure/docker/docker-compose.lan.yml ps
```

- [ ] **Verificación local:** `curl --fail http://127.0.0.1:8080/actuator/health` (Windows: `curl.exe`). Esperar UP y salud de servicios/bases. Ajustar si personalizaste GATEWAY_PORT.
- [ ] **Verificación desde otro computador:** con la CA pública verificada, ejecutar `curl --fail --cacert ca.crt https://DIRECCION_PRIVADA:8443/actuator/health` (Windows: `curl.exe`); sustituir dirección y puerto reales. No usar `-k`. Verificar que los puertos internos no sean accesibles desde el invitado.
- [ ] **Prueba de usuario obligatoria antes de dar LAN por validado en el equipo:** dos cuentas Google reales independientes, sala compartida, partida en ambos mapas, bots local, refresh/logout y reconexión. Godot real, Windows anfitrión y dos computadores físicos siguen pendientes de verificación; los probes simulados registrados no acreditan esas pruebas.
- [ ] **Datos:** no ejecutar migraciones Redis/Mongo por LAN. PostgreSQL agrega login_attempts automáticamente; SQL manual solo si el administrador gestiona el esquema. Comprobar usuarios/partidas preexistentes sin borrar bases ni volúmenes.

Los compañeros que solo ejecutan Godot **no ejecutan estos comandos de servidor**, no necesitan Docker, `.env`, secretos Google ni migraciones. Instalan el cliente actualizado, configuran CA pública/dirección/puerto LAN y se autentican en su propio navegador. El anfitrión puede usar ese mismo cliente con la opción local sin introducir IP. Una instalación nueva de servidor necesita además preparar claves y secretos de base: ver los pasos separados Windows/Linux del [manual LAN](MANUAL_SERVIDOR_LAN.md#2-preparar-repositorio-y-datos).

Opcionales: reserva DHCP, puerto distinto, recordar dirección, monitorización y almacén seguro para refresh. Si se mueve la carpeta del repositorio, corregir `LAN_TLS_DIRECTORY` del lan.env generado. Si cambia la IP del anfitrión, reemitir su certificado con el SAN correcto; con la CA común, los clientes conservan su confianza. No trasladar automáticamente la persistencia entre anfitriones.

## Verificaciones posteriores y recuperación

Comprobar salud, registro de servicios, REST autenticado/401 sin JWT y WebSocket. Probar local contra bots y dos jugadores LAN en ambos mapas, login Google real, renovación/logout y reconexión. Tras recreación puede haber errores transitorios de descubrimiento; esperar disponibilidad sin desactivar autenticación. Las pruebas automatizadas y en una IP no loopback no sustituyen una partida física Windows↔Linux.

Si falla LAN, detener solo ese proyecto con los mismos archivos y `stop`; conservar volúmenes y certificados. Volver a local con el Compose base y `up -d`, y retirar únicamente las reglas de firewall generadas para PixelChiss si ya no hacen falta. Para revertir código, usar la revisión anterior en un checkout y reconstruir servicios con el mismo proyecto/volúmenes; la tabla login_attempts adicional puede permanecer y el motor usa el mismo estado. La versión anterior vuelve a exponer tokens en el callback: no usar esa autenticación como solución LAN segura. No usar down -v, prune, reset de bases ni cambios manuales al estado Game.

SQL opcional para instalaciones con DDL gestionado: [login-attempts.sql](../infrastructure/lan/login-attempts.sql). Las instalaciones con ddl-auto=update no requieren ejecutarlo manualmente. Revisar permisos de esquema si Auth no puede crear la tabla; no borrar datos para arrancar.

---

## Historial de actualizaciones anteriores

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


### Actualización de pares y temporizadores

Un par concede otra tirada una vez resuelto. Si quedan fichas HOME, libera obligatoriamente hasta 4 con (1,1), 3 con (6,6) o 2 con los demás pares; consume ambos dados incluso si libera menos. No se puede mover con esos dados. Sin HOME, admite suma o uso separado. Se conservan los tres intentos HOME y la captura especial de salida. Tres pares conceden coronación de una ficha propia activa previa (recorrido o corredor); el jugador conserva la selección CROWN_REQUEST y después termina el turno, sin cuarta tirada ni penalización por tres pares.

Movimiento: 30 segundos tras confirmar cada animación (máximo 10 segundos para confirmar). Las liberaciones no abren reloj de movimiento. Trivia: 15 segundos independientes; pausa el saldo de movimiento y lo reanuda sin reiniciarlo. Sin respuesta se resuelve como incorrecta; se rechazan respuestas tardías. Al vencer movimiento se conservan jugadas aceptadas, se descartan dados pendientes, se evalúa SOPLAR solo por oportunidades reales omitidas y pasa turno sin lanzamiento extra pendiente.

Godot debe reconocer HOME_RELEASE_RESOLVED, los dados consumidos y ROLL_AVAILABLE, además de MOVEMENT_TIMER_PAUSED/RESUMED y trivia.deadline. Reconectar mantiene contadores, saldo y plazos del servidor. Véase [contrato WebSocket](../game-service/docs/WEBSOCKET.md). No cambia el tablero v4-100, el catálogo ni las acciones existentes. Los campos nuevos son aditivos; tiradas/trivias ya persistidas antes de actualizar conservan su estado, y las nuevas reglas se aplican desde la siguiente activación, sin borrar partidas o volúmenes.
