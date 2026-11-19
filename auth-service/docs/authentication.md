> Actualización LAN y seguridad: el callback Web ya no entrega tokens en URL. Godot debe enviar client_challenge S256 y canjear el handoff por POST /api/auth/login/exchange; para LAN usar OAuth Desktop + PKCE y TLS. Véanse [integración Godot](../../docs/GODOT_INTEGRACION_LAN.md) y [manual LAN](../../docs/MANUAL_SERVIDOR_LAN.md). Las caracterizaciones y ejemplos históricos de tokens en callback no describen el comportamiento vigente.

# Flujos, seguridad y contratos

## Inicio de sesión desde Godot

El flujo de escritorio para LAN/local utiliza POST `/api/auth/desktop/start` y `/api/auth/desktop/exchange`. Godot genera PKCE S256 y abre su listener loopback; Auth fija `http://127.0.0.1:puerto/callback`, genera state/nonce, persiste un intento de cinco minutos y devuelve una autorización Google Desktop. El navegador del jugador recibe el código Google en su propio loopback; Godot lo envía por cuerpo HTTPS al anfitrión junto a state/verifier. Auth consume el intento atómicamente antes de intercambiar con Google, verifica firma/emisor/audiencia/nonce/expiración/correo verificado y emite la sesión PixelChiss exclusivamente en JSON no-store. El secreto Desktop opcional permanece en el anfitrión. Sin Desktop client ID configurado, inicio responde 503.

La tabla login_attempts guarda hashes de códigos/states, challenge, nonce, redirect fijo, expiración y consumo, no códigos Google ni sus tokens. Consume una sola petición concurrente y mantiene expiración/uso único tras reinicios. Un fallo posterior del proveedor exige otra autorización. El servidor no toma una redirect URI libre del cliente.

### Login Web local conservado

1. Godot abre listener loopback y navegador a `/oauth2/authorization/google?client_state=...&client_port=...&client_challenge=...` por el Gateway local. client_challenge es S256 de un verifier propio de Godot.
2. ClientLoginParamsFilter valida state/puerto como antes y, si se proporciona challenge, valida 43 caracteres Base64URL y lo guarda en sesión. Un inicio antiguo puede todavía alcanzar Google; un callback sin challenge válido falla con 400 sin emitir credenciales. El cliente debe actualizarse.
3. Spring conserva su propio state, nonce OIDC y PKCE de Google Web, guarda la autorización en sesión y realiza el intercambio cuando Google vuelve al backend por `/login/oauth2/code/google`.
4. OAuth2LoginSuccessHandler **primero** valida la sesión de retorno/challenge. Busca o crea el usuario por Google sub y emite un handoff de 60 segundos vinculado al challenge. No emite access/refresh antes de validar el retorno.
5. Invalida la sesión y redirige solo a `http://127.0.0.1:puerto/callback?code=<handoff>&state=<client_state>`, con no-store/no-referrer y codificación de parámetros.
6. Godot valida client_state y canjea por POST `/api/auth/login/exchange`, cuerpo code/codeVerifier. Solo entonces obtiene accessToken/refreshToken en JSON no-store. No hay credenciales de sesión en la URL. Handoff inválido/usado/vencido o verifier ajeno responde 401.

El login Web se conserva por HTTP loopback; el conector TLS LAN lo rechaza con 400 para no enviar al jugador remoto al callback del backend en localhost. LAN usa Desktop. No se sustituye el redirect Web registrado por una IP privada.

## States y PKCE

En Web son distintos el state OAuth de Spring y el client_state de Godot. También son distintas la prueba PKCE Web de Spring y la prueba de posesión PKCE del handoff Godot. En Desktop el state/nonce pertenecen al intento persistido y el challenge de Godot se utiliza tanto para Google como para el canje en Auth. No reutilizar verifiers entre intentos.

La regresión de Spring Security comprueba requireProofKey, S256, el verifier guardado en autorización y su inclusión en la solicitud de token; no se elimina PKCE del flujo Web. El nuevo dominio Pkce también verifica el vector RFC7636. La validación criptográfica de ID tokens Desktop se prueba con JWT firmados de prueba y rechazos de firma/claims. No se efectuó login real contra Google ni se ejecutó Godot.

El cliente Web conserva GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET y OAUTH_REDIRECT_URI; el cliente Desktop utiliza otras variables: GOOGLE_DESKTOP_CLIENT_ID y, si lo requiere Google, GOOGLE_DESKTOP_CLIENT_SECRET. Ambos secretos pertenecen al anfitrión. Procedimiento completo en [manual LAN](../../docs/MANUAL_SERVIDOR_LAN.md) y [guía Godot](../../docs/GODOT_INTEGRACION_LAN.md).

## Spring Security y JWT

[`SecurityConfig`](../src/main/kotlin/com/pixelchiss/auth/security/SecurityConfig.kt)
mantiene CSRF deshabilitado y permite:

- Dispatcher `ERROR`.
- `/actuator/health`, `/oauth2/**`, `/login/**`.
- `/api/auth/refresh`, `/api/auth/logout`, `/api/auth/desktop/start`, `/api/auth/desktop/exchange`, `/api/auth/login/exchange`.
- `/swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/**`.

Las demás solicitudes requieren autenticación, sin nuevas restricciones por
roles. El resource server procesa `Authorization: Bearer {accessToken}` y usa
la clave pública configurada en `spring.security.oauth2.resourceserver.jwt`.
Una ruta pública puede responder 401 si se envía un Bearer inválido: los filtros
de autenticación siguen procesando ese header antes de la autorización.

[`JwtService`](../src/main/kotlin/com/pixelchiss/auth/security/JwtService.kt)
carga una clave privada PEM PKCS#8 RSA desde `jwt.private-key`, de forma lazy.
El contenido se decodifica Base64 y se convierte con `KeyFactory("RSA")`. JJWT
firma con `signWith(privateKey)`, manteniendo la selección automática del
algoritmo según la clave; con la clave RSA de 2048 bits generada en las pruebas
se obtiene RS256. No se fija un algoritmo nuevo ni se cambia el formato PEM.

Los claims propios son exactamente `sub` (id de usuario como string), `email`,
`iat` y `exp`. El access token dura 15 minutos. La verificación entrante pertenece
al decoder de Spring, no a `JwtService`. No se agregan validadores de
issuer/audience a los JWT PixelChiss (los ID tokens Google Desktop sí se validan), ni se altera la tolerancia temporal predeterminada de Spring.
[`JwtAuthenticationConverter`](../src/main/kotlin/com/pixelchiss/auth/security/JwtAuthenticationConverter.kt)
retorna `JwtAuthenticationToken(jwt, emptyList())` y conserva subject como nombre
del principal. El controlador convierte subject a `Long` para consultar perfiles.

Las rutas `jwt.private-key`, `jwt.public-key` y la clave pública del resource
server siguen dependiendo de `JWT_PRIVATE_KEY_PATH` y `JWT_PUBLIC_KEY_PATH`.
Las pruebas generan claves propias y no leen las claves privadas de producción.

## Refresh y logout

[`RefreshTokenService`](../src/main/kotlin/com/pixelchiss/auth/service/RefreshTokenService.kt)
crea un UUID aleatorio, lo persiste con user id y `Instant.now() + 30 días`, y
comienza con `revoked=false`. Para validarlo busca por token, rechaza inexistentes
o revocados, y rechaza `expiresAt.isBefore(Instant.now())`. Se conserva esa
comparación estricta; no se reemplaza por `<=` ni por una ventana de tolerancia.

[`AuthService`](../src/main/kotlin/com/pixelchiss/auth/service/AuthService.kt)
coordina refresh: verifica el refresh, busca su usuario y firma otro access
token. No crea ni devuelve un refresh nuevo, no cambia su expiración y no lo
rota. El mismo refresh puede reutilizarse hasta expirar o revocarse.

Logout busca el token suministrado y guarda `revoked=true`; si no existe, no hace
nada. Repetir logout sigue dando 200. No revoca otros refresh del usuario ni
JWT ya emitidos; esos JWT siguen sometidos a su expiración normal.

## Contratos REST y DTOs

| Método / ruta de Auth | Entrada | Respuesta de éxito | Errores propios |
| --- | --- | --- | --- |
| `POST /api/auth/refresh` | [`RefreshTokenRequest`](../src/main/kotlin/com/pixelchiss/auth/dto/RefreshTokenRequest.kt): `{"refreshToken":"..."}` | 200, [`TokenResponse`](../src/main/kotlin/com/pixelchiss/auth/dto/TokenResponse.kt): `{"accessToken":"..."}` | 401 refresh inválido/expirado/revocado o usuario inexistente |
| `POST /api/auth/logout` | [`LogoutRequest`](../src/main/kotlin/com/pixelchiss/auth/dto/LogoutRequest.kt): `{"refreshToken":"..."}` | 200, [`MessageResponse`](../src/main/kotlin/com/pixelchiss/auth/dto/MessageResponse.kt): `{"message":"Sesión cerrada correctamente"}` | Sin error propio para token inexistente |
| `GET /api/auth/me` | Principal JWT | 200, [`UserProfileResponse`](../src/main/kotlin/com/pixelchiss/auth/dto/UserProfileResponse.kt) | 401 subject inválido; 404 usuario inexistente |
| `PUT /api/auth/me/display-name` | Principal JWT y [`UpdateDisplayNameRequest`](../src/main/kotlin/com/pixelchiss/auth/dto/UpdateDisplayNameRequest.kt): `{"displayName":"..."}` | 200, `UserProfileResponse` | 401 subject inválido; 400 formato; 404 usuario inexistente |
| `GET /api/test/protected` | Autenticación | 200, `{"message":"JWT válido","user":"{subject}"}` | Errores de autenticación de Spring |

Un perfil contiene exactamente `id` numérico, `email`, `displayName` y
`createdAt` como timestamp ISO-8601. Los DTOs no se modifican. Los strings de
solicitud no nulos conservan la deserialización Kotlin/Jackson: cuerpo malformado
o campo obligatorio ausente/null responde 400. No se añaden Bean Validation ni
normalizaciones nuevas. Para displayName se conserva `trim()` y el patrón
`^[A-Za-z0-9_]{3,24}$`, incluyendo ASCII, dígitos y guion bajo.

## Excepciones y su orden

Se conservan `ResponseStatusException`, códigos y razones:

| Situación | Estado | Razón |
| --- | --- | --- |
| Refresh inválido, expirado o revocado | 401 | `Refresh token inválido o expirado` |
| Usuario inexistente en refresh | 401 | `Usuario no encontrado` |
| Subject no convertible a Long | 401 | `ID de usuario inválido` |
| Perfil inexistente | 404 | `Usuario no encontrado` |
| Nombre no válido | 400 | `El nombre debe tener entre 3 y 24 caracteres: letras, números o guion bajo` |

Al actualizar un nombre se valida primero subject, después nombre y finalmente
existencia del usuario. Cambiar ese orden alteraría el error visible cuando hay
varios problemas simultáneos. Las pruebas lo protegen. No se introduce un
exception advice ni se cambia la representación de errores de Spring Boot. La
razón de la excepción no implica que Boot la incluya por defecto en su JSON de
error; se conserva la configuración existente.

El handler OAuth mantiene requireNotNull para atributos Google incompletos; una sesión de retorno/challenge inválida devuelve ahora 400 antes de emitir credenciales. Los nuevos canjes utilizan errores genéricos sin cuerpos del proveedor.
Las excepciones de lectura de claves y de persistencia también conservan su
propagación original.

## Gateway y Eureka

Auth conserva nombre `auth-service`, puerto 8081 y registro Eureka mediante
`EUREKA_URL`. El Gateway utiliza `lb://AUTH-SERVICE` y estas rutas verificadas
por lectura de `api-gateway/src/main/resources/application.yaml`:

| Ruta pública del Gateway | Ruta destino en Auth |
| --- | --- |
| `POST /api/auth/desktop/start` | Misma ruta |
| `POST /api/auth/desktop/exchange` | Misma ruta |
| `POST /api/auth/login/exchange` | Misma ruta |
| `POST /api/refresh` | `POST /api/auth/refresh` |
| `POST /api/logout` | `POST /api/auth/logout` |
| `/oauth2/authorization/**` | Misma ruta |
| `/login/oauth2/code/**` | Misma ruta |
| `GET /api/auth/me` | Misma ruta |
| `PUT /api/auth/me/display-name` | Misma ruta |

Gateway valida JWT para sus rutas privadas y Auth vuelve a validarlo. La sesión
HTTP de OAuth debe acompañar el navegador en inicio y callback. Las rutas nuevas de canje se añaden al Gateway; cookies/discovery y la URI Web configurada se conservan. LAN añade TLS sin alterar los JWT propios. La
prueba local de Auth no equivale a una ejecución de la cadena completa de
Gateway/Eureka/Google/Godot.

## Verificación en dos pasos

El proveedor puede pedir un segundo factor a una cuenta que lo tenga habilitado. PixelChiss recibe el éxito de OAuth, pero no exige ni comprueba el nivel de autenticación de la cuenta antes de emitir tokens. `state`, PKCE, JWT y refresh tienen otras funciones y no cuentan como factores. Ver [alcance de la revisión y demostración de MFA](../../docs/revision-solid-mfa.md).
