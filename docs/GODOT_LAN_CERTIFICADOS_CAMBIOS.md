# Cambios nuevos para Godot: certificados LAN

Esta entrega modifica solo PixelChissServer. Integrar estos pasos en el selector LAN, el cliente HTTP y el socket existentes; no reconstruir escenas de juego, tablero, Lobby ni reglas. Contrato completo y ejemplo PEM real: [Descubrimiento automático de CA y primera conexión a un anfitrión LAN](GODOT_INTEGRACION_LAN.md#descubrimiento-automático-de-ca-y-primera-conexión-a-un-anfitrión-lan).

## A. Qué cambia respecto a la implementación anterior

| Antes | Ahora | Acción necesaria |
|---|---|---|
| Obtener ca.crt manualmente | El Gateway entrega una CA pública candidata | Añadir una descarga aislada, sin credenciales |
| Seleccionar/importar un archivo | Comparar una huella completa y confirmar | Añadir diálogo de verificación independiente |
| Una CA configurada para la conexión | Varias CA públicas verificadas en user:// | Añadir almacén por SHA-256 DER y selector de perfil |
| Dirección utilizada como referencia del servidor | IP actualizable; identidad de confianza criptográfica | Separar alias IP, CA y sesión del backend |
| Cambiar de servidor ajustando la conexión | Elegir su CA y aislar su sesión | Cancelar conexiones/intentos del servidor previo |
| HTTPS/WSS con CA configurada | HTTPS/WSS con la CA guardada que corresponde | Reutilizar los clientes existentes con TLS estricto |

## B. Qué debe mostrar la pantalla LAN

Mantener los campos de IP privada y puerto (8443 predeterminado) y el estado de conexión. No solicitar certificados al jugador. Añadir estados «Descargando CA candidata», «Pendiente de verificar huella», «Comprobando conexión segura», error y cancelar. No abrir Google ni Game antes de completar confianza y HTTPS estricto.

El diálogo muestra IP/puerto, huella SHA-256 calculada completa y: «Compara los 64 caracteres con la huella que te comunicó el anfitrión por un canal independiente. Confirma únicamente si coinciden». Ofrecer «He comparado y coincide» y «Cancelar», sin confirmación preseleccionada. El anfitrión consulta su huella en `lan.py url` sin reiniciar Docker. No introducir códigos ni cuentas de emparejamiento.

## C. Cómo descargar la CA pública

Endpoint: `GET https://<IP>:<puerto>/.well-known/pixelchiss/ca`. HTTPS en el mismo puerto 8443; sin query ni cuerpo. Ningún header de aplicación es obligatorio; opcional `Accept: application/json`. No enviar Authorization, cookies, JWT ni OAuth/PKCE.

El 200 es JSON con estos cuatro campos:

```json
{
  "protocolVersion": 1,
  "caId": "sha256:<64 dígitos hex minúsculos>",
  "fingerprintSha256": "<64 dígitos hex minúsculos>",
  "caPem": "-----BEGIN CERTIFICATE-----\n<Base64 DER de la CA>\n-----END CERTIFICATE-----\n"
}
```

Los marcadores describen estructura; no son certificados válidos. La [guía técnica](GODOT_INTEGRACION_LAN.md#descubrimiento-automático-de-ca-y-primera-conexión-a-un-anfitrión-lan) incluye un ejemplo completo coherente. La respuesta lleva `Cache-Control: no-store` y `X-Content-Type-Options: nosniff`.

La primera descarga todavía no puede autenticar al servidor. Usar un `HTTPRequest` nuevo exclusivamente para esta ruta, sin credenciales, con TLS provisional (`TLSOptions.client_unsafe()` solo en ese objeto). Limitarlo a IP literal privada/puerto validados y ruta fija; `max_redirects = 0`, `timeout = 10.0`, `body_size_limit = 32768`, `accept_gzip = false`. No compartir cliente, opciones o conexión con Auth/REST/WSS; destruirlo al finalizar. No es un bypass global. La respuesta permanece no confiable aunque incluya su propio hash.

Validar resultado HTTP 200 y Content-Type JSON, tipos/versión, un único PEM CERTIFICATE y DER sin bytes sobrantes. Comprobar X.509 raíz autofirmada, Basic Constraints CA:true, keyCertSign si hay Key Usage, sujeto/emisor, firma propia y vigencia. El servidor limita el archivo PEM a 16 KiB; el cliente limita el documento a 32 KiB. Nunca aceptar hojas TLS como autoridades ni rutas/URLs de la respuesta.

`X509Certificate.load_from_string()` parsea PEM, pero no expone todas esas comprobaciones en GDScript. Hace falta un validador X.509 revisado mediante extensión nativa o APIs del cliente C#, según la implementación existente. No inventar funciones de Godot ni asumir que parsear equivale a validar CA. Las interfaces verificadas se enlazan en la guía técnica.

| Error exacto del recurso | Acción |
|---|---|
| 503 `{"error":"LAN_CA_UNAVAILABLE"}` | CA ausente/ilegible/no válida/fuera de vigencia; avisar al anfitrión, sin cambiar confianza |
| 400 `{"error":"INVALID_DISCOVERY_REQUEST"}` | Quitar query/cuerpo/Transfer-Encoding; GET de ruta fija |
| 405 `{"error":"METHOD_NOT_ALLOWED"}`, Allow: GET | Corregir método; HEAD no trae cuerpo |
| 404 `{"error":"NOT_FOUND"}` | Ruta bajo el prefijo incorrecta o conector HTTP; usar URL exacta HTTPS |
| 401/404 de versión previa/perfil local | Actualizar/iniciar Gateway LAN; no usar HTTP LAN |
| 4xx antes del recurso | El servidor puede rechazar URL/recorrido malformados sin el JSON anterior |
| Timeout, rechazo, TLS que no establece transporte | Mostrar error de conexión; backoff cancelable, sin borrar CA |
| Redirección, JSON/PEM inválido, >32 KiB | Rechazar y descartar candidata |

No todos los fallos de TLS permiten recibir una respuesta HTTP. No tratar un error genérico de handshake como una causa de certificado específica sin diagnóstico.

## D. Cómo comprobar la huella

Decodificar el bloque PEM a DER y calcular SHA-256 localmente. Mostrar 64 caracteres `[0-9a-f]` sin separadores; no calcular el hash del texto PEM. Comprobar coincidencia con `fingerprintSha256` y `caId == "sha256:" + hash`. Después exigir comparación con la huella que el anfitrión obtuvo localmente y comunicó por un canal independiente y confiable.

Una respuesta manipulada puede contener otra CA y su hash correcto. Comparar solo los dos campos del JSON no autentica al anfitrión. Si la comparación independiente falla o el jugador cancela, descartar sin guardar, iniciar sesión ni conectar Game. No implementar confianza automática en la primera conexión.

## E. Cómo guardar la confianza

Añadir, por ejemplo, `LanTrustStore.gd`, aprovechando la configuración existente. Persistir atómicamente CA pública en `user://lan-trust/<hash-calculado>.crt` e índice con caId, fecha de confirmación y alias opcional. Revalidar hash/vigencia al cargar. No usar el caId recibido como nombre de archivo sin validar; no sobrescribir silenciosamente entradas. Los datos no contienen claves privadas, tokens, state ni verifier.

Ofrecer «Olvidar CA», mostrando los perfiles afectados: cerrar sus conexiones y retirar la entrada/archivo. La siguiente conexión exige comparación otra vez. Es un almacén de la aplicación, no una instalación de certificados en Windows/Linux.

## F. Cómo utilizarla para HTTPS/WSS

Después de la confirmación, destruir la conexión provisional. Cargar la CA verificada del perfil en `X509Certificate`, crear `TLSOptions.client(ca)` y aplicarlo al `HTTPRequest` existente con `set_tls_options`; pasar esas opciones a `WebSocketPeer.connect_to_url(wss_url, options)`. No establecer common_name_override ni utilizar opciones inseguras en esos clientes.

El certificado hoja debe encadenar a esa CA, estar vigente y tener la IP actual en SAN de tipo IP. Mantener los errores bloqueantes. Si vence la hoja o cambia la IP, el anfitrión ejecuta start --auto para renovar con su CA existente; el jugador no olvida una CA válida por ese motivo. Ante CA vencida, requiere mantenimiento y rotación explícita. Verificar SAN/vigencia en la versión nativa exportada de Godot: esta entrega no ejecutó el cliente.

## G. Cómo manejar múltiples anfitriones

Guardar A/B/C como perfiles independientes con su ancla, dirección y contexto de sesión. Solo pasar al cliente TLS la CA del perfil elegido. Reconocer una CA guardada por el hash calculado y sus bytes; no por CN, IP o caId declarado. Una CA ya verificada no necesita nueva comparación.

Al cambiar de IP, descargar candidata si hace falta localizar el ancla y reconocer su hash persistido. Usar la CA guardada para la conexión estricta a la nueva IP; exigir SAN actualizado. No repetir verificación solo por cambiar IP/puerto.

Si la IP de A ofrece otra CA, detener el flujo y mostrar huellas anterior/nueva. No reemplazar A. Si es un servidor B conocido, pedir selección explícita de su perfil; si es una CA nueva, exigir comparación independiente y guardarla separadamente. Conservar la entrada anterior hasta que el usuario la retire.

Al cambiar de backend cerrar WSS, cancelar HTTP, listener OAuth y PKCE pendientes y limpiar tokens/estado de Lobby/Game activos. Nunca enviar JWT/refresh del servidor anterior. Restaurar solo una sesión del perfil seleccionado o iniciar login. Una CA identifica al emisor; si se usa la opción histórica de CA común, no identifica por sí sola la instancia/persistencia. El cambio de IP de la misma instancia puede conservar su sesión válida, comprobada por HTTPS y `/api/auth/me`.

## H. Compatibilidad existente

Google Desktop + PKCE, JWT/refresh/logout, Lobby, AUTH/JOIN_GAME/SYNC_REQUEST y reglas de Game conservan sus contratos. Ejecutarlos después de HTTPS estricto; el descubrimiento no contiene datos OAuth. Mantener callback en loopback del jugador. No cambiar bots, tablero, pares/capturas, temporizadores, animaciones o recuperación de partidas.

Modo Local mantiene HTTP/WS en 127.0.0.1:8080 sin CA ni descubrimiento. Cambiar de modo también debe cerrar la sesión/conexión anterior. No extender HTTP a IPs LAN.

## I. Pruebas obligatorias para Godot

1. Mismo `.exe` sin certificados empaquetados: conectar a A y B con CA distintas, verificar cada una y alternar sin filtrar sesiones.
2. Reiniciar cliente: reconocer ambas CA y conectar por HTTPS/WSS estricto sin repetir comparación.
3. Rechazar huella incorrecta/cancelación y una CA manipulada con hash internamente coherente; comprobar que no se guarda ni envía OAuth/JWT.
4. Cambiar IP de A conservando CA; reconocerla sin nuevo emparejamiento. Antes de renovar SAN debe fallar; tras start --auto debe conectar.
5. Rechazar hoja vencida, CA vencida, IP fuera de SAN y cadena ajena; sin fallback HTTP/TLS inseguro.
6. Simular cambio inesperado de CA: no sobrescribir; exigir verificación nueva y preservar entrada anterior.
7. Probar 503, servidor ausente, JSON/PEM inválidos, versión incorrecta, redirección, tamaño excesivo y cancelación.
8. Olvidar CA y comprobar que exige verificación de nuevo; conservar modo Local y completar login Google real con PKCE, Lobby y reconexión Game existentes.

## J. Límites y pendientes

Backend implementado: recurso HTTPS público limitado, CA/huella/identidad estables, montaje público de solo lectura, comprobación del recurso contra la CA local durante start/diagnose y salida de URLs/huella. No hay puertos ni reglas de firewall nuevos. La renovación de hojas con CA conservada ya existía y se mantiene.

Pendiente Godot: incorporar la descarga aislada y su manejo asíncrono, validador X.509, diálogo, persistencia, olvido, asociación de perfiles y aislamiento de sesiones en los scripts existentes. Nombres de scripts sugeridos no suponen una estructura nueva obligatoria. Esta entrega no contiene un proyecto Godot ni implementa su UI; las pruebas de HTTPS/WSS con Java y fixtures no acreditan un `.exe`, login Google real, Windows ni una partida entre dos computadores. Consultar [evidencia y límites de backend](LAN_AUTO_VALIDACION.md).
