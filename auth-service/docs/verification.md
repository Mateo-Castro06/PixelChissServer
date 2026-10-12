# Verificación de la refactorización

## Entorno y reproducción

Se conserva Maven Wrapper 3.9.16, Spring Boot 4.1.1, Kotlin 2.3.21 y Java 21.
Las pruebas se ejecutaron con OpenJDK 21.0.12.1 y PostgreSQL 17.11, desde
`auth-service/`. No se actualiza el POM ni se agregan dependencias de test.

Las pruebas completas requieren PostgreSQL dedicado. Se usó un contenedor
temporal `pixelchiss-auth-refactor-tests`, con puerto loopback 55432, sin volúmenes
ni reutilizar la base o contenedores de PixelChiss:

```bash
docker run --rm -d --name pixelchiss-auth-refactor-tests \
  -p 127.0.0.1:55432:5432 \
  -e POSTGRES_DB=auth_refactor_tests \
  -e POSTGRES_USER=auth_test \
  -e POSTGRES_PASSWORD=auth_test postgres:17
docker exec pixelchiss-auth-refactor-tests pg_isready -U auth_test -d auth_refactor_tests
./mvnw test
./mvnw verify
docker stop pixelchiss-auth-refactor-tests
```

Esas credenciales son exclusivas de pruebas. Si se utiliza otro PostgreSQL,
[`AuthIntegrationTest`](../src/test/kotlin/com/pixelchiss/auth/support/AuthIntegrationTest.kt)
admite `AUTH_TEST_DATABASE_URL`, `AUTH_TEST_DATABASE_USER` y
`AUTH_TEST_DATABASE_PASSWORD`. Deben apuntar a una base exclusiva de pruebas:
Hibernate utiliza la estrategia `update` existente y las pruebas escriben datos.
Los casos de integración ejecutan sus operaciones dentro de transacciones que
se revierten al finalizar cada prueba.

El soporte de test carga **la aplicación real**, el mismo `SecurityConfig`,
controladores, servicios y repositorios. Sólo sustituye propiedades de entorno:
base dedicada, credenciales Google ficticias, claves RSA efímeras y discovery
desactivado. No hay acceso a Google, Eureka ni a claves privadas de producción.
Los tests HTTP usan MockMvc y la cadena real de Spring Security; los servicios
OAuth/refresh tienen además pruebas unitarias con mocks para verificar errores
y orden de llamadas. Esos mocks no se usan como prueba de persistencia real.

## Resultados registrados

| Ejecución | Pruebas | Fallos | Errores | Omitidas | Resultado |
| --- | ---: | ---: | ---: | ---: | --- |
| Baseline original, `./mvnw test` con permiso | 1 | 0 | 1 | 0 | BUILD FAILURE; paquete del test no encuentra configuración Boot |
| Primera caracterización ejecutable, aún sin cambios de producción | 70 | 1 | 2 | 0 | Expectativa PKCE incorrecta y fixture de sesión incorrecto; corregidos en tests |
| Caracterización corregida | 70 | 0 | 0 | 0 | BUILD SUCCESS |
| Caracterización completa previa a producción, `./mvnw test` | 75 | 0 | 0 | 0 | BUILD SUCCESS |
| Bloque 1, `-Dtest=AuthHttpIntegrationTest,AuthPersistenceIntegrationTest,AuthServiceApplicationTests test` | 34 | 0 | 0 | 0 | BUILD SUCCESS |
| Bloque 2, `-Dtest=OAuth2LoginSuccessHandlerTest,ClientLoginParamsFilterTest,AuthHttpIntegrationTest test` | 62 | 0 | 0 | 0 | BUILD SUCCESS |
| Bloque 3, `-Dtest=JwtServiceTest,RefreshTokenServiceTest,AuthHttpIntegrationTest test` | 39 | 0 | 0 | 0 | BUILD SUCCESS |
| Final, `./mvnw verify` | 75 | 0 | 0 | 0 | BUILD SUCCESS; compilación, suite completa y JAR ejecutable |

La ejecución final duró 23.625 segundos y generó
`target/auth-service-0.0.1-SNAPSHOT.jar`. El contenedor temporal fue detenido y
eliminado automáticamente mediante `docker stop pixelchiss-auth-refactor-tests`
después de las pruebas. No se desplegó ni se reinició el Auth existente.

Antes del baseline con permiso hubo un bloqueo del sandbox en la caché Kotlin,
sin ejecución de pruebas. Durante la preparación de la caracterización hubo
dos errores de compilación de tests: primero 7 diagnósticos de nullability/smart
casts, después 2 diagnósticos de nullability en el constructor del intercambio
OAuth. Se corrigieron exclusivamente los tests antes de modificar producción.
No se cambió producción para hacer pasar una expectativa ni se deshabilitaron
pruebas. Los comandos de Maven requirieron permiso para escribir en su caché
local; no requirieron cambiar el proyecto.

## Cobertura

| Clase de prueba | Casos | Qué protege |
| --- | ---: | --- |
| [`ClientLoginParamsFilterTest`](../src/test/kotlin/com/pixelchiss/auth/security/ClientLoginParamsFilterTest.kt) | 22 | Límites y formato de state/port, rechazo 400 exacto, creación/preservación de sesión y bypass de callback |
| [`OAuth2LoginSuccessHandlerTest`](../src/test/kotlin/com/pixelchiss/auth/security/OAuth2LoginSuccessHandlerTest.kt) | 10 | Usuario nuevo/existente, atributos requeridos, sesión ausente/incompleta/tipos incorrectos, orden de emisión, codificación y redirección, invalidación |
| [`JwtServiceTest`](../src/test/kotlin/com/pixelchiss/auth/security/JwtServiceTest.kt) | 2 | Firma verificable, algoritmo con clave de prueba, claims exactos, vida de 900 segundos, carga lazy y converter sin authorities |
| [`RefreshTokenServiceTest`](../src/test/kotlin/com/pixelchiss/auth/service/RefreshTokenServiceTest.kt) | 7 | UUID, asociación a usuario, vida de 30 días, inexistencia/revocación/expiración, ausencia de rotación y revocación idempotente |
| [`AuthHttpIntegrationTest`](../src/test/kotlin/com/pixelchiss/auth/controller/AuthHttpIntegrationTest.kt) | 30 | JSON real, estados/razones/precedencia, refresh reutilizable, logout, perfiles/nombres, JWT ausente/malformado/expirado/firma ajena, OpenAPI, state OAuth, PKCE S256/verifier y lecturas PostgreSQL |
| [`AuthPersistenceIntegrationTest`](../src/test/kotlin/com/pixelchiss/auth/repository/AuthPersistenceIntegrationTest.kt) | 3 | Restricciones reales de unicidad de googleId, email y refresh token |
| [`AuthServiceApplicationTests`](../src/test/kotlin/com/pixelchiss/authservice/AuthServiceApplicationTests.kt) | 1 | Prueba original `contextLoads`, con aplicación y entorno explícitos |
| Total | 75 | Sin pruebas eliminadas u omitidas |

Los reportes JUnit se generan en `target/surefire-reports/`. Los logs de esta
sesión están en `/tmp/pixelchiss-auth-{baseline,characterization,block1,block2,block3,final}.log`;
son artefactos temporales del entorno, no archivos de producción.

## Inventario de cambios

Todos los paths siguientes son relativos a `auth-service/`.

| Estado | Archivo |
| --- | --- |
| Modificado | `src/main/kotlin/com/pixelchiss/auth/controller/AuthController.kt` |
| Creado | `src/main/kotlin/com/pixelchiss/auth/service/AuthService.kt` |
| Modificado | `src/main/kotlin/com/pixelchiss/auth/service/UserService.kt` |
| Modificado | `src/main/kotlin/com/pixelchiss/auth/service/RefreshTokenService.kt` |
| Modificado | `src/main/kotlin/com/pixelchiss/auth/security/OAuth2LoginSuccessHandler.kt` |
| Modificado | `src/main/kotlin/com/pixelchiss/auth/security/JwtService.kt` |
| Modificado | `src/test/kotlin/com/pixelchiss/authservice/AuthServiceApplicationTests.kt` |
| Creado | `src/test/kotlin/com/pixelchiss/auth/support/AuthIntegrationTest.kt` |
| Creado | `src/test/kotlin/com/pixelchiss/auth/controller/AuthHttpIntegrationTest.kt` |
| Creado | `src/test/kotlin/com/pixelchiss/auth/repository/AuthPersistenceIntegrationTest.kt` |
| Creado | `src/test/kotlin/com/pixelchiss/auth/security/ClientLoginParamsFilterTest.kt` |
| Creado | `src/test/kotlin/com/pixelchiss/auth/security/JwtServiceTest.kt` |
| Creado | `src/test/kotlin/com/pixelchiss/auth/security/OAuth2LoginSuccessHandlerTest.kt` |
| Creado | `src/test/kotlin/com/pixelchiss/auth/service/RefreshTokenServiceTest.kt` |
| Creado | `docs/README.md` |
| Creado | `docs/architecture.md` |
| Creado | `docs/authentication.md` |
| Creado | `docs/refactoring.md` |
| Creado | `docs/verification.md` |

Al cerrar la refactorización eran 6 archivos modificados y 13 nuevos. Git mostraba además como
modificado `src/main/kotlin/com/pixelchiss/auth/AuthServiceApplication.kt` por
su cambio **preexistente**, que no fue editado durante la refactorización.

## Revisión de alcance y estado de Git al cerrar la refactorización

Este registro describe el estado previo a la autorización posterior para versionar.

- Rama final: `main`. No se ejecutaron commits, push, pull, checkout ni reset.
- Cambios propios sin staging: 6 archivos modificados y 13 archivos nuevos,
  exclusivamente dentro de Auth. El diff staged sigue vacío.
- Se compararon SHA-256 de todos los archivos versionados externos a Auth con
  el registro inicial: **ninguno cambió**. No se modificaron Gateway, Game,
  Lobby, Eureka, infraestructura ni otros componentes.
- POM, YAML, DTOs, entidades, repositorios, configuración y componentes de
  seguridad intactos se compararon byte a byte con HEAD: **17 archivos iguales**.
- Las cinco anotaciones de rutas del `AuthController` (prefijo y cuatro métodos)
  coinciden con el original; `TestController` permanece intacto.
- SHA-256 y diff de `AuthServiceApplication.kt` coinciden con los iniciales.
- `git diff --check` sólo señala su línea en blanco preexistente al final.
  Excluyendo ese archivo, la comprobación termina sin advertencias.

## Límites

- Se prueba Auth con Spring Security y PostgreSQL reales. MockMvc no ejecuta
  una conexión HTTP al Gateway ni el listener local de Godot.
- No se repite el login real con Google/Godot ni se valida el intercambio contra
  Google. Se comprueban redirección de autorización, correlación de state,
  challenge/verifier y preparación del request de token, además del handler.
- Las claves y credenciales son de prueba; no se verifica operativamente que
  las claves desplegadas, DNS, variables, cookies del navegador o registro de
  redirect URI de Google estén configurados correctamente.
- Los tests verifican status y razón resuelta por MVC. No sustituyen un recorrido
  real del contenedor servlet por `/error` para comparar el JSON de errores de
  Boot. Su configuración y mecanismo de excepciones no se modifican.
- La igualdad exacta de `expiresAt` con `Instant.now()` no puede fijarse con el
  reloj real. Se prueban expirados/vigentes y se conserva explícitamente
  `isBefore` y el cortocircuito para tokens revocados.
- No se prueban carreras de creación de cuentas, carga ni fallos de red de
  servicios externos. Los riesgos existentes figuran en [refactoring.md](refactoring.md).

La conclusión es conservación de los contratos caracterizados y de la
configuración de seguridad. **No se afirma que la integración real con Godot
siga funcionando sólo por compilar o por pasar estas pruebas.** Requiere una
nueva ejecución con navegador, Google, Gateway/Eureka y cliente Godot.
