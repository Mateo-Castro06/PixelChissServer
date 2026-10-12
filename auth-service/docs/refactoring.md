# Refactorización de Auth

## Alcance y punto de partida

Se conserva el comportamiento observable de Auth. No se cambian versiones,
dependencias, entidades, repositorios, DTOs, `application.yaml`, `SecurityConfig`
ni la configuración del Gateway. Durante la refactorización no se realizaron
operaciones de escritura en Git; el versionado se autorizó posteriormente.

Antes de modificar código se ejecutó `git status --short`: sólo existía una
modificación en `AuthServiceApplication.kt`, una línea en blanco al final. Se
registraron el diff inicial y su SHA-256 para comprobar que se preserva.

La primera ejecución de `./mvnw test` quedó bloqueada al escribir la caché del
daemon Kotlin fuera del sandbox. Repetida con permiso, compiló producción y
ejecutó **1 prueba: 0 fallos, 1 error, 0 omitidas**. El error fue
`Unable to find a @SpringBootConfiguration`: el paquete del test era
`com.pixelchiss.authservice`, distinto de `com.pixelchiss.auth`.

## Problemas y plan por bloques

1. **Cobertura previa:** mantener `contextLoads`, indicar explícitamente la
   aplicación y proporcionar configuración de prueba aislada. Añadir pruebas
   de caracterización antes de tocar producción: JSON, HTTP, errores y su
   precedencia, filtro de parámetros, callback, JWT, refresh/logout y JPA real.
2. **Controlador:** mezcla transporte, consultas JPA, validación de nombres,
   emisión de tokens y mapeo de perfiles duplicado. Delegar los casos de uso a
   servicios concretos, conservando las excepciones Spring y su orden.
3. **Callback:** extraer pasos privados con nombres claros dentro del mismo
   handler. Mantener la creación de usuario/tokens antes de validar la sesión,
   la invalidación antes de codificar la URL y el orden de parámetros.
4. **Tokens:** nombrar las duraciones existentes y simplificar guardas de
   refresh sin cambiar el instante de consulta ni `isBefore`.
5. **Verificación y documentación:** ejecutar los bloques y después compilar,
   empaquetar y ejecutar toda la suite; revisar diff, archivos externos y el
   cambio preexistente. Documentar responsabilidades y límites reales.

## Criterio SOLID

- **SRP:** el controlador adapta HTTP; los servicios coordinan tokens y usuarios;
  los repositorios siguen ocupándose de persistencia. El handler continúa
  siendo el adaptador del éxito OAuth y del callback local.
- **DIP:** el controlador deja de depender directamente de JPA. Los servicios
  reciben sus colaboradores por constructor; Spring Data ya proporciona las
  abstracciones de persistencia. No se añade una interfaz por clase.
- **OCP:** se reducen puntos duplicados de validación/mapeo que exigirían cambios
  repetidos. No existe una necesidad concreta de varios proveedores o políticas
  y no se añade una jerarquía para anticiparlos.
- **LSP:** no se introduce herencia de producción. Se preservan los contratos de
  `AuthenticationSuccessHandler` y `Converter<Jwt, AbstractAuthenticationToken>`.
- **ISP:** se mantienen las interfaces pequeñas propias de Spring Data y los
  contratos de Spring Security. No se introduce una interfaz general de Auth.

Los servicios usan `ResponseStatusException` deliberadamente: es el mecanismo
existente. Sustituirlo por una nueva jerarquía de errores y un advice implicaría
una superficie de cambio innecesaria para esta tarea. Tampoco se añaden
transacciones nuevas: conservar las llamadas a repositorios conserva los límites
transaccionales existentes.

## Cambios realizados

| Problema concreto | Refactorización | Por qué mejora el código |
| --- | --- | --- |
| Controlador mezclaba HTTP, repositorios y emisión JWT | `AuthService` coordina refresh/logout; `AuthController` sólo adapta el contrato | Aísla los casos de uso del transporte y reduce dependencias del controlador |
| Dos consultas de perfil y dos mapeos idénticos | `UserService.requireProfileUser` y `User.toProfileResponse` privados | Evita divergencias entre consulta y actualización del perfil |
| Parseo de subject duplicado | `AuthController.authenticatedUserId` privado | Mantiene un único punto para el error 401 existente |
| Validación de nombres y persistencia dentro del controlador | `UserService.updateDisplayName`, patrón precompilado | Agrupa la política existente con la operación sobre el usuario |
| Callback largo con pasos mezclados | `findOrCreateUser`, `createClientRedirect`, `encodeQueryValue` privados | Permite leer la secuencia de éxito sin crear componentes adicionales |
| Duraciones sin nombre y guardas repetidas | Constantes de 15 minutos/30 días y guardas con Elvis/cortocircuito | Hace explícita la intención manteniendo el cálculo y comparación originales |
| Único test no encontraba Boot y carecía de entorno aislado | Configuración Boot explícita heredada desde soporte de test | Conserva `contextLoads` y permite cargar realmente Auth con PostgreSQL de prueba |

Se creó una sola clase nueva de producción, `AuthService`. No se eliminaron
métodos públicos aparentemente poco usados como `UserService.findByEmail`,
porque no había una necesidad demostrada de eliminarlos. No se agregaron
dependencias, herencia ni interfaces de producción.

Los tres bloques pasaron respectivamente 34, 62 y 39 pruebas. La verificación
final compiló y empaquetó el servicio, ejecutando 75 pruebas sin fallos, errores
u omisiones. El detalle, inventario y límites están en
[verification.md](verification.md).

## Riesgos existentes que requieren una decisión aparte

- Auth no configura PKCE explícitamente. La caracterización confirmó que Spring
  Security 7.1.1 habilita `requireProofKey` por defecto y genera S256 incluso para
  este cliente confidencial. Es una dependencia de los valores predeterminados
  de la versión actual que conviene revisar en futuras actualizaciones. Los
  parámetros `client_state` y `client_port` pertenecen al callback Godot, no a PKCE.
- El callback entrega access y refresh tokens en la query de una URL HTTP
  loopback; podrían aparecer en historial o registros. Cambiar transporte
  requiere coordinar con Godot.
- Los refresh tokens se almacenan como UUID en texto, sin hashing ni rotación.
  Logout revoca sólo el refresh suministrado; no invalida JWT ya emitidos.
- El handler emite tokens antes de comprobar los atributos de sesión. Una sesión
  incompleta causa `IllegalArgumentException` después de esas operaciones.
- La identidad de usuarios existentes se obtiene por `googleId`; no se actualiza
  email/nombre desde Google y no se fusionan cuentas por email. Conflictos de
  unicidad concurrentes los resuelve PostgreSQL, sin traducción propia.
- No hay validadores propios de issuer/audience ni authorities añadidas. Las
  reglas y validación quedan en la configuración actual de Spring Security.
- `ddl-auto: update` pertenece a la configuración actual. Cambiar la estrategia
  de migraciones o almacenamiento está fuera del alcance.

Estos puntos se documentan; no se corrigen ni se presentan como capacidades que
Auth no implementa.
