# PixelChiss Server

PixelChiss Server es el backend del videojuego multijugador PixelChiss. Permite autenticar jugadores, gestionar salas y ejecutar partidas; el cliente PixelChiss desarrollado en Godot se instala por separado.

## Requisitos previos

**Obligatorios para el inicio LAN automatizado:**

- Git, Python **3.9 o posterior**, Docker Engine **28 o posterior** y Docker Compose **2.24.4 o posterior** (`docker compose`).
- Linux con `ip` (iproute2), `sudo` y firewall Docker mediante iptables o nftables. En Windows: Docker Desktop con **contenedores Linux**, `py -3` y Windows PowerShell.
- Acceso a Docker local sin rootless y permisos administrativos para autorizar el firewall.
- Una interfaz física con IPv4 privada en la LAN de los jugadores e Internet para descargar dependencias y autenticar con Google.
- Credenciales Google OAuth Web y Desktop, secretos de instalación y claves JWT preparados como se indica abajo.
- Para preparar claves por primera vez: OpenSSL en Linux; **PowerShell 7.2 o posterior** en Windows para el procedimiento del manual.

**Opcionales:** curl y monitoreo Prometheus/Grafana. Java, Maven y las librerías de Lobby se instalan dentro de las imágenes.

## Obtener el proyecto

Desde el directorio donde guardarás el proyecto, en Bash o PowerShell:

```sh
git clone https://github.com/Mateo-Castro06/PixelChissServer.git
cd PixelChissServer
```

Todos los comandos siguientes parten de la raíz `PixelChissServer/`.

## Configuración inicial

1. **Prepara `.env`.** Copia `infrastructure/docker/.env.example` a `infrastructure/docker/.env` solo si no existe. Sigue la [preparación Linux/Windows](docs/MANUAL_INSTALACION.md#4-preparación-de-archivos-de-configuración).
2. **Configura secretos.** Reemplaza `POSTGRES_PASSWORD=change_me` y genera `GAME_LOBBY_TOKEN` aleatorio de al menos 32 caracteres. Conserva las conexiones internas Docker y las rutas JWT del ejemplo; el manual incluye generación segura de valores.
3. **Configura Google OAuth.** Prepara consentimiento y usuarios de prueba. Necesitas un cliente **Web** (`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`) con callback predeterminado `http://localhost:8081/login/oauth2/code/google`, y otro **Desktop app** (`GOOGLE_DESKTOP_CLIENT_ID`, obligatorio para LAN; `GOOGLE_DESKTOP_CLIENT_SECRET` si Google lo requiere). Desktop usa el loopback del jugador; no cambies el callback Web por la IP LAN.
4. **Prepara JWT** con el [procedimiento del manual](docs/MANUAL_INSTALACION.md#52-claves-jwt): privada y pública en `auth-service/keys/`, y copia de `public_key.pem` en `api-gateway/keys/`. Conserva las claves existentes.
5. **Conecta una LAN autorizada.** El asistente detecta IP/subred y genera certificados y `infrastructure/docker/lan-secrets/lan.env`; solicita autorización para el firewall. No crea OAuth ni modifica `.env`. Mantén privados `.env`, claves y `lan-secrets/`.

## Instalación y ejecución

Completa primero la configuración anterior y abre una terminal interactiva con Docker funcionando.

Linux:

```bash
python3 infrastructure/lan/lan.py start --auto --project docker
```

Windows, PowerShell:

```powershell
py -3 infrastructure/lan/lan.py start --auto --project docker
```

En una instalación nueva, `--project docker` es opcional también en Windows: es el nombre predeterminado. Si la instalación existente usa otro proyecto, utiliza **ese mismo nombre** en todos los comandos; consulta `docker compose ls -a`.

El asistente descarga herramientas, construye las imágenes necesarias y espera salud y registro de servicios. Solicita `CREAR CA` en la primera preparación y `AUTORIZO` para red, firewall o interrupciones cuando corresponda. Sudo/UAC se usa solo para firewall; se conservan CA, secretos y volúmenes.

## Verificación

Linux:

```bash
python3 infrastructure/lan/lan.py status --project docker
python3 infrastructure/lan/lan.py url
python3 infrastructure/lan/lan.py diagnose --project docker
```

Windows, PowerShell:

```powershell
py -3 infrastructure/lan/lan.py status --project docker
py -3 infrastructure/lan/lan.py url
py -3 infrastructure/lan/lan.py diagnose --project docker
```

1. `status`: contenedores ejecutándose y `healthy` donde haya healthcheck. Eureka no tiene healthcheck. `diagnose` verifica publicaciones reales, salud, Eureka, certificado, firewall y HTTPS con la CA local. La lectura del firewall requiere privilegios en Linux.
2. `url`: IP/puerto configurados, URLs y huella **SHA-256 DER de la CA**, de 64 caracteres; no comprueba disponibilidad. Comparte IP, puerto (8443 por defecto) y huella por un canal independiente y confiable.
3. Godot debe descargar la CA en `/.well-known/pixelchiss/ca`, comparar la huella y verificar nuevas conexiones HTTPS/WSS. **HTTPS sin una CA confiable no acredita TLS verificado.** Usa `https://<IP_DEL_SERVIDOR>:<PUERTO>` y WSS en `/api/game/ws`, sustituyendo los marcadores.
4. Desde otro dispositivo, comprueba login y WSS autenticado con el [procedimiento del manual](docs/MANUAL_INSTALACION.md#10-conexión-del-cliente-pixelchiss). La salud HTTPS no verifica WSS ni el acceso remoto.

## Detener y reiniciar

Avisa a los jugadores: detener interrumpe las conexiones. Desde la raíz:

Linux:

```bash
python3 infrastructure/lan/lan.py stop --project docker
python3 infrastructure/lan/lan.py start --auto --project docker
```

Windows, PowerShell:

```powershell
py -3 infrastructure/lan/lan.py stop --project docker
py -3 infrastructure/lan/lan.py start --auto --project docker
```

Ejecuta la primera línea para detener y la segunda cuando quieras volver a iniciar. `stop` pide confirmación y conserva contenedores, volúmenes y datos. No uses `down -v` ni limpiezas de volúmenes para reiniciar.

## Solución de problemas frecuentes

| Problema | Acción |
|---|---|
| Docker no está iniciado | Inicia Docker Engine o Docker Desktop y comprueba `docker info`. |
| Puerto ocupado | Revisa la publicación con `docker ps` y el puerto indicado en el error; consulta las variables de puertos del manual antes de cambiarlo. |
| Firewall no verificado | Revisa `diagnose` y los permisos sudo/UAC. No desactives el firewall; UFW por sí solo no protege publicaciones Docker. |
| Cambió la IP local | Repite `start --auto --project docker` con el intérprete de tu sistema y comunica la dirección nueva. La CA se conserva; una CA común puede requerir firma administrativa. |
| Contenedores sin salud | Consulta `status`, espera el arranque y revisa los logs según el manual; no borres datos. |
| Error OAuth | Comprueba tipo de cliente, credenciales, consentimiento, usuarios de prueba y callback Web exacto. LAN exige el cliente Desktop. |
| Otro dispositivo no conecta | Confirma misma LAN/subred autorizada, IP/puerto, CA verificada y ausencia de aislamiento Wi-Fi/VLAN; prueba desde ese dispositivo. |

## Manual completo

Consulta el [manual de instalación y configuración](docs/MANUAL_INSTALACION.md) para preparar un equipo nuevo en Linux o Windows.
