# Integración Godot: conexión local y LAN

Esta guía es el contrato para el equipo del cliente **Godot nativo de escritorio** en Windows/Linux. No se modificó ni ejecutó un proyecto Godot. El descubrimiento de CA no modifica el motor ni los contratos de Auth, Lobby o Game. El computador que ejecuta Docker es el anfitrión del backend; `host_id` de una sala identifica al jugador que administra esa sala. Pueden ser personas distintas.

## A. Selección de conexión

Ofrecer «Jugar localmente» y «Conectarse a servidor LAN».

- Local: usar `http://127.0.0.1:8080`, sin pedir IP. El backend debe estar iniciado en ese computador. No es un motor offline.
- LAN: solicitar IPv4 privada del anfitrión, puerto HTTPS (predeterminado 8443). Descargar la CA candidata mediante el recurso documentado abajo y verificar su huella con el anfitrión por un canal independiente; no pedir un archivo al jugador. Construir `https://<dirección>:<puerto>`. No aceptar HTTP LAN como alternativa ante errores TLS.
- El anfitrión puede jugar por su conexión local mientras el mismo Gateway atiende LAN por HTTPS. También puede usar su dirección LAN con TLS.
- Con la CA común del equipo ya configurada y certificados firmados en los anfitriones, al cambiar de anfitrión basta cambiar la dirección (y el puerto si se personalizó) y autenticar de nuevo. No requiere otro ejecutable ni cambiar OAuth en Godot. Con CAs independientes, descargar y verificar una vez cada CA; conservar varias en el mismo ejecutable. Nunca enviar el refresh del anfitrión anterior al nuevo. Los usuarios, salas y partidas pertenecen a la persistencia de cada backend.

## B. Configuración

Crear, por ejemplo, `ConnectionConfig.gd` (Autoload) con modo, dirección y puerto. Usar `ConfigFile` en `user://connection.cfg` para recordar opcionalmente esos datos y la CA pública elegida. No guardar tokens ni verificadores PKCE allí. Normalizar y validar una dirección literal antes de construir la URL; no concatenar una URL libre con usuario, contraseña, fragmento o rutas arbitrarias.

| Modo | Base REST | WebSocket |
|---|---|---|
| Local | `http://127.0.0.1:8080` | `ws://127.0.0.1:8080/api/game/ws` |
| LAN | `https://<IPv4 privada>:8443` | `wss://<IPv4 privada>:8443/api/game/ws` |

El puerto es configurable; todos los endpoints se construyen a partir de una sola base. «Volver a local» restaura host loopback, HTTP y puerto 8080. Si el anfitrión personalizó GATEWAY_PORT, permitir ajustar el puerto local sin pedir una IP.

El anfitrión puede preparar su red actual con `lan.py start --auto`; esta automatización no cambia endpoints ni OAuth. Cuando cambia su Wi-Fi/IP, comunica las URLs nuevas que imprime la herramienta y los jugadores actualizan la dirección en Godot. No hay descubrimiento automático del anfitrión en el cliente. La CA se conserva: no aceptar otra CA automáticamente ni desactivar la comprobación de IP. Las conexiones WebSocket anteriores pueden cortarse; reconectar por la nueva dirección y sincronizar antes de habilitar acciones. Para la misma instancia/persistencia se puede recuperar una sesión todavía válida; al pasar a otro backend independiente no reutilizar sus credenciales. El [manual](MANUAL_SERVIDOR_LAN.md#inicio-automático-y-cambios-de-wi-fi) distingue CA individual, firma del administrador y aprobaciones de mantenimiento.

Para LAN cargar la CA pública en `X509Certificate` y usar `TLSOptions.client(ca)` tanto en `HTTPRequest.set_tls_options(...)` como en `WebSocketPeer.connect_to_url(url, tls_options)`. Conservar verificación de nombre/IP del certificado. **En REST de aplicación y WSS no usar `client_unsafe` ni ignorar errores TLS.** Solo la descarga aislada de CA descrita a continuación puede utilizar TLS provisional sin autenticar; su respuesta nunca autoriza confianza automática. Comparar su huella SHA256 del certificado DER con el anfitrión por un canal autenticado. Para mostrar la misma huella que lan.py, decodificar a DER el bloque PEM de la CA y calcular SHA256; el hash del texto PEM completo es distinto. No distribuir `server.p12`, `ca.p12` o `lan.env`. El navegador de Google se conecta a Google con su confianza normal; para este flujo de escritorio no necesita confiar en una CA privada del backend.

Referencias de API: [HTTPRequest](https://docs.godotengine.org/en/stable/classes/class_httprequest.html), [WebSocketPeer](https://docs.godotengine.org/en/stable/classes/class_websocketpeer.html), [TLSOptions](https://docs.godotengine.org/en/stable/classes/class_tlsoptions.html). La exportación web/móvil requiere un diseño OAuth distinto; no se acredita con este flujo loopback de escritorio.


## Descubrimiento automático de CA y primera conexión a un anfitrión LAN

### Contrato del backend (protocolo 1)

La IP la introduce el jugador: no hay búsqueda de equipos por broadcast/mDNS ni códigos de emparejamiento. Cada anfitrión puede conservar su propia CA; no necesita administrador central. La alternativa anterior de CA común sigue disponible, pero no es necesaria para este flujo.

| Campo | Valor exacto |
|---|---|
| URL | `https://<IPv4 privada>:<puerto>/.well-known/pixelchiss/ca` |
| Ejemplo de dirección | `https://192.168.1.10:8443/.well-known/pixelchiss/ca` |
| Transporte y puerto | HTTPS, 8443 predeterminado; el puerto configurado también sirve REST/WSS |
| Método | `GET`, sin cuerpo, sin parámetros |
| Headers requeridos por la aplicación | Ninguno; `Accept: application/json` es opcional; el cliente HTTP genera Host |
| Credenciales | Ninguna: no enviar Authorization, cookies, JWT, refresh, state, verifier o códigos OAuth |
| Respuesta 200 | `Content-Type: application/json;charset=UTF-8`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff` |
| Campos | `protocolVersion` entero 1; `caId`, `fingerprintSha256`, `caPem` strings |
| CA | Un único certificado X.509 raíz autofirmado en PEM; BEGIN/END CERTIFICATE, Base64 DER, saltos LF |
| Huella | SHA-256 de los bytes DER, exactamente 64 dígitos `[0-9a-f]`, minúsculas, sin `:` ni espacios |
| Identificador | `caId = "sha256:" + fingerprintSha256`; no es la IP ni un identificador de sesión/backend |

Petición completa de ejemplo (sustituir la IP):

```http
GET /.well-known/pixelchiss/ca HTTP/1.1
Host: 192.168.1.10:8443
Accept: application/json
```

Ejemplo completo de respuesta, generado con una **CA desechable de prueba**; el PEM y su huella son coherentes. No incorporarlo como confianza predeterminada ni como certificado de un anfitrión real:

```json
{
  "protocolVersion": 1,
  "caId": "sha256:8131f973fac95ea3090b9405762af5d453e66083567c915a5ce740ea98422aaf",
  "fingerprintSha256": "8131f973fac95ea3090b9405762af5d453e66083567c915a5ce740ea98422aaf",
  "caPem": "-----BEGIN CERTIFICATE-----\nMIIC7jCCAdagAwIBAgIJAOLtJWAtDUCdMA0GCSqGSIb3DQEBDAUAMBYxFDASBgNV\nBAMTC1Rlc3QgTEFOIENBMB4XDTI2MTEyMzIwMDkzMVoXDTM2MTEyMDIwMDkzMVow\nFjEUMBIGA1UEAxMLVGVzdCBMQU4gQ0EwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAw\nggEKAoIBAQDfhh1nS2QEv/bAUELoVvhCQ1h3PtpxP8rxw4ws6ZQkruDDX+9eVL1A\nHoAUOLA5PIxJMK4JbCE+fCeJXTyNTIXCOa2EBA11b8UKowDbI2xjfui2wfeBx2dZ\n4dAh1Ci6Ia77pDKwVJRZpcgWKFj4G0SOL8Mehp+xfPql3H721/t7k+LV8sz8jIxI\njjNXzx3VxEgx+gaKIg3onIu+/ZwGMen3WVHH/TVzChHJ/6C0Szt5ZqNraWBeXDtW\nWGAa4+qfDaK5GM5cIYvboTYSHN+kQZzhjxZKaHQ3OA5+mPL7O37XU3JXfteea8tv\nbVkMoCzOoOUZn3s8x5xogDyQ7dw52UfPAgMBAAGjPzA9MB0GA1UdDgQWBBTclASi\nG/BxSVRKdwClcdmBaemUDTALBgNVHQ8EBAMCAQYwDwYDVR0TBAgwBgEB/wIBADAN\nBgkqhkiG9w0BAQwFAAOCAQEAzEZx8L/SQAaspVE8Dgo+ZbtfVYaS2fIVGJQWV1NY\nkjECBkrsHFlb0LWXdve8BtEFdkVwda08dFwxa0gX6qQctIxBJzGljDN65KFg6MWp\njN4KecRWKmiH/5Qp2r8VLU1MQ+4N5VVNwEP8KIPJIETdXOERyY+TSb4Gv70W0Kye\netz7h6zK+TAIwW7sypImuFjg5zufY/HtyyspeEOTc9UkoYRl5CTkXuHrB65+JtTi\nGNT3yK9jbOJWA/a6I+Pwx4Dyas8VW8R1QZjaL4YuFQa9ptAR3+y+958V8RWdtqT/\nsnsx8DCUXtC4mbReFIScTQTFsEROeBINffntqeHaaqVeiQ==\n-----END CERTIFICATE-----\n"
}
```

En JSON los saltos de `caPem` están escapados como `\n`; después de parsearlo son saltos reales. El orden de claves no importa. No incluye URLs ni nombres de archivos elegibles por el cliente. El Gateway monta únicamente `server.p12` para TLS y `ca.crt` público, ambos de solo lectura; no monta el directorio con ca.p12, lan.env o respaldos. El endpoint vuelve a leer la CA en cada GET y limita su entrada a 16 KiB. No entrega listados, métricas ni material privado. Solo existe bajo perfil `lan`, y solo responde al conector HTTPS; HTTP loopback no ofrece este recurso.

### Descarga candidata: una conexión separada y sin credenciales

Sin ancla previa, HTTPS no puede autenticar esta descarga. En Godot nativo 4 se puede crear un **HTTPRequest nuevo, exclusivo de descubrimiento**, con `TLSOptions.client_unsafe()` únicamente en ese objeto, dirigido a la IP literal validada y la ruta fija anterior. Destruirlo al terminar o cancelar. No compartirlo con HttpClient/Auth, no mantener su conexión, no cambiar opciones TLS globales, no seguir redirecciones (`max_redirects = 0`), no usar proxies configurados por el juego ni enviar credenciales. La documentación oficial describe la conexión insegura como una conexión sin comprobación de identidad: [TLSOptions](https://docs.godotengine.org/en/stable/classes/class_tlsoptions.html). Aquí su resultado se trata como datos no confiables, nunca como autorización para jugar.

Establecer `timeout = 10.0`, `body_size_limit = 32768`, `accept_gzip = false` y no descargar a un archivo de confianza. Aceptar solo resultado de transporte exitoso, código HTTP 200, JSON UTF-8 y Content-Type JSON. Un atacante puede servir otra CA con su huella correcta: la coherencia interna **no acredita la identidad del anfitrión**. Rechazar redirecciones y respuestas sobredimensionadas. No descargar ninguna URL obtenida del documento. Referencia de las propiedades y señales reales: [HTTPRequest de Godot 4](https://docs.godotengine.org/en/stable/classes/class_httprequest.html).

### Validación y comparación independiente

Antes de presentar la confirmación:

1. Validar el esquema y `protocolVersion == 1`; exigir los cuatro campos y tipos anteriores. Rechazar caId/huella mal formados. No interpretar campos desconocidos como instrucciones, rutas o confianza.
2. Exigir un solo bloque PEM CERTIFICATE sin otros bloques, claves ni contenido ajeno. Decodificar su Base64 a DER con límites y rechazo de codificación inválida o bytes sobrantes. Parsear X.509, comprobar Basic Constraints `CA:true`, Key Usage `keyCertSign` si existe, sujeto igual a emisor, firma propia y vigencia de la raíz con el reloj local. Rechazar una hoja TLS usada como CA.
3. Calcular SHA-256 **localmente sobre DER**. Compararlo con `fingerprintSha256` y con el sufijo de `caId`. El hash del archivo PEM como texto produce otro resultado. Esta comprobación detecta respuestas inconsistentes, no la sustitución completa por un atacante.
4. Mostrar la huella calculada **completa**, sin truncarla. El anfitrión obtiene la suya de `lan.py start --auto` o `lan.py url` (fuente local); la comunica en persona o por un canal autenticado independiente de esta descarga. El texto del diálogo debe decir: «Compara los 64 caracteres con la huella que te comunicó el anfitrión por un canal independiente. Confirma únicamente si coinciden». Botones «He comparado y coincide» y «Cancelar». No basta un botón genérico «Confiar» ni una marca preseleccionada.
5. Ante diferencia/cancelación descartar la candidata, sin persistir ni iniciar OAuth. Si coincide, guardar la CA y la fecha/versión de la confirmación. No confiar automáticamente en el primer certificado recibido.

`X509Certificate.load_from_string()` permite cargar PEM, pero su API pública de GDScript no expone comprobaciones de Basic Constraints, emisor, KU, firma propia o fechas. **Que devuelva OK no sustituye esas comprobaciones.** El equipo debe incorporar un validador X.509 revisado (por ejemplo una extensión nativa o las APIs criptográficas de su cliente C#) para inspeccionarlas. No se inventa aquí un método GDScript `verify_ca` ni se atribuyen estas comprobaciones al parser de Godot. El backend las realiza sobre su propio archivo; un cliente debe validar también una respuesta manipulada. Referencia: [X509Certificate](https://docs.godotengine.org/en/stable/classes/class_x509certificate.html).

### Persistencia de confianza y conexión estricta

Guardar, mediante escritura atómica, `user://lan-trust/<sha256-der>.crt` y un índice de confianza con caId, fecha de confirmación y alias opcional del usuario. Validar el nombre a partir del hash **calculado**, nunca de una ruta del documento. Recalcular el hash del archivo al leerlo y comprobar vigencia. No sobrescribir una CA existente con otros bytes; no incluir secretos en este índice. Las direcciones recordadas son alias actualizables; la confianza pertenece al certificado DER. Para quitarla, ofrecer «Olvidar CA», mostrar qué perfiles la usan, cerrar conexiones afectadas y borrar su entrada/certificado; la próxima conexión requiere verificación otra vez.

Tras confirmar, cerrar la conexión de descubrimiento y crear conexiones **nuevas** para REST y WSS. Cargar solamente la CA del perfil seleccionado, usar `TLSOptions.client(ca)` sin `common_name_override` y comprobar cadena, vigencia e identidad de la IP literal de la URL mediante su SAN de tipo IP. No cargar automáticamente todas las CA guardadas en una sola bolsa global. La CA descargada no es el certificado hoja y no se compara su hash con el de la hoja.

Fragmento solo para la etapa ya confiada, después de validar/persistir la CA (no es un flujo completo de UI/errores):

```gdscript
var ca := X509Certificate.new()
var error := ca.load(trusted_ca_path) # ruta validada de user://, elegida por el almacén
if error != OK:
    return
var strict_tls := TLSOptions.client(ca)
rest_request.set_tls_options(strict_tls) # HTTPRequest del cliente normal
# Base construida exclusivamente con IP literal privada y puerto validados.
rest_request.request(base_url + "/actuator/health")
# Después de comprobar disponibilidad y sesión con el contrato existente:
game_socket.connect_to_url(wss_url, strict_tls) # WebSocketPeer
```

Las firmas son reales de Godot 4: [TLSOptions.client](https://docs.godotengine.org/en/stable/classes/class_tlsoptions.html), [HTTPRequest.set_tls_options](https://docs.godotengine.org/en/stable/classes/class_httprequest.html) y [WebSocketPeer.connect_to_url](https://docs.godotengine.org/en/stable/classes/class_websocketpeer.html). Hay que comprobar el resultado asíncrono de HTTPS y mantener `poll()`/estados de WSS como ya hace el cliente. La prueba de SAN de IP en el ejecutable y versión concreta exportada es obligatoria; no se ha ejecutado Godot en este repositorio. No reemplazar la IP por el CN ni omitir su comprobación para resolver fallos.

### Conexiones posteriores, varias CA y cambios

- **Mismo anfitrión/CA:** usar su CA persistida, abrir HTTPS estricto, health y luego el flujo de sesión habitual. No volver a pedir comparación mientras siga válida y no haya sido olvidada. Si se vuelve a descubrir, reconocer la candidata por su hash local y comprobar que coincide byte a byte con la CA verificada, antes de usar el archivo confiado; no confiar en el caId declarado.
- **A, B y C independientes:** cada CA tiene su entrada. Un solo ejecutable permite elegir perfil/IP y seleccionar su ancla. La confianza en A no autoriza B. Si B ya se verificó, no repetir su huella. Ante una IP antes asociada a A que ahora ofrece B, informar cambio y pedir selección explícita del perfil; no reasignarla silenciosamente, aunque B sea conocida.
- **Nueva IP con la misma CA:** actualizar la dirección. Si no se conoce aún su alias, descargar candidata en aislamiento, calcular su identidad y localizar la CA verificada. No requiere nueva comparación de huella. Probar una conexión nueva con esa CA y **SAN de la nueva IP**. `start --auto` conserva la CA y renueva/firma la hoja para la red actual; con una CA individual lo hace localmente.
- **Cambio inesperado de CA:** detener conexiones y sesión, mostrar huellas anterior/nueva completas, conservar la anterior. La nueva CA necesita comparación independiente y confirmación explícita; agregarla como entrada separada, sin sustitución silenciosa. Puede ser reinstalación legítima o ataque. Una CA con igual CN pero diferente DER es otra identidad.
- **Cambio de servidor:** cerrar WSS, cancelar HTTP y callback OAuth/intentos PKCE, borrar tokens activos y estado Lobby/Game del perfil anterior. Nunca enviar JWT/refresh/verifier anteriores al nuevo backend. Iniciar sesión o restaurar exclusivamente una sesión guardada para el perfil de esa instancia y validarla allí. `caId` identifica una autoridad, **no prueba continuidad de usuarios/partidas**: en la opción histórica de CA común varios servidores comparten ancla; mantener sus sesiones separadas. Para cambio de IP de la misma instancia se puede restaurar una sesión válida de ese perfil después de HTTPS estricto y `/api/auth/me`.

### Errores y recuperación

| Resultado | Contrato y acción |
|---|---|
| 503 `{"error":"LAN_CA_UNAVAILABLE"}` | CA ausente, ilegible, >16 KiB, malformada, no raíz/CA o fuera de vigencia. También una ruta montada como directorio al faltar el archivo. Conservar confianza previa; anfitrión revisa cert-check/diagnose y material existente. No generar otra CA automáticamente. |
| 400 `{"error":"INVALID_DISCOVERY_REQUEST"}` | GET con query no vacía o cuerpo/Transfer-Encoding; enviar solo la ruta exacta. Algunos clientes normalizan `?` vacío y lo omiten. |
| 405 `{"error":"METHOD_NOT_ALLOWED"}`, `Allow: GET` | POST/PUT/PATCH/DELETE/OPTIONS/HEAD en la ruta exacta HTTPS; HEAD no incluye cuerpo de respuesta. |
| 404 `{"error":"NOT_FOUND"}` | Otra ruta bajo `/.well-known/pixelchiss/`, incluido sufijo/barra; o petición al conector HTTP local. Fuera del prefijo siguen aplicándose las reglas previas del Gateway. |
| 400/4xx del servidor HTTP | URL/escapes/recorrido de ruta inválidos pueden rechazarse antes del filtro; no depender de un cuerpo JSON para esos casos. |
| 401/404 de imagen antigua/modo Local | No hay descubrimiento LAN disponible; comprobar perfil y revisión del Gateway. No sustituir por otra ruta o HTTP LAN. |
| Timeout/rechazo/conexión cortada | Revisar IP, puerto, Docker, firewall o aislamiento Wi-Fi. Reintentos de GET con backoff cancelable 1/2/4/8 s, sin modificar confianza. |
| Huella errónea, versión/esquema/PEM inválidos, tamaño >32 KiB | Rechazar candidata; no OAuth ni persistencia. 200 por sí solo no es éxito de confianza. |
| CA desconocida | Comparación independiente obligatoria; no aceptación automática. |
| Hoja TLS vencida/no vigente | Bloquear HTTPS/WSS, comprobar reloj y ejecutar start --auto en el anfitrión para renovar hoja. No borrar CA confiada válida ni relajar TLS. |
| CA vencida | Bloquear y solicitar mantenimiento/rotación explícita con nueva verificación; no se renueva sola ni se da por válida por estar guardada. |
| IP fuera del SAN/cadena ajena | Bloquear; corregir IP o renovar hoja con start --auto. No usar common_name_override ni reutilizar el socket provisional. |
| Redirección | Rechazar; el contrato no redirige. |

TLS puede fallar **antes** de recibir un 503 si el Gateway no puede arrancar con su keystore o el transporte no se establece. Los errores TLS de Godot pueden ser genéricos; no afirmar una causa específica sin evidencia. Guardar la CA no implica que un certificado futuro sea válido sin comprobación. Esta CA offline no ofrece OCSP/CRL y Godot documenta limitaciones de revocación; el usuario puede olvidar la CA y el anfitrión debe proteger su clave privada ([HTTPClient](https://docs.godotengine.org/en/stable/classes/class_httpclient.html)).

### Compatibilidad y alcance

El modo Local conserva `http://127.0.0.1:8080` y `ws://127.0.0.1:8080/api/game/ws`, sin descubrimiento ni CA privada. Google Desktop + PKCE continúa después de establecer TLS; el navegador usa confianza pública para Google y callback loopback del jugador. No poner PKCE ni OAuth en la conexión de descubrimiento. Lobby/Game/bots/reglas/reconexión mantienen sus contratos existentes. El backend no guarda ni decide la aceptación del jugador: almacén, diálogo y validación cliente corresponden al equipo Godot. Ver [solo los cambios nuevos para compañeros](GODOT_LAN_CERTIFICADOS_CAMBIOS.md).

## C. Comprobación de conectividad

Antes del login consultar `GET <base>/actuator/health`. Respuesta pública mínima: `{"status":"UP"}`. Comprueba Gateway, no promete que todos los servicios descubiertos estén ya listos. No requiere JWT ni devuelve credenciales. No consultar Eureka o bases de datos desde Godot.

Configurar timeout HTTP de 5–10 segundos y plazo propio de conexión WebSocket. Mostrar mensajes distintos para dirección/puerto inválidos, conexión rechazada, timeout, certificado no confiable, IP distinta al SAN, 401, 403 y 500/502/503 transitorios de descubrimiento tras arrancar. No bloquear el hilo principal. Reintentar disponibilidad con backoff (por ejemplo 1, 2, 4, 8 segundos, máximo configurable y botón cancelar). No sustituir HTTPS por HTTP ni desactivar JWT para recuperarse.

Una vez disponible, verificar la sesión con `/api/auth/me`. Durante arranque puede ser necesario esperar propagación Eureka/Gateway aun cuando health sea UP. Reintentar lecturas; para acciones con efecto conservar las reglas de idempotencia descritas abajo.

## D. Autenticación

### Flujo recomendado: Google Desktop + PKCE, válido para local y LAN

El administrador del proyecto Google debe crear un cliente OAuth de tipo **Desktop app**, configurar el consentimiento/usuarios de prueba y entregar su client ID a cada anfitrión. No usar el ID del cliente Web existente como si fuera Desktop. Configurar `GOOGLE_DESKTOP_CLIENT_ID` en el backend; si Google requiere el secreto de ese cliente, configurarlo solo en `GOOGLE_DESKTOP_CLIENT_SECRET` del anfitrión. Godot no necesita secretos OAuth.

1. Godot genera un `codeVerifier` criptográficamente aleatorio de 43–128 caracteres permitidos por PKCE. Recomendación: 32 bytes de `Crypto.generate_random_bytes`, Base64URL sin `=`. Calcula `codeChallenge = BASE64URL(SHA256(ASCII(codeVerifier)))`, sin padding (43 caracteres). Un verificador nuevo por intento.
2. Abrir un listener HTTP **solo en `127.0.0.1` del computador del jugador**, en puerto libre 1024–65535, antes de abrir el navegador. No escuchar callbacks en `0.0.0.0` ni en la IP del anfitrión.
3. Enviar a Auth por Gateway:

   ```http
   POST /api/auth/desktop/start
   Content-Type: application/json

   {"clientPort":45678,"codeChallenge":"<S256 de 43 caracteres>"}
   ```

   Respuesta 200, `Cache-Control: no-store`:

   ```json
   {"authorizationUrl":"https://accounts.google.com/o/oauth2/v2/auth?<parámetros>","state":"<aleatorio>","expiresAt":"<fecha UTC ISO-8601>"}
   ```

   Es un ejemplo de estructura: los marcadores no son valores válidos para enviar. Guardar el state y verifier en memoria, asociados a ese servidor/intento. Un 503 puede indicar Auth todavía no disponible o Desktop sin configurar; si persiste, el anfitrión debe revisar su configuración. No inventar un login alternativo ni omitir validaciones.
4. Abrir `authorizationUrl` en el **navegador del sistema** (`OS.shell_open`), no en un navegador embebido. La URL incluye client ID público, S256, state y nonce; no contiene tokens ni el verifier.
5. Google redirige al **jugador**, a `http://127.0.0.1:45678/callback?code=...&state=...`. Ese loopback no es el del anfitrión Docker. Godot valida path y coincidencia exacta del state antes de aceptar. Si Google devuelve `error`, cancelar limpiamente. No registrar la URL completa. Mostrar una página mínima sin recursos externos, `Cache-Control: no-store` y `Referrer-Policy: no-referrer`; cerrar el listener tras un callback válido o al cancelar/vencer.
6. Godot envía el código a su backend seleccionado, siempre por HTTPS en LAN:

   ```http
   POST /api/auth/desktop/exchange
   Content-Type: application/json

   {"state":"<state del inicio>","code":"<código Google>","codeVerifier":"<verificador original>"}
   ```

   Auth deriva el redirect del puerto guardado (no acepta un callback arbitrario), comprueba PKCE/TTL/uso único, intercambia con Google y valida firma del ID token, emisor, audiencia Desktop, expiración, nonce y correo verificado. El intento dura cinco minutos, sobrevive al reinicio y solo puede consumirse una vez. Un fallo del proveedor después de consumir requiere empezar de nuevo. No repetir un canje ambiguo como si fuera una renovación de sesión.
7. Respuesta 200 exclusivamente JSON:

   ```json
   {"accessToken":"<JWT PixelChiss>","refreshToken":"<refresh PixelChiss>"}
   ```

   No son tokens Google. No ponerlos en URLs, logs, capturas o archivos de configuración. Mantener access token en memoria. Para recordar refresh usar un almacén de credenciales del sistema operativo; si el cliente no dispone de uno, conservarlo solo en memoria.
8. Usar `Authorization: Bearer <accessToken>` en REST. `GET /api/auth/me` devuelve `id`, `email`, `displayName`, `createdAt`; convertir el id al identificador textual usado por Lobby/Game.
9. Renovar mediante `POST /api/refresh`, cuerpo `{"refreshToken":"..."}`; respuesta `{"accessToken":"..."}`. La validez actual del access token es 15 minutos. Refresh mantiene las reglas anteriores (30 días, revocable).
10. Cerrar sesión mediante `POST /api/logout`, cuerpo `{"refreshToken":"..."}`; respuesta `{"message":"Sesión cerrada correctamente"}`. Cerrar sockets y borrar credenciales locales. Logout revoca ese refresh; los JWT emitidos conservan su expiración previa.

Serializar la renovación: si varias peticiones reciben 401 a la vez, un solo componente renueva y comparte el nuevo access token. Si el refresh falla con 401, borrar la sesión y pedir login; no crear un bucle de renovación. No reenviar automáticamente acciones de juego con requestId nuevo tras un error de red. Al cambiar servidor, cancelar callbacks/canjes pendientes y separar cualquier credencial recordada por servidor; no enviar la sesión del anfitrión anterior al nuevo.

El retorno de Google y el canje pertenecen a conexiones distintas:

```mermaid
sequenceDiagram
    participant C as Godot del jugador
    participant B as Navegador del jugador
    participant A as Auth por Gateway del anfitrión
    participant G as Google
    C->>A: HTTPS POST desktop/start (puerto, challenge)
    A-->>C: authorizationUrl y state
    C->>B: Abrir navegador del sistema
    B->>G: Autorizar (S256, state, nonce, loopback)
    G-->>B: Redirigir con code y state
    B->>C: HTTP 127.0.0.1 del jugador /callback
    C->>A: HTTPS POST desktop/exchange (code, verifier, state)
    A->>G: HTTPS canje de código con PKCE
    G-->>A: ID token firmado
    A->>A: Validar identidad y consumo único
    A-->>C: JSON accessToken y refreshToken
```

Errores: 400 por parámetros inválidos; 401 por state/código/verificador/expiración/reutilización o validación Google fallida; 503 si Desktop no está configurado. Tratar errores HTTP como errores, no como una sesión. El servidor no devuelve los cuerpos de error del proveedor que pudieran contener datos sensibles.

### Compatibilidad del login web local anterior

Se conservan `/oauth2/authorization/google` y `/login/oauth2/code/google`, el cliente Google Web y su PKCE gestionado por Spring. Es un flujo local cuando el redirect registrado apunta al backend loopback del mismo computador. **No sirve como atajo LAN cambiando ese redirect por una IP privada. El conector TLS LAN rechaza las rutas del login Web con 400 y exige el flujo Desktop; se conservan en HTTP loopback.**

Para seguir usándolo, Godot debe añadir `client_challenge=<S256 propio>` al inicio junto a `client_state` y `client_port`. Tras el éxito recibe en su listener `?code=<handoff de PixelChiss>&state=...`, nunca access/refresh tokens. Validar client_state y canjear el handoff en `POST /api/auth/login/exchange` con `{"code":"...","codeVerifier":"..."}`. Caduca en 60 segundos y es de un solo uso. Un callback sin challenge válido falla cerrado con 400 y no emite tokens. Las versiones antiguas de Godot que esperaban tokens en la URL deben actualizarse; conservar ese retorno inseguro contradice los requisitos LAN. El state OAuth de Spring y el client_state de Godot siguen siendo distintos.

Referencia del flujo Desktop/loopback/PKCE: [Google OAuth para escritorio](https://developers.google.com/identity/protocols/oauth2/native-app). No se ha completado una autorización real de una cuenta Google en esta revisión; la prueba manual con las credenciales del equipo es obligatoria antes de dar por validado el login de usuario final.

## E. REST y WebSocket

Todas las rutas siguientes son públicas del **mismo Gateway**. No usar direcciones Docker, 8081, 8083, 8084, Redis o Eureka desde el cliente.

| Método y ruta | Cuerpo / uso | Resultado principal |
|---|---|---|
| GET `/actuator/health` | Sin JWT | Estado mínimo de Gateway |
| POST `/api/auth/desktop/start` | clientPort, codeChallenge | authorizationUrl, state, expiresAt |
| POST `/api/auth/desktop/exchange` | state, code, codeVerifier | accessToken, refreshToken |
| POST `/api/auth/login/exchange` | code, codeVerifier | Canje del retorno web local |
| POST `/api/refresh`, `/api/logout` | refreshToken | Renovación / revocación |
| GET `/api/auth/me` | Bearer | Perfil |
| PUT `/api/auth/me/display-name` | `{"displayName":"Ana_1"}` | Perfil actualizado |
| POST `/api/lobbies` | Bearer, sin cuerpo | 201, sala |
| GET `/api/lobbies/maps` | Bearer | Mapas `classic`, `science` |
| GET `/api/lobbies/{code}` | Bearer | Sala y versión; solo miembros |
| POST `/api/lobbies/{code}/join` | Bearer | Sala compartida |
| POST `/api/lobbies/{code}/leave` | Bearer | 204, sin cuerpo |
| PUT `/api/lobbies/{code}/ready` | `{"ready":true}` | Sala |
| PUT `/api/lobbies/{code}/vote` | `{"map_id":"classic"}` | Sala |
| POST `/api/lobbies/{code}/bots` | Bearer; administrador de sala | 201, sala |
| DELETE `/api/lobbies/{code}/bots/{bot_id}` | Bearer; administrador de sala | Sala |
| DELETE `/api/lobbies/{code}/players/{player_id}` | Bearer; administrador de sala | Sala |
| POST `/api/lobbies/{code}/start` | Bearer; administrador de sala | Sala STARTING/STARTED y game_id |
| GET `/api/game/board` | Bearer | Geometría v4-100 |
| GET `/api/game/games/{gameId}` | Bearer; miembro | Snapshot de partida |
| WS `/api/game/ws` | AUTH como primer frame | Estado, acciones y eventos |

Una sala contiene `code`, `host_id`, `creator_id`, `status`, `version`, `max_players`, `map_id`, `map_votes`, `players`, `banned_players`, `game_id`. Cada participante trae `user_id`, `display_name`, `role`, `ready`, `is_bot`, `voted_map_id`. No existe un WebSocket de Lobby: consultar GET sala periódicamente mientras está abierta. Errores de Lobby utilizan `{"detail":"..."}`; 403 indica permisos/miembro, 404 sala ausente, 409 estado incompatible o capacidad, 502/503 fallo de coordinación/descubrimiento. No volver a crear otra sala por un timeout de inicio: consultar la misma.

Ejemplo REST: enviar `POST <base>/api/lobbies` con `Authorization: Bearer <JWT del creador>` y sin cuerpo; guardar el `code` de la respuesta 201. El segundo jugador envía `POST <base>/api/lobbies/<code>/join` con **su propio JWT**, también sin cuerpo, y recibe la sala con ambos participantes. Después, cada cliente consulta `GET <base>/api/lobbies/<code>` y muestra los valores `display_name`, `ready`, `is_bot` y `role` recibidos. No hay una ruta pública para enumerar salas ajenas: el acceso se comparte por código. Solo cuando la respuesta indica `status="STARTED"` y trae `game_id`, abrir la partida; no derivar un gameId del código de sala.

WebSocket nativo, sin STOMP/SockJS, sin query string y sin tokens en la URL:

```json
{"type":"AUTH","token":"<accessToken>"}
{"type":"JOIN_GAME","gameId":"<game_id de Lobby>"}
```

Son frames separados; esperar `{"type":"AUTHENTICATED","playerId":"42"}` antes de JOIN_GAME. Se recibe `GAME_STATE` con `state` y `legalMoves`. La conexión nativa puede omitir Origin. No enviar un Origin arbitrario; la exportación navegador necesita allowlist explícita y tiene otro alcance OAuth.

Ejemplo de acción, solo para el actor activo y versión real:

```json
{"type":"ROLL_DICE","gameId":"game-123","requestId":"roll-1","expectedVersion":7}
```

Se recibe ACTION_ACK (`requestId`, `committedVersion`, `duplicate`, `state`) y GAME_UPDATED (`gameId`, `version`, `requestId`, `events`, `state`). Un error usa, por ejemplo, `{"type":"ERROR","code":"STALE_VERSION","message":"Resynchronize before acting","gameId":"game-123","requestId":"roll-1","version":8}`. Copiar movimientos de legalMoves, nunca inventar posiciones. Mantener las confirmaciones de animación, cartas/trivia, contadores y temporizadores del [contrato del motor](../game-service/docs/WEBSOCKET.md), que LAN no cambia.

## F. Flujo multijugador

1. Elegir conexión y comprobar Gateway/CA.
2. Autenticarse en Google desde el navegador del jugador y obtener sesión de ese backend.
3. Un jugador crea sala; otro usa su código en `/join`. El anfitrión del backend no necesita crearla.
4. Consultar GET sala para ver participantes y `version`; permitir ready/vote y administración según role.
5. El administrador de sala inicia; esperar `STARTED` y `game_id`. Si se perdió la respuesta, consultar la misma sala.
6. Abrir WS/WSS por Gateway, AUTH y JOIN_GAME.
7. Aplicar GAME_STATE y cargar tablero v4-100 por REST. No recalcular reglas en Godot.
8. Enviar acciones con requestId y expectedVersion; esperar confirmación/estado antes de construir la siguiente.
9. Procesar GAME_UPDATED por versión. ACK y evento pueden llegar en distinto orden: deduplicar por `(gameId,version)` y sincronizar ante huecos.
10. Ante interrupción, renovar JWT si corresponde, reconectar al mismo servidor, AUTH y SYNC_REQUEST con gameId. No reiniciar los temporizadores ni crear otra partida. Ante ACK perdido, solo reenviar idéntico payload/requestId para recuperar recibo.
11. Si fue retirado, terminó la partida o cambió el anfitrión del backend, mostrar el estado real y no intentar recuperar una partida en otra base de datos.

Mantener `WebSocketPeer.poll()` y manejo de cierres/ping/pong. La conexión vence con el JWT. Renovar y volver a autenticar mediante nueva conexión; no hay acción para reemplazar el token de una conexión existente.

Durante la reconexión deshabilitar acciones nuevas y mostrar el estado de conexión. Usar backoff cancelable, conservar el último gameId/versión y aplicar el snapshot autoritativo antes de habilitar la UI. La cuenta regresiva que muestra Godot es informativa: reconectar no concede otra tirada ni reinicia los 30 segundos de movimiento o los 15 de trivia. Respetar la pausa de movimiento y los identificadores de animación ya definidos en el contrato Game, sin temporizadores de reglas nuevos en el cliente.

## G. Checklist para Godot

| Obligación | Componente sugerido | Endpoint / API | Resultado y prueba |
|---|---|---|---|
| Obligatorio: selector local/LAN y puerto | ConnectionConfig.gd + menú | Base única | Local sin introducir IP; cambiar host no reutiliza sesión |
| Obligatorio: confianza CA y SAN | HttpClient.gd / GameSocket.gd | TLSOptions.client | CA válida conecta; CA ajena/IP incorrecta falla |
| Obligatorio: disponibilidad y timeout | ConnectionStatus.gd | GET health | Servidor ausente no bloquea UI; reintentos cancelables |
| Obligatorio: PKCE criptográfico | AuthSession.gd | Desktop start | Cada intento cambia verifier y challenge S256 |
| Obligatorio: callback solo loopback | OAuthLoopback.gd | 127.0.0.1:puerto/callback | Rechaza state ajeno, cancela error y cierra listener |
| Obligatorio: canje por cuerpo TLS | AuthSession.gd | Desktop exchange | No tokens en URLs/logs; código repetido no autentica |
| Obligatorio si conserva login Web local | AuthSession.gd | client_challenge + login/exchange | Consume handoff y no espera tokens en callback |
| Obligatorio: sesión, refresh/logout | AuthSession.gd | refresh/logout/me | Dos jugadores mantienen identidades independientes |
| Obligatorio: sala compartida y polling | LobbyClient.gd | Rutas Lobby | Crear/unirse/listar/ready/start en ambos mapas |
| Obligatorio: AUTH/estado/versiones | GameSocket.gd | WS/WSS | Ambos observan mismos dados, turnos y movimientos |
| Obligatorio: reconexión | GameSocket.gd | SYNC_REQUEST | Misma partida y plazos, sin acciones duplicadas |
| Ya ofrece backend: reglas/bots/EDA/SAGA | No duplicar en Godot | Contrato Game | Ejecutar regresiones visuales y de animación |
| Obligatorio LAN: persistir varias CA verificadas | LanTrustStore.gd + configuración existente | user://lan-trust/ | Identidad SHA-256 DER; no repetir verificación por IP, sin secretos |
| Opcional: recordar refresh seguro | Integración SO | Keychain/credential vault | Sin almacén seguro, mantener solo en memoria |

Pruebas pendientes del equipo: login Google real con su Desktop client, Godot real, Windows como anfitrión y partida física Windows↔Linux. Clientes simulados y contenedores no sustituyen esas pruebas.
