# Flujos, seguridad y contratos

## Inicio de sesión desde Godot

1. Godot abre un listener local y un navegador hacia
   `GET /oauth2/authorization/google?client_state=...&client_port=...` a través
   del Gateway. Auth no incluye un endpoint REST propio de login con contraseña.
2. [`ClientLoginParamsFilter`](../src/main/kotlin/com/pixelchiss/auth/security/ClientLoginParamsFilter.kt)
   se ejecuta antes de `OAuth2AuthorizationRequestRedirectFilter`. Sólo procesa
   URIs que empiezan con `/oauth2/authorization/`. `client_state` debe cumplir
   `^[A-Za-z0-9_-]{16,128}$` y `client_port` debe ser entero entre 1024 y 65535.
   Si falla, responde 400, `text/plain;charset=UTF-8`, con:
   `Faltan client_state / client_port válidos. Inicia el login desde el juego.`
   Si son válidos, los guarda en la sesión como `CLIENT_LOGIN_STATE` y
   `CLIENT_LOGIN_PORT` y continúa la cadena. No se cambia este filtro.
3. Spring Security construye la autorización Google con `response_type=code`,
   client id, scopes `openid profile email`, redirect URI, `state`, nonce OIDC y
   PKCE. Conserva la solicitud en la sesión HTTP y redirige el navegador a
   `https://accounts.google.com/o/oauth2/v2/auth`.
4. Google devuelve el navegador a `/login/oauth2/code/google`. Spring Security
   correlaciona el state OAuth con la solicitud en sesión, intercambia el código
   y autentica al usuario. Estos pasos pertenecen al framework: Auth no los
   reemplaza ni implementa un callback REST adicional.
5. [`OAuth2LoginSuccessHandler`](../src/main/kotlin/com/pixelchiss/auth/security/OAuth2LoginSuccessHandler.kt)
   lee `sub`, `email`, `name` del `OAuth2User`, exigiendo los tres con
   `requireNotNull`. Busca por Google id. Si existe, conserva los datos guardados;
   si no, crea el usuario con esos atributos. No vincula usuarios por email.
6. El handler genera un access token con el id/email persistidos y crea un
   refresh token asociado al id. **Este orden se conserva:** sólo después lee
   los parámetros del cliente desde la sesión y exige `String`/`Int` válidos.
7. Invalida la sesión completa y codifica con `URLEncoder` UTF-8 los tokens y
   state. Redirige con 302 a:

   ```text
   http://127.0.0.1:{client_port}/callback?accessToken={token}&refreshToken={refresh}&state={client_state}
   ```

El cliente debe correlacionar el state de su callback y conservar los tokens.
La implementación Godot no está disponible aquí para revisar cómo lo hace.
La ruta, host loopback, nombres de query y orden de los parámetros se conservan.
No se introducen cookies propias ni mecanismos de transporte nuevos: la sesión
OAuth y sus cookies siguen gestionadas por Spring/Servlet.

## Dos states y PKCE

`client_state` se entrega al callback local de Godot. El filtro valida su formato
y el handler lo devuelve. Es distinto del `state` OAuth que genera y valida
Spring Security para correlacionar la autorización de Google. Las pruebas
comprueban que son distintos y que un state OAuth incorrecto causa redirección
a `/login?error` sin intercambiar el código con Google.

Aunque no hay código PKCE propio ni configuración explícita en Auth, en la
versión instalada **Spring Security 7.1.1** `ClientRegistration.ClientSettings`
usa `requireProofKey=true` por defecto. La caracterización previa al refactor
confirmó ese valor en el bean real y los parámetros de la autorización real:

- `code_challenge_method=S256`.
- `code_challenge=BASE64URL(SHA256(code_verifier))`, sin padding.
- `code_verifier` queda en los atributos de la autorización guardada en sesión;
  no se incluye en la redirección del navegador.
- El convertidor predeterminado de la solicitud de token incluye el mismo
  verifier junto con `code` y `grant_type=authorization_code`.

Google verifica el challenge/verifier durante el intercambio. Las pruebas
locales comprueban la generación y preparación de parámetros; no afirman haber
realizado ese intercambio con Google. No se añade ni desactiva PKCE.

[`application.yaml`](../src/main/resources/application.yaml) mantiene
`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `OAUTH_REDIRECT_URI` (por defecto
`http://localhost:8081/login/oauth2/code/google`), los scopes y URIs Google
de autorización, token y user-info. No se cambian proveedores ni callbacks.

## Spring Security y JWT

[`SecurityConfig`](../src/main/kotlin/com/pixelchiss/auth/security/SecurityConfig.kt)
mantiene CSRF deshabilitado y permite:

- Dispatcher `ERROR`.
- `/actuator/health`, `/oauth2/**`, `/login/**`.
- `/api/auth/refresh`, `/api/auth/logout`.
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
issuer/audience, ni se altera la tolerancia temporal predeterminada de Spring.
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

El handler OAuth mantiene `requireNotNull` y su `IllegalArgumentException` para
atributos Google o sesión incompletos; no los transforma en errores REST nuevos.
Las excepciones de lectura de claves y de persistencia también conservan su
propagación original.

## Gateway y Eureka

Auth conserva nombre `auth-service`, puerto 8081 y registro Eureka mediante
`EUREKA_URL`. El Gateway utiliza `lb://AUTH-SERVICE` y estas rutas verificadas
por lectura de `api-gateway/src/main/resources/application.yaml`:

| Ruta pública del Gateway | Ruta destino en Auth |
| --- | --- |
| `POST /api/refresh` | `POST /api/auth/refresh` |
| `POST /api/logout` | `POST /api/auth/logout` |
| `/oauth2/authorization/**` | Misma ruta |
| `/login/oauth2/code/**` | Misma ruta |
| `GET /api/auth/me` | Misma ruta |
| `PUT /api/auth/me/display-name` | Misma ruta |

Gateway valida JWT para sus rutas privadas y Auth vuelve a validarlo. La sesión
HTTP de OAuth debe acompañar el navegador en inicio y callback. No se modifica
Gateway, forwarding, cookies, discovery ni la URI configurada para Google. La
prueba local de Auth no equivale a una ejecución de la cadena completa de
Gateway/Eureka/Google/Godot.
