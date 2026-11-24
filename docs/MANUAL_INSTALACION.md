# Manual de instalación y configuración de PixelChiss Server

Este manual permite instalar el backend de PixelChiss en un anfitrión Linux o Windows y ofrecer HTTPS/WSS a clientes Godot de la misma LAN. El cliente se obtiene por separado. Los invitados solo necesitan el cliente y acceso a Google para autenticarse.

**Directorio de trabajo:** después de descargar el proyecto, ejecuta todos los bloques desde la raíz `PixelChissServer/`, salvo que se indique otra ruta. Los bloques Bash corresponden a Linux; los PowerShell, a Windows. Los valores entre `<...>` son marcadores: sustitúyelos antes de ejecutar una instrucción.

## 1. Requisitos del equipo

| Requisito obligatorio | Condición |
|---|---|
| Sistema | Linux con interfaz física de red, o Windows compatible con Docker Desktop y contenedores Linux. |
| Docker | Engine **28 o posterior**, ejecutándose en el mismo anfitrión; no se admite contexto remoto ni rootless en el asistente LAN. |
| Compose | **2.24.4 o posterior**, comando `docker compose`; el override LAN usa `!override`. |
| Git y Python | Git disponible en PATH y Python **3.9 o posterior**. Python estándar basta para `lan.py`. |
| Red | Interfaz física activa con IPv4 privada RFC1918 y subred detectables; los jugadores deben poder alcanzar esa red. VPN, loopback y bridges Docker no son candidatos. |
| Internet | Necesario para clonar, descargar imágenes/dependencias y usar Google OAuth. El login no funciona sin acceso a Google. |
| Permisos | Acceso al daemon Docker con tu usuario y autorización administrativa para comprobar/aplicar firewall. |
| Linux | `ip` de iproute2, `sudo` y backend Docker verificable mediante iptables/DOCKER-USER o nftables. OpenSSL para preparar JWT con este manual. |
| Windows | Python accesible mediante `py -3`, Windows PowerShell (`powershell.exe`) para la automatización y **PowerShell 7.2 o posterior** para generar JWT con las API .NET de este manual. |
| Identidad | Proyecto Google autorizado, credenciales OAuth Web y Desktop, par JWT y secretos propios de esta instalación. |

El repositorio no fija mínimos comprobados de RAM o disco. Prevé recursos para compilar cinco imágenes de aplicación, descargar sus bases y mantener los volúmenes persistentes; comprueba también los requisitos del proveedor Docker de tu sistema.

**Opcionales:** curl para consultas manuales, un editor de texto y el perfil de monitoreo Prometheus/Grafana. Java 21, Maven y Python 3.12 con las librerías de Lobby están en los Dockerfiles; no necesitas instalarlos en el anfitrión para ejecutar todo con Docker.

## 2. Instalación de dependencias

### 2.1 Linux

1. En Ubuntu o una distribución compatible con APT, desde cualquier directorio:

   ```bash
   sudo apt update
   sudo apt install git python3 openssl ca-certificates iproute2 sudo iptables nftables
   ```

2. Instala Docker Engine, Buildx y el plugin Docker Compose siguiendo la [guía oficial para Ubuntu](https://docs.docker.com/engine/install/ubuntu/) o la [guía para tu distribución](https://docs.docker.com/engine/install/). En Mint usa su base Ubuntu compatible, como indica el proveedor. Si ya tienes Docker, comprueba versiones y acceso antes de reinstalarlo.
3. En un Linux con systemd, inicia el daemon si está detenido:

   ```bash
   sudo systemctl start docker
   ```

4. Comprueba que tu usuario puede ejecutar Docker. Si `docker info` devuelve permiso denegado, configura el acceso según la instalación de Docker de tu sistema. Ejecuta el asistente con tu usuario: solicitará sudo únicamente para el firewall.

   ```bash
   git --version
   python3 --version
   openssl version
   ip -Version
   docker version
   docker compose version
   docker info
   ```

Conserva el firewall administrado por Docker. El asistente rechaza una configuración con routing directo habilitado o firewall Docker desactivado. En Linux necesita verificar DOCKER-USER al inicio de FORWARD o el backend nftables Docker; no basta una autorización UFW.

### 2.2 Windows

1. Instala Git y Python 3.9 o posterior, con Git y el lanzador `py` disponibles en la terminal.
2. Instala [Docker Desktop para Windows](https://docs.docker.com/desktop/setup/install/windows-install/) con los requisitos de virtualización/WSL2 que indique el proveedor. Inícialo y selecciona **contenedores Linux**.
3. Para una instalación sin claves, instala PowerShell 7.2 o posterior y abre esa terminal para los bloques de preparación criptográfica. `powershell.exe` también debe estar disponible: el asistente lo usa para consultas de red y firewall.
4. Desde cualquier directorio, comprueba:

   ```powershell
   $PSVersionTable.PSVersion
   git --version
   py -3 --version
   docker version
   docker compose version
   docker info
   ```

La versión de **servidor** mostrada por `docker version` debe ser al menos 28. No hace falta Bash, OpenSSL ni un JDK local en este procedimiento Windows. El asistente solicita elevación UAC cuando necesita modificar el firewall; no ejecutes toda la instalación como administrador.

## 3. Descarga del repositorio

Desde el directorio donde quieras guardar el clon, en Bash o PowerShell:

```sh
git clone https://github.com/Mateo-Castro06/PixelChissServer.git
cd PixelChissServer
git status --short --branch
```

Si GitHub exige acceso, utiliza una cuenta autorizada. No insertes tokens en la URL. A partir de aquí permanece en la raíz del clon.

Estas son las rutas que usarás:

| Ruta relativa a la raíz | Uso en la instalación |
|---|---|
| `infrastructure/docker/.env.example` | Plantilla versionada de variables. |
| `infrastructure/docker/.env` | Configuración privada que prepararás. |
| `auth-service/keys/` | Claves JWT de esta instalación. |
| `api-gateway/keys/` | Copia de la clave pública JWT. |
| `infrastructure/docker/docker-compose.yml` | Despliegue base y volúmenes. |
| `infrastructure/docker/docker-compose.lan.yml` | Publicación HTTPS/WSS. |
| `infrastructure/lan/lan.py` | Asistente de inicio y diagnóstico. |
| `infrastructure/docker/lan-secrets/` | Configuración y certificados LAN generados. |

## 4. Preparación de archivos de configuración

<a id="despliegue-completo"></a>

1. Si este anfitrión ya tiene `.env`, claves o datos, conserva sus archivos y pasa a la revisión de variables. Los pasos de generación de la sección 5 son para una instalación nueva.
2. Copia la plantilla **solo si no existe** `.env`.

   Linux:

   ```bash
   if [ ! -e infrastructure/docker/.env ]; then
     cp infrastructure/docker/.env.example infrastructure/docker/.env
   fi
   chmod 600 infrastructure/docker/.env
   ```

   Windows, PowerShell:

   ```powershell
   if (!(Test-Path infrastructure/docker/.env)) {
       Copy-Item infrastructure/docker/.env.example infrastructure/docker/.env
   }
   notepad infrastructure/docker/.env
   ```

3. Edita `.env` con tu editor. Guarda como UTF-8. Completa los valores de la sección siguiente y conserva las referencias `${...}` del ejemplo. En Windows restringe el archivo al usuario anfitrión mediante Propiedades → Seguridad.
4. No crees ni copies `lan.env` de otro equipo. El primer `start --auto` generará la configuración TLS en la ruta de este clon.

`.env`, las carpetas de claves y `lan-secrets/` están excluidos de Git. Evita almacenarlos en carpetas compartidas o publicarlos en capturas, logs o informes.

## 5. Configuración de credenciales y variables

### 5.1 Secretos y conexiones internas

| Variable de `.env` | Qué configurar |
|---|---|
| `POSTGRES_DB`, `POSTGRES_USER` | Puedes conservar `pixelchiss` en una instalación nueva. |
| `POSTGRES_PASSWORD` | Reemplaza `change_me` por una contraseña privada aleatoria. |
| `DB_HOST`, `DB_PORT` | Conserva `postgres` y `5432`: son la dirección y el puerto dentro de Docker. |
| `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | Conserva `${POSTGRES_DB}`, `${POSTGRES_USER}`, `${POSTGRES_PASSWORD}`. Compose transmite a Auth la contraseña de `POSTGRES_PASSWORD`. |
| `EUREKA_URL` | Conserva `http://eureka-server:8761/eureka/`. |
| `MONGODB_URI` | Conserva `mongodb://mongodb:27017/pixelchiss`. |
| `REDIS_URL` | Conserva `redis://redis:6379/0`. Lobby usa DB 1 por defecto; no cambies las bases para resolver conectividad. |
| `GAME_LOBBY_TOKEN` | Secreto aleatorio de al menos 32 caracteres, compartido internamente por Game y Lobby a través del mismo `.env`. |
| `JWT_PRIVATE_KEY_PATH` | Conserva `/app/keys/private_key.pem`. |
| `JWT_PUBLIC_KEY_PATH` | Conserva `/app/keys/public_key.pem`. |
| `GAME_SEED_ENABLED` | `true` para sembrar el catálogo en una instalación nueva. `false` si administras un catálogo existente que no debe repoblarse. |
| `REDIS_APPENDONLY` | Conserva `auto`; una instalación nueva utiliza AOF. No cambies la persistencia de datos existentes como parte de un reinicio. |
| `GAME_ALLOWED_ORIGINS` | Conserva el valor inicial para Godot nativo, que puede omitir Origin. Los clientes con Origin deben usar una lista explícita de orígenes autorizados. |

Para generar contraseña PostgreSQL y token interno **solo sobre los marcadores originales de una instalación nueva**, ejecuta uno de estos bloques. No imprime los secretos.

Linux:

```bash
python3 - <<'PY'
import secrets
from pathlib import Path
path = Path('infrastructure/docker/.env')
text = path.read_text(encoding='utf-8-sig')
if 'POSTGRES_PASSWORD=change_me\n' not in text or 'GAME_LOBBY_TOKEN=\n' not in text:
    raise SystemExit('Conserva la configuración existente; los marcadores iniciales no coinciden.')
text = text.replace('POSTGRES_PASSWORD=change_me\n', 'POSTGRES_PASSWORD=' + secrets.token_hex(24) + '\n')
text = text.replace('GAME_LOBBY_TOKEN=\n', 'GAME_LOBBY_TOKEN=' + secrets.token_hex(32) + '\n')
path.write_text(text, encoding='utf-8')
PY
```

Windows, **PowerShell 7.2 o posterior**:

```powershell
$envPath = Join-Path (Get-Location).Path 'infrastructure/docker/.env'
$template = [IO.File]::ReadAllText($envPath).Replace("`r`n", "`n")
if (!$template.Contains("POSTGRES_PASSWORD=change_me`n") -or !$template.Contains("GAME_LOBBY_TOKEN=`n")) {
    throw 'Conserva la configuración existente; los marcadores iniciales no coinciden.'
}
$dbPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$lobbyToken = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$template = $template.Replace("POSTGRES_PASSWORD=change_me`n", "POSTGRES_PASSWORD=$dbPassword`n")
$template = $template.Replace("GAME_LOBBY_TOKEN=`n", "GAME_LOBBY_TOKEN=$lobbyToken`n")
[IO.File]::WriteAllText($envPath, $template)
Remove-Variable dbPassword, lobbyToken, template
```

Si los marcadores no coinciden, revisa la configuración en el editor. No regeneres credenciales sobre volúmenes existentes: cambiar `.env` no cambia por sí solo la contraseña de una base PostgreSQL ya inicializada.

### 5.2 Claves JWT

Genera un par RSA propio de este anfitrión. La privada debe ser PKCS#8 PEM sin contraseña y la pública SubjectPublicKeyInfo PEM. Si existe cualquiera de estos archivos, no ejecutes generación: conserva o recupera el par correcto.

Linux, desde la raíz:

```bash
(
  set -eu
  for path in auth-service/keys/private_key.pem auth-service/keys/public_key.pem api-gateway/keys/public_key.pem; do
    if [ -e "$path" ]; then
      echo "Preparación cancelada: ya existe $path; conserva las claves."
      exit 1
    fi
  done
  umask 077
  mkdir -p auth-service/keys api-gateway/keys
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out auth-service/keys/private_key.pem
  openssl pkey -in auth-service/keys/private_key.pem -pubout -out auth-service/keys/public_key.pem
  cp auth-service/keys/public_key.pem api-gateway/keys/public_key.pem
  chmod 600 auth-service/keys/private_key.pem
  chmod 644 auth-service/keys/public_key.pem api-gateway/keys/public_key.pem
  cmp auth-service/keys/public_key.pem api-gateway/keys/public_key.pem
)
```

`cmp` debe terminar sin salida, con código 0.

Windows, **PowerShell 7.2 o posterior**, desde la raíz:

```powershell
$privatePath = Join-Path (Get-Location).Path 'auth-service/keys/private_key.pem'
$publicPath = Join-Path (Get-Location).Path 'auth-service/keys/public_key.pem'
$gatewayPublicPath = Join-Path (Get-Location).Path 'api-gateway/keys/public_key.pem'
foreach ($path in @($privatePath, $publicPath, $gatewayPublicPath)) {
    if (Test-Path $path) { throw "Preparación cancelada: ya existe $path; conserva las claves." }
}
New-Item -ItemType Directory -Force auth-service/keys, api-gateway/keys | Out-Null
$rsa = [Security.Cryptography.RSA]::Create(2048)
try {
    [IO.File]::WriteAllText($privatePath, $rsa.ExportPkcs8PrivateKeyPem())
    [IO.File]::WriteAllText($publicPath, $rsa.ExportSubjectPublicKeyInfoPem())
    [IO.File]::WriteAllText($gatewayPublicPath, $rsa.ExportSubjectPublicKeyInfoPem())
} finally { $rsa.Dispose() }
if ([IO.File]::ReadAllText($publicPath) -ne [IO.File]::ReadAllText($gatewayPublicPath)) {
    throw 'Las claves públicas no coinciden.'
}
```

En Windows restringe `auth-service/keys/` al usuario anfitrión mediante las propiedades de Seguridad. Solo Auth recibe la privada. Gateway usa su copia pública; Game y Lobby montan la pública de Auth. No copies claves privadas de otro anfitrión con datos independientes.

### 5.3 Google OAuth obligatorio para el uso previsto

Desde la consola de tu proyecto Google autorizado:

1. Configura la pantalla de consentimiento y las cuentas de prueba si la aplicación está en modo de pruebas. El login solicita `openid`, `profile` y `email`.
2. Crea o conserva un cliente OAuth de tipo **Web application**. Introduce su identificador en `GOOGLE_CLIENT_ID` y su secreto en `GOOGLE_CLIENT_SECRET`, dentro de `.env`. Estos valores siguen siendo requeridos por la configuración de Auth.
3. Registra exactamente `http://localhost:8081/login/oauth2/code/google` como URI de redirección Web si conservas el valor predeterminado. `OAUTH_REDIRECT_URI` puede permanecer comentada. Si personalizas ese flujo, el valor configurado y la URI registrada deben coincidir, incluido el puerto.
4. Crea un cliente distinto de tipo **Desktop app**. Introduce el identificador real en `GOOGLE_DESKTOP_CLIENT_ID` (termina en `.apps.googleusercontent.com`). Si el canje de ese cliente requiere secreto, configura `GOOGLE_DESKTOP_CLIENT_SECRET` solo en el anfitrión.
5. Conserva el callback Desktop en el loopback dinámico del **jugador**, `http://127.0.0.1:<PUERTO_DEL_JUGADOR>/callback`. No lo cambies por la IP del servidor ni uses el identificador Web como Desktop.

El asistente exige `GOOGLE_DESKTOP_CLIENT_ID` antes de iniciar LAN; no crea ni verifica las credenciales en la consola de Google. La comprobación definitiva exige un login real. El cliente necesita OAuth Desktop con PKCE y un navegador del sistema. Referencia de configuración: [Google OAuth para aplicaciones de escritorio](https://developers.google.com/identity/protocols/oauth2/native-app).

## 6. Configuración de Docker Compose

1. Mantén los archivos Compose del repositorio. No cambies nombres internos por la IP LAN.
2. Identifica el proyecto si ya existía:

   ```sh
   docker compose ls -a
   ```

   Los ejemplos usan `docker`. En un clon nuevo es el valor predeterminado de `lan.py` en ambos sistemas. Si ya utilizabas otro nombre, reemplaza `docker` en **todos** los argumentos `--project` y `-p`. No crees otro proyecto para reutilizar una instalación.
3. Comprueba el Compose base sin imprimir valores privados:

   ```sh
   docker compose -p docker --env-file infrastructure/docker/.env -f infrastructure/docker/docker-compose.yml config --quiet
   ```

4. El asistente combinará el Compose base y `docker-compose.lan.yml` con `.env` y el `lan-secrets/lan.env` generado. No uses un `up` con solo el archivo base para administrar una instalación LAN: omitiría su configuración TLS.

Publicaciones predeterminadas:

| Dirección en el anfitrión | Puerto | Configuración |
|---|---|---|
| IPv4 privada seleccionada | 8443/TCP HTTPS y WSS | Generado como `LAN_HTTPS_PORT`; el asistente acepta `--port`. |
| `127.0.0.1` | 8080 HTTP/WS local | `GATEWAY_PORT` |
| `127.0.0.1` | 8081 Auth, 8083 Lobby, 8761 Eureka | `AUTH_PORT`, `LOBBY_PORT`, `EUREKA_PORT` |
| `127.0.0.1` | 5432 PostgreSQL, 27017 MongoDB, 6379 Redis | `POSTGRES_PORT`, `MONGO_PORT`, `REDIS_PORT` |
| `127.0.0.1` | 9090 Prometheus, 3000 Grafana, solo con monitoreo | Perfil opcional `monitoring` |

Game usa 8084 internamente y no lo publica en el anfitrión. Cambiar `POSTGRES_PORT` u otro puerto publicado no cambia los puertos internos ni los nombres DNS Docker. Solo el Gateway TLS debe recibir conexiones LAN; no abras las bases o los puertos administrativos a los jugadores.

El monitoreo no es necesario para jugar. Si se activa, requiere `PIXELCHISS_METRICS_KEY` aleatoria de al menos 32 caracteres y `GRAFANA_ADMIN_PASSWORD` privada en `.env`. Para activarlo **después de un inicio LAN correcto**, en Bash o PowerShell:

```sh
docker compose -p docker --env-file infrastructure/docker/.env --env-file infrastructure/docker/lan-secrets/lan.env -f infrastructure/docker/docker-compose.yml -f infrastructure/docker/docker-compose.lan.yml --profile monitoring up -d
```

Los siguientes inicios automáticos conservan el monitoreo si ya existía.

## 7. Configuración y ejecución en LAN

1. Conecta anfitrión y jugadores a una red autorizada que permita tráfico entre equipos. No habilites port forwarding del router. Para consultar candidatas sin cambiar la configuración:

   Linux:

   ```bash
   python3 infrastructure/lan/lan.py network
   ```

   Windows:

   ```powershell
   py -3 infrastructure/lan/lan.py network
   ```

2. Si hay varias interfaces físicas, el inicio pedirá elegir. También acepta `--interface` o `--host` con valores que aparezcan en la consulta. No supongas un prefijo `/24`: usa la subred detectada.
3. En la primera ejecución, el flujo habitual es una **CA individual por anfitrión**, que se crea al confirmar `CREAR CA`. El asistente generará `lan-secrets/lan.env` con `LAN_BIND_ADDRESS`, `LAN_HTTPS_PORT`, `LAN_TLS_DIRECTORY` y `LAN_TLS_PASSWORD`, además de `ca.crt`, `ca.p12`, `server.p12`, `server.crt` y la CSR. No edites estos valores para seguir el flujo automático.
4. El asistente verifica permisos privados: en Linux, directorio 700 y `lan.env`/PKCS12 600, del usuario anfitrión. En Windows, prepara el acceso de `lan-secrets/` desde Propiedades → Seguridad sin concesiones a Everyone, Users o Authenticated Users; si los archivos nuevos heredan permisos amplios, corrígelos y repite el inicio. No se corrigen ACL existentes automáticamente.
5. Revisa la IP/subred mostradas y las solicitudes de autorización. Linux puede pedir contraseña sudo; Windows puede mostrar UAC. El asistente limita las reglas a la IP, puerto, subred y perfil autorizados. No cambia el perfil de red ni desactiva el firewall. No existe una opción para aceptar todos los controles automáticamente.

El Gateway monta únicamente `server.p12` y la CA **pública** `ca.crt`, en solo lectura. `ca.p12`, la contraseña, `lan.env` y los respaldos permanecen privados. `auto-state.json` guarda el estado de la automatización; `.backups/` conserva respaldos privados y `firewall-undo.txt` describe cómo retirar las reglas propias. No ejecutes la reversión mientras aceptas jugadores.

**CA común ya administrada, opcional:** en un anfitrión nuevo el inicio acepta `--ca-certificate` con la ruta al certificado público verificado. Se detiene para que el administrador firme la CSR; se reanuda añadiendo `--signed-certificate` con el certificado público recibido. Los mismos argumentos existen en Linux y Windows. El [procedimiento de firma](MANUAL_SERVIDOR_LAN.md#firma-de-una-ca-común-reanudar-el-mismo-inicio) indica los comandos. La clave privada de la CA común no se copia al anfitrión.

## 8. Inicio del servidor

Tras completar las secciones anteriores, desde la raíz y en una terminal interactiva:

Linux:

```bash
python3 infrastructure/lan/lan.py start --auto --project docker
```

Windows, PowerShell:

```powershell
py -3 infrastructure/lan/lan.py start --auto --project docker
```

`py -3 infrastructure/lan/lan.py start --auto` también es correcto: selecciona el proyecto detectado o `docker` si es nuevo. Aquí se indica explícitamente para mantener la misma selección en administración y diagnóstico.

1. Autoriza la red solo si es la prevista. En la primera CA individual confirma `CREAR CA`; cuando corresponda, escribe `AUTORIZO` para las autorizaciones separadas.
2. Espera la descarga de la imagen JDK de herramientas y la construcción de las imágenes faltantes. No hace falta un `build` o `up` previo. Las siguientes ejecuciones reconstruyen imágenes cuando cambian sus fuentes registradas, o si se solicita `--rebuild`; la primera adopción del descubrimiento CA puede reconstruir Gateway.
3. El asistente inicia con ambos Compose y ambos archivos de entorno, conserva volúmenes y espera salud, registros Eureka y HTTPS verificado con la CA local. Si necesita recrear servicios y hay partidas activas o no puede descartarlas, solicita confirmación. Anuncia una ventana de mantenimiento antes de autorizar una interrupción.
4. Al terminar debe mostrar REST HTTPS, WSS, descubrimiento CA y huella completa. Si termina con error, usa la sección 12; no interpretes un arranque parcial como instalación lista.

## 9. Verificación de servicios

### 9.1 Estado y diagnóstico

Linux:

```bash
python3 infrastructure/lan/lan.py status --project docker
python3 infrastructure/lan/lan.py cert-check
python3 infrastructure/lan/lan.py diagnose --project docker
python3 infrastructure/lan/lan.py url
```

Windows:

```powershell
py -3 infrastructure/lan/lan.py status --project docker
py -3 infrastructure/lan/lan.py cert-check
py -3 infrastructure/lan/lan.py diagnose --project docker
py -3 infrastructure/lan/lan.py url
```

Comprueba:

1. PostgreSQL, MongoDB, Redis, Auth, Game, Lobby y Gateway ejecutándose y `healthy`. Eureka debe estar ejecutándose; no tiene healthcheck.
2. `cert-check`: `Certificado: OK`. Verifica el certificado para la IP configurada usando la imagen JDK de herramientas, sin modificar el material TLS.
3. `diagnose`: servicios/puertos, registro Eureka, certificado, firewall y HTTP local/HTTPS LAN en `OK`. Verifica los cuatro clientes de Eureka `UP` y la respuesta HTTPS y de descubrimiento con la CA local. En Linux, si falta autorización sudo para leer firewall, el resultado queda pendiente; puedes autorizar la consulta con `sudo -v` y repetir `diagnose`, sin ejecutar Python como root.
4. `url`: direcciones y huella configuradas. No consulta Docker ni acredita que esa dirección esté disponible. `status` tampoco sustituye el diagnóstico TLS/firewall.

Para ver directamente estado y puertos publicados, en ambos sistemas:

```sh
docker compose -p docker --env-file infrastructure/docker/.env --env-file infrastructure/docker/lan-secrets/lan.env -f infrastructure/docker/docker-compose.yml -f infrastructure/docker/docker-compose.lan.yml ps -a
```

### 9.2 HTTPS con CA verificada

`diagnose` ya realiza una consulta HTTPS con validación de CA e IP. Para una consulta manual opcional con curl, usa la IP/puerto que mostró `url`. Estos ejemplos piden los valores reales en la terminal y parten de la raíz.

Linux, con curl instalado:

```bash
read -r -p 'IPv4 del servidor mostrada por url: ' server_ip
read -r -p 'Puerto HTTPS mostrado por url: ' server_port
curl --fail --show-error --cacert infrastructure/docker/lan-secrets/ca.crt "https://${server_ip}:${server_port}/actuator/health"
```

Windows, con `curl.exe` disponible:

```powershell
$serverIp = Read-Host 'IPv4 del servidor mostrada por url'
$serverPort = Read-Host 'Puerto HTTPS mostrado por url'
curl.exe --fail --show-error --cacert infrastructure/docker/lan-secrets/ca.crt "https://${serverIp}:${serverPort}/actuator/health"
```

Se espera `status: UP`. Se usa la CA pública local del anfitrión; no se omite la validación TLS. Desde otro equipo, una CA debe verificarse primero mediante su huella por un canal independiente. No uses `-k` como validación o solución de conexión.

La salud HTTPS no prueba una sesión WSS. Completa la comprobación desde otro dispositivo con el cliente de la sección siguiente; el certificado válido en el anfitrión no demuestra que el firewall o el punto de acceso permitan tráfico remoto.

## 10. Conexión del cliente PixelChiss

<a id="operaciones-rest"></a>

1. Obtén un cliente Godot nativo compatible con el servidor actual (`v5-100`), HTTPS/WSS, OAuth Desktop con PKCE y verificación de la CA LAN. El proyecto Godot no está incluido aquí; confirma estas capacidades en el ejecutable utilizado.
2. En el anfitrión ejecuta `lan.py url` con el intérprete de tu sistema. Comunica **IP, puerto y los 64 caracteres de la huella SHA-256 DER de la CA** en persona o por un canal autenticado independiente del servidor de descubrimiento.
3. En el cliente selecciona LAN e introduce esa IP/puerto. La base REST es `https://<IP_DEL_SERVIDOR>:<PUERTO>` y el WebSocket es `wss://<IP_DEL_SERVIDOR>:<PUERTO>/api/game/ws`.
4. El cliente debe obtener la CA candidata mediante `GET /.well-known/pixelchiss/ca`, comprobarla y comparar su huella completa con la comunicada por el anfitrión. El recurso entrega `protocolVersion`, `caId`, `fingerprintSha256` y `caPem`. Su coherencia interna o la descarga HTTPS sin confianza previa **no autentican al anfitrión**. La huella se calcula sobre el certificado DER, no sobre el texto PEM.
5. Solo tras confirmar la coincidencia, el cliente debe guardar esa CA y abrir conexiones nuevas con validación de cadena, vigencia e IP para REST y WSS. No necesita instalar la CA globalmente en Windows/Linux ni recibir archivos privados del servidor. Si el cliente aún no implementa este flujo, requiere su actualización; no omitas TLS para continuar.
6. Comprueba desde otro equipo que el cliente recibe `UP` en `/actuator/health`, inicia sesión Google en el navegador del **jugador** y obtiene su sesión del backend elegido. El callback Desktop vuelve al loopback del jugador y el canje pasa por HTTPS. No uses el flujo Web local a través del conector LAN.
7. Comprueba una conexión WSS a `/api/game/ws`: el primer frame debe ser `AUTH` con el access token real y se espera `AUTHENTICATED`. No pongas tokens en la URL. Después usa el flujo normal del cliente para entrar a una sala/partida de prueba y confirmar conexión entre dispositivos; este paso crea datos y debe hacerse fuera de partidas ajenas.

Si el cliente se ejecuta en el propio anfitrión, también puede usar `http://127.0.0.1:8080` y `ws://127.0.0.1:8080/api/game/ws`, ajustando el puerto si cambió `GATEWAY_PORT`. Este acceso es HTTP loopback; los dispositivos remotos usan HTTPS/WSS. Otro anfitrión independiente tiene otra persistencia y requiere su propia confianza y sesión.

## 11. Detención y reinicio

Avisa antes a los jugadores. Detener interrumpe HTTP/WSS; se conservan contenedores, volúmenes y datos persistidos.

Linux:

```bash
python3 infrastructure/lan/lan.py stop --project docker
```

Windows:

```powershell
py -3 infrastructure/lan/lan.py stop --project docker
```

Confirma la detención cuando el asistente la solicite. Para iniciar otra vez, ejecuta el comando `start --auto --project docker` de la sección 8 con el intérprete de tu sistema. Así se comprueban de nuevo IP, certificado y firewall después de un reinicio o cambio de red.

No uses `down -v`, limpiezas de volúmenes, `FLUSHDB` o `FLUSHALL` para reiniciar. Conserva `.env`, JWT y `lan-secrets/` junto con los volúmenes del mismo proyecto. Los respaldos privados del asistente cubren su configuración LAN; no sustituyen un respaldo de las bases.

## 12. Solución de problemas

| Síntoma | Comprobación y acción |
|---|---|
| Docker no responde | Ejecuta `docker info`. Linux con systemd: inicia el daemon con `sudo systemctl start docker`; Windows: inicia Docker Desktop en modo contenedores Linux. Revisa permisos y contexto local. |
| Versión o contexto no admitido | Comprueba Engine >=28, Compose >=2.24.4, contexto local y Docker sin rootless. No cambies DNS internos para evitar la comprobación. |
| Puerto ocupado | Revisa `docker ps`. Linux: `ss -ltnp`; Windows: `Get-NetTCPConnection -State Listen`. Identifica el propietario sin detener procesos ajenos. Para publicaciones loopback ajusta solo la variable `*_PORT` correspondiente de la sección 6; para TLS el asistente acepta `--port` entre 1024 y 65535. Comunica el nuevo puerto al cliente. |
| Red no detectada o varias redes | Ejecuta `network`; conecta una interfaz física a la LAN. Selecciona una candidata real con `--interface` o `--host`. No uses la IP Docker/VPN. |
| Firewall pendiente | Comprueba permisos sudo/UAC y ejecuta `diagnose`. El siguiente `start --auto` solicita autorización si necesita corregir reglas propias. No vacíes cadenas ni desactives el firewall. Reglas manuales antiguas o de terceros requieren revisión del administrador. |
| Cambió la IP/Wi-Fi | Repite `start --auto --project docker`. Revisa la red, autoriza los cambios necesarios y comparte la dirección nueva. Se conserva la CA; una CA común sin clave local requiere firma de CSR. Las conexiones anteriores pueden cortarse. |
| Contenedor `starting`, `unhealthy` o detenido | Consulta `status` y los logs de abajo. Verifica claves, credenciales y salud de PostgreSQL/MongoDB/Redis. Espera registro Eureka; `UP` del Gateway por sí solo no acredita los demás servicios. Si el arranque necesita más tiempo, `start` admite `--timeout` de 1 a 1800 segundos. No borres volúmenes. |
| OAuth 503 o falta Desktop | Configura `GOOGLE_DESKTOP_CLIENT_ID` real de tipo Desktop, no el Web. Repite el inicio para aplicar la configuración. |
| OAuth `redirect_uri_mismatch`, acceso denegado o 401 | Revisa tipo de cliente, ID/secreto, consentimiento, cuentas de prueba y URI Web exacta. En Desktop el callback es del jugador; un código/state/verificador vencido o consumido exige iniciar login de nuevo. No envíes secretos dentro de Godot. |
| HTTPS falla | Revisa reloj del equipo, CA, IP/SAN y vigencia con `cert-check`. `start --auto` puede renovar el certificado de servidor conservando CA y clave. Una CA inválida o vencida exige intervención; no se sustituye silenciosamente. |
| Conecta el anfitrión, pero no otro equipo | Revisa IP/puerto reales, subred autorizada, aislamiento de clientes Wi-Fi/AP, VLAN y reglas del anfitrión. Comprueba CA y cliente compatibles desde ese otro equipo; no abras los puertos de bases. |
| Operación LAN interrumpida | Si existe una transacción pendiente, `recover` restaura sus archivos y después debes repetir `start --auto`. Conserva respaldos. Si quedó `auto.lock`, comprueba primero que su PID ya no exista antes de retirar solo ese bloqueo. |

Para consultar logs sin reiniciar, el comando es igual en Bash y PowerShell, desde la raíz:

```sh
docker compose -p docker --env-file infrastructure/docker/.env --env-file infrastructure/docker/lan-secrets/lan.env -f infrastructure/docker/docker-compose.yml -f infrastructure/docker/docker-compose.lan.yml logs --tail 100 auth-service game-service lobby-service api-gateway
```

Puedes añadir `postgres mongodb redis eureka-server` a la lista para revisar dependencias. No publiques logs sin revisar su contenido. Evita mostrar `docker compose config` sin `--quiet`: la configuración resuelta contiene secretos.

La recuperación de archivos requiere confirmación y no inicia servicios:

Linux:

```bash
python3 infrastructure/lan/lan.py recover --project docker
```

Windows:

```powershell
py -3 infrastructure/lan/lan.py recover --project docker
```

## 13. Actualización segura del servidor

El repositorio dispone del procedimiento `git pull --ff-only` seguido de `start --auto`, que detecta cambios en las fuentes de las imágenes. Para una instalación LAN ya preparada:

1. Programa mantenimiento y conserva respaldos recuperables de PostgreSQL, MongoDB y Redis, además de `.env`, claves JWT y material LAN, fuera de ubicaciones públicas. No continúes con partidas activas sin coordinar su interrupción.
2. Desde la raíz, en Bash o PowerShell:

   ```sh
   git status --short --branch
   git fetch origin
   git log --oneline --left-right HEAD...origin/main
   ```

3. Si hay trabajo local o divergencias, consérvalo y resuélvelo antes de actualizar. Con la rama `main` limpia y sin divergencias:

   ```sh
   git pull --ff-only origin main
   ```

4. Conserva el mismo proyecto, `.env`, claves y volúmenes. Revisa las instrucciones de compatibilidad de la versión de destino. El asistente no migra automáticamente partidas de geometrías anteriores; no cambies sus marcadores para forzar compatibilidad con `v5-100`.
5. Ejecuta el inicio de la sección 8. Construirá las imágenes necesarias según sus fuentes registradas y aplicará la configuración con los controles de mantenimiento. Si estás adoptando imágenes anteriores sin procedencia registrada, añade `--rebuild` para reconstruir expresamente los cinco servicios.
6. Repite las verificaciones de las secciones 9 y 10 y comunica cualquier nueva dirección. No uses force, borrado de datos ni cambios de nombre de proyecto para resolver una actualización.
