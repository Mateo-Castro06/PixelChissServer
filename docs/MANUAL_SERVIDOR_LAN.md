# Manual del anfitrión LAN — PixelChiss

Un único backend Docker atiende al anfitrión y a los demás jugadores. Los invitados solo ejecutan Godot; no instalan microservicios ni bases de datos. El dueño del backend no adquiere permisos especiales sobre salas. No hay despliegue en Internet, hosting, AWS ni port forwarding del router.

## Inicio automático y cambios de Wi-Fi

Desde la raíz, con Docker funcionando y la configuración inicial de secretos/Google completada:

```bash
python3 infrastructure/lan/lan.py start --auto
```

Windows PowerShell:

```powershell
py -3 infrastructure/lan/lan.py start --auto
```

No se necesita recordar la IP ni editar archivos al cambiar de Wi-Fi. El asistente detecta interfaces físicas activas, IPv4 privada, prefijo/subred y gateway. Linux consulta `ip -j` y dispositivos físicos en sysfs; Windows consulta adaptadores físicos, direcciones, rutas y perfiles mediante PowerShell. Descarta loopback, Docker, bridges, VPN/túneles y direcciones no aptas. Con varias redes físicas no elige por prioridad arbitraria: muestra interfaz/IP/subred y pide una selección, o permite `--interface NOMBRE` / `--host IP_DETECTADA`. `--subnet` solo selecciona una subred realmente detectada; no permite ampliar el acceso.

### Preparación inicial, una sola vez

1. Instalar Python **3.9 o posterior**, Git, Docker Engine >=28 y Compose >=2.24.4. En Windows usar Docker Desktop con contenedores Linux. No ejecutar todo Python como administrador/root: el asistente solicita privilegios únicamente para las operaciones de firewall.
2. Preparar `.env`, claves JWT y credenciales Google reales siguiendo las secciones posteriores. La automatización **no edita `.env` ni inventa OAuth**. Falta de Desktop client ID detiene el inicio. El secreto Desktop existente se conserva.
3. Flujo recomendado sin administrador central: cada anfitrión conserva su propia CA. Ejecutar start --auto y confirmar expresamente `CREAR CA` solo en la primera preparación. La imagen JDK se descarga si falta. Si ya hay certificados, no se crea otra CA.
4. Para la CA común del equipo en un anfitrión nuevo, recibir solo su certificado público verificado y ejecutar `start --auto --ca-certificate RUTA_CA_PUBLICA.crt`. Se crea la clave de servidor y una CSR privada para traslado público al administrador; nunca se solicita su ca.p12. Si no hay firma todavía, el comando se detiene antes de publicar LAN.
5. Verificar que el directorio y las claves privadas sean del usuario anfitrión. En Linux: directorio 700, lan.env/PKCS12 600; en Windows sin permisos de acceso para Everyone, Users o Authenticated Users. El asistente comprueba protección y se detiene si es insuficiente; no cambia ACL/permisos de archivos existentes automáticamente.

### Qué hace cada inicio

- Muestra la red elegida. Una red nueva, pública, de dominio o cuya confianza no pueda determinar requiere `AUTORIZO`. No convierte una red pública a privada. Linux normalmente informa perfil Unknown y pide confirmación; Windows conserva el perfil detectado. Tener perfil Private no garantiza seguridad.
- Identifica el proyecto Compose por los archivos de este checkout. Conserva su nombre; `--project NOMBRE` permite resolver ambigüedad. Rechaza colisiones con contenedores de otros proyectos, contextos remotos y Docker rootless sin una protección de bridge verificable.
- Reutiliza el certificado si su SAN, vigencia, firma, cadena, uso de servidor y correspondencia con la clave privada son válidos. La comprobación usa Java dentro de la imagen JDK, sin red ni secretos en argumentos. La CA pública existente es el ancla; esta CA privada offline no tiene OCSP/CRL configurado.
- Si cambia la IP o vence el certificado de servidor, genera una CSR con **la misma clave de servidor** y prepara una copia validada dentro de lan-secrets. Con CA individual firma allí, sin copiar su clave CA a otra carpeta. Con CA común se detiene para obtener la firma. Una CA vencida, una cadena ajena o una clave incompatible no provocan regeneración silenciosa.
- Actualiza atómicamente solo LAN_BIND_ADDRESS y, si se solicitó, LAN_HTTPS_PORT. Conserva LAN_TLS_DIRECTORY, contraseña TLS, Google, JWT y bases. Por defecto conserva el puerto anterior; `--port PUERTO` lo cambia expresamente.
- Comprueba las partidas leyendo únicamente estados persistidos de Game en Redis. Ante partidas ACTIVE, estado desconocido o una consulta no verificable pide confirmación antes de aplicar cambios que puedan interrumpir conexiones. Vuelve a consultar antes de recrear servicios si inicialmente no había partidas. No congela la creación de nuevas partidas: anuncia una ventana de mantenimiento a los jugadores antes de autorizar cambios.
- Verifica y, si hace falta, solicita autorización para aplicar firewall limitado a esa IP/puerto/subred. Solo entonces permite privilegios de sistema. Comprueba la protección instalada antes de publicar y conserva las reglas anteriores hasta que HTTPS esté saludable.
- Usa los dos Compose y los dos env-files existentes. Construye imágenes faltantes o cuyos archivos de construcción hayan cambiado desde el último inicio automático exitoso. En la primera adopción reutiliza imágenes existentes; si proceden de una revisión desconocida, usar `--rebuild` para reconstruir expresamente los cinco servicios. No reconstruye por cambios solo documentales. Una instalación nueva construye las imágenes que falten.
- Reutiliza servicios sin cambios y no los recrea. Renueva Gateway si cambió su certificado aunque su IP sea la misma. Si se recrearon Redis/Mongo, reinicia Game para renovar conexiones; se conserva su estado. Si el monitoreo ya existía, también aplica el override de Prometheus, sin iniciar servicios opcionales que antes no existían.
- Espera healthchecks, valida los puertos realmente publicados y comprueba HTTPS estricto, HTTP local y el descubrimiento contra la CA pública local. Deja el backend funcionando y muestra IP/puerto, REST/WSS, URL de descubrimiento, estado TLS/firewall, huella DER SHA256 completa y reversión de firewall.
- Al adoptar por primera vez el protocolo de descubrimiento, reconstruye solo la imagen Gateway si todavía no estaba prevista su construcción, incluso si existía una imagen sin baseline registrada. Se mantienen la comprobación de partidas y el consentimiento de mantenimiento. Los siguientes inicios reutilizan esa imagen mientras no cambien sus fuentes.

El asistente no es un monitor residente: **cada ejecución** detecta la red actual. No puede mantener intactos los WebSocket durante un cambio de IP. Tras la migración, los jugadores usan la nueva dirección y reconectan/sincronizan con el estado persistido. No hay cambios en los contratos ni reglas del juego.

### Datos para compartir con jugadores: sin archivos manuales

Ejecutar `python3 infrastructure/lan/lan.py url` (Windows: `py -3 ... url`) para volver a consultar IP/puerto, URLs y huella sin reiniciar ni acceder al daemon Docker. Comunicar IP, puerto y **los 64 caracteres de la huella** en persona o mediante un canal independiente y confiable. No enviarlos únicamente desde el mismo recurso de descubrimiento.

El recurso es `GET https://IP:8443/.well-known/pixelchiss/ca` (o el puerto configurado), sin JWT ni Google previo. Devuelve únicamente CA PEM pública, SHA-256 DER, caId y versión. La descarga inicial no autentica al anfitrión: Godot debe mantener la candidata no confiable hasta la comparación independiente y abrir después conexiones nuevas HTTPS/WSS estrictas. No copiar ca.crt ni instalarlo en Windows/Linux. El mismo ejecutable puede guardar CA de varios anfitriones. El [contrato completo](GODOT_INTEGRACION_LAN.md#descubrimiento-automático-de-ca-y-primera-conexión-a-un-anfitrión-lan) y los [cambios para Godot](GODOT_LAN_CERTIFICADOS_CAMBIOS.md) describen lo pendiente en el cliente.

No hay puertos adicionales: solo el Gateway TLS en la IP privada y puerto autorizado. HTTP 8080 sigue limitado a loopback; no se publican bases, Eureka ni métricas en LAN. Las reglas, autorizaciones de firewall, stop y recover permanecen bajo el ciclo existente. El montaje adicional solo contiene ca.crt público. Si la CA no está preparada, el recurso devuelve 503 sin detalles privados; si no arranca TLS puede no haber respuesta HTTP. `diagnose` comprueba el recurso usando la CA local y validación TLS normal.

### Firma de una CA común: reanudar el mismo inicio

Cuando haga falta renovar y no exista ca.p12 local, el comando muestra `lan-secrets/.renewal/server.csr`. Enviar únicamente esa CSR y la IP autorizada al administrador. En su equipo:

```sh
python3 infrastructure/lan/lan.py sign --host IP_AUTORIZADA --csr RUTA_CSR --ca-directory RUTA_CA_PRIVADA --output certificado.crt
```

Windows sustituye python3 por `py -3`. El administrador devuelve solo el certificado público firmado. En el anfitrión:

```sh
python3 infrastructure/lan/lan.py start --auto --signed-certificate RUTA_CERTIFICADO.crt
```

No se necesita editar lan.env ni ejecutar install manualmente para este flujo. La CA queda fijada al certificado público anterior y la firma debe corresponder a su clave de servidor y nueva IP. El asistente instala la copia validada y continúa. Si vuelve a cambiar de red antes de recibir la firma, archiva la solicitud anterior en `.renewal-history` privada y prepara otra; no sobrescribe la CSR anterior. Conservar la confianza pública en Godot: no hay que importar otra CA por cambiar de Wi-Fi.

### Firewall administrado y autorizaciones

Las reglas nuevas llevan una identidad de esta instalación/proyecto. El asistente nunca elimina reglas sin esa identidad; una regla manual antigua equivalente en DOCKER-USER se reutiliza sin duplicarla y permanece bajo administración manual. Al cambiar de red, esas reglas antiguas sin etiqueta se conservan. Si coinciden con la misma IP/puerto y restringen una subred anterior, pueden bloquear invitados de la nueva subred: el administrador debe revisar esa regla manual, sin vaciar cadenas.

Linux detecta DOCKER-USER realmente enlazada al inicio de FORWARD, o las tablas del backend nativo nftables Docker. En iptables usa coincidencia de destino/puerto original por conntrack y DROP fuera de la subred. En nftables utiliza una tabla propia por instalación con cadena forward de prioridad -1, sin editar ni vaciar tablas Docker. Una plataforma no verificable detiene la publicación con indicaciones de diagnóstico; UFW por sí solo no se considera suficiente.

Windows crea reglas con nombre/grupo propios: autorización para la subred y perfil actual, bloqueo del complemento IPv4 y bloqueo de los otros perfiles. Una red Public solo se utiliza después de confirmar expresamente el aviso; no se cambia su perfil ni se desactiva Defender Firewall. Si hace falta elevación, se muestra UAC para un bloque limitado de comandos de firewall, sin ejecutar todo el asistente como administrador. La pertenencia al grupo Administradores no sustituye `AUTORIZO`.

En Linux sudo puede pedir contraseña **en tu terminal**, nunca como parámetro de la herramienta ni dentro de un archivo. Si falta autorización, privilegios o verificación real de reglas, no se publicará una nueva configuración. No hay `--yes` para omitir consentimientos. La protección debe volver a verificarse tras reinicios: no se modifica el mecanismo global de persistencia del firewall del sistema.

Después del éxito, `firewall-undo.txt` contiene los comandos para retirar exclusivamente la protección creada por esa instalación. No ejecutarlos mientras se recibe tráfico LAN. Para una regla manual reutilizada indica que no le pertenece. La retirada de reglas administradas antiguas se hace solo después de comprobar la protección nueva y HTTPS; si esa limpieza falla, la configuración nueva queda protegida y se informa del error, sin revertir a un estado desprotegido.

### Consultas, diagnóstico, parada y recuperación

```sh
python3 infrastructure/lan/lan.py network
python3 infrastructure/lan/lan.py status
python3 infrastructure/lan/lan.py cert-check
python3 infrastructure/lan/lan.py firewall-check
python3 infrastructure/lan/lan.py url
python3 infrastructure/lan/lan.py diagnose
python3 infrastructure/lan/lan.py stop
python3 infrastructure/lan/lan.py recover
```

En PowerShell usar `py -3` en cada línea. Los comandos network/status/cert-check/firewall-check/url/diagnose no escriben configuración ni reglas ni recrean servicios. La comprobación del firewall necesita permisos de lectura privilegiada en Linux; si no están disponibles, devuelve pendiente sin solicitar ni aplicar cambios. `cert-check --host IP` permite verificar expresamente otro SAN. `url` muestra direcciones REST/WSS/descubrimiento y huella local completa **sin consultar Docker**; no promete disponibilidad. `status` enumera contenedores y también muestra descubrimiento/huella; `diagnose` también comprueba TLS, red, firewall y Gateway y termina con error si falta verificación. Stop pide confirmación y detiene únicamente el proyecto seleccionado, conservando todos sus datos.

Antes de modificar archivos, el asistente guarda copias privadas de lan.env, server.p12, server.crt y auto-state.json en `.backups`. Un journal permite recuperar una operación interrumpida. Las sustituciones son atómicas, y un error intenta restaurar los archivos y la configuración previa de contenedores cuando era posible; si la IP anterior ya no existe, informa de que no pudo reactivarla. Mantiene protecciones de firewall en los fallos. Nunca elimina volúmenes ni estados de dominio para recuperarse.

`recover` restaura solo los archivos de una transacción interrumpida; después ejecutar start --auto para validar la red y reconciliar servicios. Si quedó auto.lock tras un cierre abrupto, comprobar primero que el PID ya no existe y retirar únicamente ese bloqueo de la instalación. No retirar el bloqueo de un proceso vivo. Los respaldos siguen privados y no se borran automáticamente; no compartirlos porque contienen contraseñas y claves de servidor.

Si falta Google, Docker no está disponible, no hay red apta, falla TLS o no puede protegerse el firewall, el comando termina con error y no declara «listo». Si se pierde una firma, reanudar con el certificado correcto; nunca sustituir la CA común por una nueva para evitar la aprobación.

Las secciones siguientes conservan el procedimiento **manual avanzado** y la preparación inicial. Los subcomandos históricos init/request/sign/install/firewall/check mantienen su comportamiento; `firewall` imprime reglas, mientras que **start --auto** puede aplicarlas tras autorización.

## 1. Requisitos

- Docker Engine **28 o posterior** en Linux, o Docker Desktop actualizado con contenedores Linux en Windows. Docker documenta una limitación de aislamiento de publicaciones loopback en versiones anteriores a 28: no usarlas como base de seguridad LAN.
- Docker Compose **2.24.4 o posterior** (`!override`), Git y Python 3.9 o posterior. La herramienta usa Python estándar y keytool dentro de un contenedor JDK; no exige OpenSSL ni Bash en Windows.
- Permisos para Docker, espacio para imágenes/volúmenes y administrador para las reglas de firewall.
- Conexión a Internet para descargar dependencias/imágenes y para **autenticación Google**, aunque la partida y sus servicios estén en LAN. No es login offline.
- OAuth Web/keys/secreto Lobby ya existentes para la instalación local, y un cliente OAuth **Desktop app** para login LAN seguro. El equipo Godot debe implementar [su guía](GODOT_INTEGRACION_LAN.md).

En Linux Mint usar Docker Engine compatible con su edición Ubuntu base siguiendo la [instalación oficial para Linux](https://docs.docker.com/engine/install/) y la documentación de la distribución; no codificar un nombre de versión ajeno. En Windows [instalar Docker Desktop](https://docs.docker.com/desktop/setup/install/windows-install/) si todavía no está disponible y habilitarlo, backend WSL2 si corresponde y contenedores Linux; seleccionar el contexto correcto. No editar direcciones internas para sustituir DNS Docker por la IP del anfitrión.

```sh
docker version
docker compose version
docker compose ls
```

No cambiar el nombre del proyecto existente. Los ejemplos omiten `-p` para mantener la resolución normal del archivo situado en infrastructure/docker (predeterminado `docker`). Si tu instalación usaba otro nombre, añadir el mismo `-p NOMBRE` a todos los comandos. No arrancar un segundo proyecto accidentalmente.

## 2. Preparar repositorio y datos

Desde la raíz del repositorio, ejecutar `git status`, conservar cambios propios y hacer `git pull --ff-only`. No incluir demo-rum ni credenciales. Si es una instalación nueva, preparar `.env` y claves PEM conforme al manual de instalación; **no copiar .env.example encima de un .env existente**. Conservar los volúmenes y claves JWT para que usuarios, sesiones y partidas continúen en ese anfitrión. Para un anfitrión nuevo generar su propio par JWT y compartir solo su pública entre sus microservicios; no copiar la privada de otro anfitrión con una base independiente.

La comunicación interna sigue usando eureka-server, postgres, mongodb, redis y nombres de servicio Docker. PostgreSQL/Mongo/Redis/Eureka/Auth/Lobby conservan acceso de desarrollo solo desde loopback del anfitrión.

### Instalación nueva en Windows, sin Bash

Si ya tienes `.env`, claves o volúmenes, **omite este bloque** y sigue la [guía de actualización](GUIA_ACTUALIZACION.md). Para una instalación completamente nueva, usar **PowerShell 7.2 o posterior** en la raíz del clon; las API criptográficas siguientes requieren esa versión, no Windows PowerShell 5.1. El resto de comandos Docker y `py -3` del manual también funciona en Windows PowerShell. No se requiere PowerShell 7 para actualizar un anfitrión ya preparado.

```powershell
$envPath = 'infrastructure/docker/.env'
$privatePath = 'auth-service/keys/private_key.pem'
$publicPath = 'auth-service/keys/public_key.pem'
$gatewayPublicPath = 'api-gateway/keys/public_key.pem'
foreach ($path in @($envPath, $privatePath, $publicPath, $gatewayPublicPath)) {
    if (Test-Path $path) { throw "Preparación cancelada: ya existe $path; conservar la instalación." }
}
$template = [IO.File]::ReadAllText('infrastructure/docker/.env.example')
$dbPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$lobbyToken = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$template = $template.Replace('POSTGRES_PASSWORD=change_me', "POSTGRES_PASSWORD=$dbPassword")
$template = $template.Replace('GAME_LOBBY_TOKEN=', "GAME_LOBBY_TOKEN=$lobbyToken")
[IO.File]::WriteAllText($envPath, $template)
New-Item -ItemType Directory -Force auth-service/keys, api-gateway/keys | Out-Null
$rsa = [Security.Cryptography.RSA]::Create(2048)
try {
    [IO.File]::WriteAllText($privatePath, $rsa.ExportPkcs8PrivateKeyPem())
    [IO.File]::WriteAllText($publicPath, $rsa.ExportSubjectPublicKeyInfoPem())
    [IO.File]::WriteAllText($gatewayPublicPath, $rsa.ExportSubjectPublicKeyInfoPem())
} finally { $rsa.Dispose() }
Remove-Variable dbPassword, lobbyToken, template
notepad infrastructure/docker/.env
```

Completar los valores Google reales en el editor, conservar `DB_HOST=postgres`, `DB_PORT=5432`, `DB_NAME=${POSTGRES_DB}`, `DB_USERNAME=${POSTGRES_USER}` y las rutas `/app/keys/...` del ejemplo. No imprimir el contenido del archivo. Restringir `.env` y `auth-service/keys` al usuario anfitrión mediante las propiedades de seguridad de Windows; no compartir estas carpetas. La clave JWT privada solo pertenece a Auth, y su pública debe coincidir en los tres montajes consumidores. Estas instrucciones Windows se entregan para ejecución por el equipo; no se verificaron en un anfitrión Windows real.

En Linux, para una instalación nueva usar los bloques de preparación de `.env` y claves RSA de [MANUAL_INSTALACION, sección 5.6.1](MANUAL_INSTALACION.md#despliegue-completo); requieren Python 3 y OpenSSL. Para la primera construcción de **cualquier instalación nueva**, construir los cinco servicios, no solo Auth/Gateway:

```sh
docker compose -f infrastructure/docker/docker-compose.yml build eureka-server auth-service game-service lobby-service api-gateway
```

Las siguientes secciones añaden TLS al mismo despliegue. El comando de construcción de la sección 7 reconstruye únicamente los dos servicios afectados cuando los demás ya tienen imagen.

## 3. Google Cloud: tarea manual del administrador del proyecto

En Google Auth Platform / clientes OAuth del proyecto real del equipo:

1. Conservar el cliente Web existente y sus redirect URIs; no reemplazar localhost por una IP privada.
2. Crear un cliente de tipo **Desktop app** para el cliente nativo de escritorio. No inventar IDs ni convertir el Web client en Desktop.
3. Configurar la pantalla de consentimiento y agregar las cuentas del equipo a usuarios de prueba si el proyecto está en Testing. Solicitar solo openid, email y profile. No desactivar controles de cuenta Google ni asumir que OAuth acredita MFA.
4. Añadir `GOOGLE_DESKTOP_CLIENT_ID` al `.env` de cada anfitrión. Puede ser el mismo Desktop client del equipo; cada backend mantiene sus datos. Si el intercambio de ese cliente requiere el secreto Desktop, añadirlo en `GOOGLE_DESKTOP_CLIENT_SECRET` **solo en el anfitrión**. Nunca distribuirlo dentro de Godot.
5. El callback Desktop es el loopback dinámico del **jugador**, `http://127.0.0.1:puerto/callback`, conforme al flujo Desktop de Google. No registrar una lista de las IP LAN de todos los anfitriones ni enviar el callback a Docker. El backend canjea el código recibido por HTTPS y verifica el ID token.

Configurar Desktop y verificar una sesión real son obligatorios para habilitar login LAN. El login Web local existente conserva OAUTH_REDIRECT_URI (por defecto `http://localhost:8081/login/oauth2/code/google`); cualquier cambio de esa URI requiere registrar exactamente la misma en el cliente Web. No hace falta cambiarla para usar el nuevo flujo Desktop.

Fuente: [Google OAuth para aplicaciones Desktop](https://developers.google.com/identity/protocols/oauth2/native-app), [validación OIDC](https://developers.google.com/identity/openid-connect/openid-connect). La herramienta no puede crear ni verificar estas credenciales privadas en tu consola.

## 4. Identificar la IP del anfitrión

Linux: `ip -brief address` o `hostname -I`. Windows PowerShell: `Get-NetIPConfiguration` o `ipconfig`. Elegir la IPv4 privada del adaptador conectado a la misma red de los jugadores, no loopback, VPN ni un bridge Docker. No depender del nombre del adaptador. Consultar su prefijo/subred real para el firewall; no suponer que toda red usa /24.

Reservar opcionalmente esa dirección por DHCP en el router para que no cambie. No abrir puertos WAN/UPnP. Si cambia la IP, el certificado debe cubrir la nueva dirección: usar `start --auto`, que prepara una renovación validada y conserva respaldos, CA y clave. Con CA de equipo sin clave local, obtener la firma de su CSR como se explica arriba. Los clientes conservan la confianza en la CA. Una rotación explícita a otra CA exige nueva descarga y verificación independiente; no se provoca por cambiar IP.

## 5. Generar certificados por anfitrión

Sustituir DIRECCION_PRIVADA por la IP real; no pegar literalmente el marcador. Linux:

```sh
docker pull maven:3.9-eclipse-temurin-21
python3 infrastructure/lan/lan.py init --host DIRECCION_PRIVADA
```

Windows PowerShell, desde la raíz:

```powershell
docker pull maven:3.9-eclipse-temurin-21
py -3 infrastructure/lan/lan.py init --host DIRECCION_PRIVADA
```

La herramienta crea `infrastructure/docker/lan-secrets/`: CA privada, servidor PKCS12, certificados públicos y `lan.env` con LAN_BIND_ADDRESS, LAN_HTTPS_PORT=8443 y contraseña aleatoria. No modifica `.env`. Los ficheros privados permanecen ignorados por Git. En Linux restringe permisos; en Windows conservarlos en el perfil privado del usuario con ACL adecuada (no carpetas compartidas ni sincronización pública).

El certificado incluye SAN de la IP elegida, localhost y 127.0.0.1. La clave CA queda en ca.p12 bajo custodia del administrador y no se monta en Gateway: Compose monta únicamente server.p12 y el certificado público ca.crt en archivos separados de solo lectura, sin ca.p12 ni lan.env. El Gateway entrega la CA pública automáticamente por HTTPS; comunicar a los jugadores la huella SHA256 completa del certificado DER que imprime la herramienta por un canal independiente autenticado (no el hash del texto PEM). No copiar archivos a los jugadores. Godot debe confiar explícitamente en esa CA para ese anfitrión, conservando verificación de IP. No es necesario instalarla globalmente si Godot usa TLSOptions.client(ca). No se usa un certificado público ni ACME/Let's Encrypt para IP privada. El certificado servidor dura un año; planificar renovación antes de caducar, sin borrar datos del backend.

### CA del equipo: cambiar de anfitrión cambiando solo la dirección

Para cumplir ese uso, el equipo debe provisionar previamente sus anfitriones con certificados de **la misma CA** y configurar esa CA pública una vez en Godot. El primer `init` lo realiza el administrador y custodia su ca.p12 fuera de carpetas compartidas. Los demás anfitriones no reciben esa clave: generan su propia clave y una CSR pública.

En el nuevo anfitrión (Windows: sustituir python3 por py -3):

```sh
python3 infrastructure/lan/lan.py request --host DIRECCION_PRIVADA
```

Enviar solo `server.csr` y la IP que el administrador deba autorizar. En el computador del administrador, con su CA privada local:

```sh
python3 infrastructure/lan/lan.py sign --host DIRECCION_PRIVADA --csr RUTA_CSR_PUBLICA --ca-directory RUTA_CA_PRIVADA --output RUTA_CERTIFICADO_PUBLICO.crt
```

El administrador verifica por un canal de confianza quién pide el certificado y la IP autorizada. Devuelve server.crt y la CA **pública**. El anfitrión termina:

```sh
python3 infrastructure/lan/lan.py install --ca-certificate RUTA_CA_PUBLICA.crt --server-certificate RUTA_CERTIFICADO_PUBLICO.crt
```

El PKCS12 de servidor conserva la clave generada en ese anfitrión; no se envía al administrador ni a los clientes. Los certificados públicos pueden transportarse por un canal verificado. Con los anfitriones ya preparados y la CA común confiada, Godot solo cambia IP (y puerto si se personalizó), sin cambiar ejecutable, microservicios, OAuth redirect o confianza TLS. Debe iniciar sesión en el nuevo backend porque su persistencia es independiente.

La CA individual por anfitrión es el flujo recomendado para servidores independientes: Godot descarga la candidata, el jugador compara su huella una vez y la aplicación conserva varias CA. No requiere importación manual ni otro ejecutable. La CA común y estos comandos auxiliares siguen siendo una opción para instalaciones ya administradas. En ambos casos, una CA desconocida exige comparación independiente y una rotación explícita exige nueva verificación.

## 6. Firewall: obligatorio antes de aceptar jugadores

0.0.0.0 escucha en todas las interfaces, no solo en la LAN. Escuchar en una IP privada tampoco limita por sí solo quién puede acceder mediante rutas. Este override publica TLS en **la IP privada seleccionada**, no en todas las interfaces. Aplicar además el alcance de subred permitido. No habilitar direct routing de Docker ni redes nat-unprotected. No desactivar el firewall de Docker.

El subcomando histórico **firewall** solo imprime reglas específicas y reversibles. Revisarlas y aplicarlas con privilegios; este subcomando no cambia el firewall. El nuevo **start --auto** usa el flujo de autorización y verificación descrito arriba.

Los ejemplos usan 8443. Si elegiste otro puerto con `init --port` o `request --port`, añadir ese mismo `--port PUERTO` a `firewall` y usarlo en todas las pruebas y en Godot. `firewall` no lee automáticamente el puerto de lan.env.

Linux (Docker Engine con backend iptables):

```sh
python3 infrastructure/lan/lan.py firewall --host DIRECCION_PRIVADA --subnet SUBRED_CIDR --platform linux
sudo iptables -S DOCKER-USER
```

Aplicar el `iptables -I DOCKER-USER` impreso. Filtra destino/puerto original mediante conntrack y descarta orígenes fuera de la subred, sin tocar otros proyectos. El comando `iptables -D` impreso revierte exactamente esa regla. Revisar y evitar duplicados antes de insertarla de nuevo. Persistirla con el mecanismo de la distribución o reaplicarla después de reinicios y comprobarla. **Un `ufw allow` por sí solo no protege puertos publicados de Docker**, porque el tráfico se desvía antes de esas reglas. Si el Engine usa backend nftables, no aplicar una regla a una cadena inexistente: configurar el equivalente en una cadena propia de forward con la prioridad adecuada según la [guía Docker nftables](https://docs.docker.com/engine/network/firewall-nftables/), o usar el backend iptables soportado. Los comandos Linux anteriores se dirigen al backend iptables; nftables y la aplicación física de reglas requieren comprobación del administrador.

Windows, PowerShell:

```powershell
py -3 infrastructure/lan/lan.py firewall --host DIRECCION_PRIVADA --subnet SUBRED_CIDR --platform windows
Get-NetConnectionProfile
```

En PowerShell **como administrador**, revisar y aplicar las reglas New-NetFirewallRule impresas. La autorización se limita al perfil Private y a esa subred; una regla Block complementaria impide el acceso desde otras IPv4 a la misma IP/puerto, incluso si hay autorizaciones amplias previas. Una tercera regla bloquea ese puerto en perfiles Public/Domain. No cambiar automáticamente una Wi-Fi pública a Private. Revisar reglas existentes de Docker Desktop y confirmar el bloqueo desde fuera de la subred permitida. El Remove-NetFirewallRule impreso elimina únicamente las tres reglas de PixelChiss. Si ya existen, modificarlas conscientemente o eliminarlas con ese comando antes de regenerar, evitando duplicados.

En ambos sistemas, no abrir 5432/27017/6379/8761/8081/8083 para invitados. No modificar reglas globales ni contenedores de otros proyectos. Referencias: [publicaciones](https://docs.docker.com/engine/network/port-publishing/), [Docker e iptables](https://docs.docker.com/engine/network/firewall-iptables/), [interacción con UFW](https://docs.docker.com/engine/network/packet-filtering-firewalls/).

## 7. Comprobar configuración e iniciar

Comprobar sin imprimir secretos:

```sh
python3 infrastructure/lan/lan.py check --lan
```

En Windows sustituir `python3` por `py -3`. El check exige Engine >=28, verifica que solo TLS pueda publicarse fuera de loopback y que existan los archivos de certificado. No sustituye la comprobación del firewall ni el login Google real.

Estos comandos son de una sola línea y funcionan en Bash y PowerShell:

```sh
docker compose --env-file infrastructure/docker/.env --env-file infrastructure/docker/lan-secrets/lan.env -f infrastructure/docker/docker-compose.yml -f infrastructure/docker/docker-compose.lan.yml build auth-service api-gateway
docker compose --env-file infrastructure/docker/.env --env-file infrastructure/docker/lan-secrets/lan.env -f infrastructure/docker/docker-compose.yml -f infrastructure/docker/docker-compose.lan.yml up -d
docker compose --env-file infrastructure/docker/.env --env-file infrastructure/docker/lan-secrets/lan.env -f infrastructure/docker/docker-compose.yml -f infrastructure/docker/docker-compose.lan.yml restart game-service
docker compose --env-file infrastructure/docker/.env --env-file infrastructure/docker/lan-secrets/lan.env -f infrastructure/docker/docker-compose.yml -f infrastructure/docker/docker-compose.lan.yml ps
```

Usar siempre la misma combinación al administrar el modo LAN. No mostrar `docker compose config` completo en un informe: contiene secretos. Revisar healthchecks. Tras recrear servicios puede tardar en propagarse el registro Eureka; esperar disponibilidad de las rutas antes del login. No reconstruir Game para cambiar conectividad: su motor es el de la baseline. El restart mostrado renueva sus conexiones persistentes cuando las bases se recrearon por el cambio de publicación; conserva su estado Redis. En arranques nuevos es inocuo.

| Acceso del anfitrión | Publicación predeterminada |
|---|---|
| Godot local, REST/WS | 127.0.0.1:8080, HTTP local al mismo Gateway |
| Invitados LAN, REST/WS | IP elegida:8443, HTTPS/WSS al mismo Gateway |
| Auth, Lobby, Eureka para desarrollo | Solo 127.0.0.1:8081/8083/8761 |
| PostgreSQL, MongoDB, Redis para desarrollo | Solo 127.0.0.1:5432/27017/6379 |
| Prometheus/Grafana opcionales | Solo 127.0.0.1:9090/3000 |
| Game | Sin publicación de puerto al anfitrión |

En LAN el mismo Gateway usa TLS en su puerto interno 8080 y un conector HTTP interno 8082 para publicación loopback y métricas internas. No se crean otro Gateway, otro Game ni otras bases. Los endpoints actuator distintos de health no se sirven por el conector TLS público; las métricas internas mantienen su clave. El override adapta el scrape de Prometheus al conector interno.

## 8. Probar y jugar

En el anfitrión: `curl http://127.0.0.1:8080/actuator/health` (en PowerShell usar curl.exe o Invoke-RestMethod). El cliente Godot debe descargar y verificar la CA según el capítulo siguiente. Para diagnóstico técnico opcional con curl y una CA pública ya verificada (no es un paso requerido al jugador):

```sh
curl --cacert ca.crt https://DIRECCION_PRIVADA:8443/actuator/health
```

Debe responder UP sin omitir validación TLS. `/api/game/board` sin JWT debe rechazar con 401. HTTP sobre el puerto TLS no debe aceptar sesiones. Ante certificado no válido, corregir CA/SAN/fecha; nunca usar `-k` como solución de juego.

En Godot remoto seleccionar LAN, introducir IP/puerto y verificar la huella de la CA descargada en la primera conexión. El equipo Godot debe incorporar este flujo; no hay proyecto cliente en este repositorio. Iniciar Google desde el navegador de **cada jugador**; el listener callback es local a ese jugador. Crear una sala, compartir su código, unirse, marcar ready, votar e iniciar. Consultar [los endpoints y pasos del cliente](GODOT_INTEGRACION_LAN.md). Para jugar solo, usar local y añadir bots; ambos mapas y todas las reglas se conservan.

Comprobar manualmente una partida entre dos computadores físicos, reconexión y cambio de anfitrión. Un probe en la propia IP del anfitrión o desde otro contenedor no acredita una partida física entre Windows/Linux.

## 9. Detener o volver al modo local

Para detener sin borrar datos, usar los mismos env-files/archivos anteriores y el subcomando `stop`. No usar down -v ni prune. Para volver al modo local sin TLS LAN:

```sh
docker compose -f infrastructure/docker/docker-compose.yml up -d
```

Mantener el mismo proyecto; Compose vuelve a publicar solo loopback. Retirar la regla de firewall de PixelChiss si ya no se necesita mediante el comando de reversión generado. No borrar certificados o volúmenes para resolver un fallo. El modo local conserva puertos predeterminados y no requiere introducir IP en Godot, aunque el cliente debe actualizar el retorno OAuth para no recibir tokens en URLs.

## 10. Problemas frecuentes

- **No conecta otro computador:** IP de adaptador incorrecto, puerto personalizado, aislamiento Wi-Fi/AP, VLAN o firewall. Revisar la publicación real y probar desde el segundo equipo. No abrir bases de datos.
- **TLS falla:** CA ajena, IP cambiada, reloj incorrecto o certificado vencido. Reemitir material correctamente y verificar su huella.
- **503 Desktop no configurado:** el anfitrión debe añadir el Desktop client ID real; no es el client ID Web.
- **redirect_uri_mismatch:** se utilizó un cliente Google de tipo incorrecto o el flujo Web conserva una URI no registrada. No reemplazar por una IP al azar.
- **400 al volver al login Web:** Godot antiguo no envió client_challenge. Actualizar el canje de handoff o usar Desktop.
- **401 de código/PKCE:** verifier/state equivocado, intento usado o vencido. Iniciar una autorización nueva; no desactivar PKCE.
- **Game unhealthy tras recrear bases:** comprobar Redis/Mongo saludables y reiniciar solo game-service para renovar conexiones antiguas, sin borrar datos.
- **500/502/503 al arrancar:** esperar propagación de Eureka; si persiste, revisar salud y logs sin credenciales. Mantener DNS interno Docker.
- **Cambió el anfitrión:** con CA común y anfitriones previamente preparados, cambiar solo dirección/puerto en Godot e iniciar sesión de nuevo. Con CAs independientes, descargar y verificar una vez la CA del nuevo anfitrión, o reconocer la ya guardada. No se trasladan automáticamente usuarios, volúmenes o partidas.
- **Se perdió la contraseña TLS:** conservar respaldos privados; no se puede leer el PKCS12 sin ella. Preparar certificados nuevos sin borrar bases de datos.
