# Validación del inicio automático LAN

## Descubrimiento de CA por HTTPS (23 de noviembre de 2026)

Esta revisión parte de `fd086fc` y añade únicamente el recurso de descubrimiento del Gateway, su montaje público de CA y la integración con herramientas LAN. No modifica Auth, Lobby, Game, Eureka, datos, OAuth ni firewall. Los resultados de noviembre 19/21 que siguen son históricos; no acreditan el entorno actual. [Evidencia de esta revisión](lan-ca-validation.json).

| Suite de esta revisión | Total | Aprobadas | Fallos | Errores | Omitidas |
|---|---:|---:|---:|---:|---:|
| LAN, archivos temporales y mocks | 113 | 111 | 0 | 0 | 2 |
| Gateway, incluido HTTPS/WSS real con JDK y CA desechable | 20 | 20 | 0 | 0 | 0 |
| Game, sin habilitar integración externa | 321 | 301 | 0 | 0 | 20 |
| Auth, subconjunto unitario | 47 | 47 | 0 | 0 | 0 |
| Lobby | 13 | 6 | 0 | 0 | 7 |

**Intento adicional que no pasó por infraestructura ausente:** `mvn -o verify -q` de Auth ejecutó 87 casos: 47 aprobados y 40 errores de contexto porque PostgreSQL de pruebas en 127.0.0.1:55432 rechazó conexiones. No se presenta como regresión aprobada ni se cuentan esos 47 dos veces. Se repitió únicamente el subconjunto unitario con éxito. No se inició otra instalación de bases ni se tocaron volúmenes. Las primeras ejecuciones de Gateway detectaron errores de implementación/fixture, que se corrigieron antes de la ejecución final aprobada.

Comandos finales ejecutados desde la raíz o el directorio indicado:

```sh
python3 -m unittest discover -s infrastructure/lan -v
# api-gateway/ (fuera del sandbox por bloqueo de sockets locales):
mvn -o -Dmaven.repo.local=.build/m2 verify -q
# game-service/:
mvn -o -Dmaven.repo.local=.build/m2 test -q
# auth-service/ (fuera del sandbox por caché Kotlin):
mvn -o -Dtest=RefreshTokenServiceTest,OAuth2LoginSuccessHandlerTest,GoogleDesktopIdentityTest,JwtServiceTest,ClientLoginParamsFilterTest test -q
# lobby-service/ (fuera del sandbox; ejecución inicial bloqueada y cancelada):
timeout 90 .venv/bin/python -m unittest discover -s tests -v
```

Gateway crea claves exclusivas de prueba con el keytool local y levanta conectores en puertos efímeros. Comprueba GET sin credenciales, PEM parseable, SHA-256 DER/caId, conjunto exacto de campos públicos, headers no-store/nosniff, CA ausente/inválida/hoja/tamaño excesivo y errores 503 saneados. Rechaza métodos de escritura, cuerpos, parámetros y sufijos/recorridos para claves, PKCS12 y env. Tras comparar contra su fuente local independiente prueba HTTPS estricto; CA desconocida/ajena e identidad fuera del SAN fallan. PKIX rechaza hoja vencida; el recurso rechaza CA vencida. La renovación simulada de hoja cambia SAN manteniendo idénticos DER/caId de CA. WSS real con CA verificada transmite un frame por el proxy hacia un eco de prueba y rechaza CA desconocida. **Ese eco no ejecuta reglas ni OAuth de Game.** Se conservan pruebas de rutas Auth/Desktop, JWT, HTTP local sin recurso LAN, rechazo de login Web sobre TLS y ocultación de métricas públicas.

La suite LAN comprueba que el montaje solo admite server.p12 y ca.crt de lectura; HTTP 8080 y servicios internos no pueden publicarse en LAN. Los fixtures conservan proyecto Compose, idempotencia, reglas ajenas, perfiles Windows, subred/IP, renovación conservando CA y mantenimiento/recuperación. Se simula manipulación de respuesta y huellas: la comprobación de preparación usa TLS estricto y contrasta la descarga con ca.crt local. Se prueba la construcción única del Gateway para adoptar el recurso, sin repetirla en arranques sin cambios. No se implementa una decisión de confianza de usuario dentro del backend: corresponde a Godot.

También pasó la resolución real, de solo lectura, de los dos Compose con los env-files existentes (`config --format json`, salida capturada sin imprimir secretos), seguida de `validate_config`: una única publicación TLS privada, HTTP/servicios internos en loopback y los dos montajes TLS de solo lectura. Esto no necesita daemon ni valida tráfico de contenedores en ejecución. Se comprobó además con OpenSSL que el PEM del ejemplo documental es parseable y con Python que su SHA-256 DER coincide con ambos campos del JSON.

**Límites actuales:** no hay daemon Docker (`docker ps` elevado informa socket inexistente). No se ejecutó Compose/start --auto real, ni se cambiaron servicios, firewall, certificados/configuración de instalación, claves JWT, Google, bases o demo-rum. Las dos omisiones LAN requieren PowerShell; Windows real/UAC/Defender no se comprobaron. Game omite 20 casos de integración sin bases/servidores aislados, y Lobby omite siete que requieren Redis/Game. No se ejecutaron tests TLS/firewall dentro de contenedores. Linux/JDK en este entorno sí ejecutó HTTPS/WSS, pero eso no acredita acceso desde otro computador, cambio físico de red, un ejecutable Godot o login Google real. Deben realizarse las pruebas cliente de [la guía de cambios](GODOT_LAN_CERTIFICADOS_CAMBIOS.md#i-pruebas-obligatorias-para-godot).

## Evidencia histórica de la automatización previa

Baseline: `c93f82a6e2d44a9396c3bf55a9c40bda2b7cffd6`. Fecha: 19 de noviembre de 2026. Esta entrega amplía la herramienta LAN existente; no modifica código de Auth, Gateway, Lobby, Game o Eureka, contratos Godot ni reglas del motor. No hay migraciones de datos ni nuevas variables obligatorias en `.env`.

[Evidencia estructurada y hashes de logs/fuentes](lan-auto-validation.json).

## Corrección de consulta Windows (21 de noviembre de 2026)

En `auto_firewall.py`, `windows_query()` generaba cinco llaves de apertura y cuatro de cierre. El fragmento `protocol=[string]$p.Protocol}};` cerraba el hashtable y el `if($r)`, pero dejaba abierto el `foreach`. Se corrigió a `protocol=[string]$p.Protocol}}};`, dejando la comprobación de perfiles y la serialización JSON fuera del bucle. El único cambio de código de ejecución es esa llave; no cambian reglas, autorizaciones, servicios ni datos.

Se añadieron doce pruebas a la suite LAN existente:

- Inspección de delimitadores y comillas de los scripts de consulta, creación en los tres perfiles, comprobación de administrador, elevación, consulta/retirada de reglas antiguas y reversión. También se decodifican e inspeccionan los payloads UTF-16LE, incluido el elevado. Las pruebas detectaron el fallo antes de aplicar el arreglo y ahora pasan; la variante original queda como caso negativo.
- Uso automático del parser real `System.Management.Automation.Language.Parser` si se encuentra `powershell.exe`, `pwsh` o `powershell`. Solo analiza los scripts, sin ejecutarlos. Otra prueba ejecuta la consulta con funciones PowerShell locales de prueba que sustituyen todos los cmdlets de firewall: verifica arrays JSON con cero, una y tres reglas y el rechazo de perfiles deshabilitados.
- CLI `firewall-check`: éxito, reglas ausentes/parciales/de ámbito incorrecto, JSON inválido, fallo de PowerShell y ejecutable ausente; comprueba códigos de salida y ausencia de escrituras/elevación. CLI `start --auto`: reutilización sin duplicados ni elevación, conservación de estado al repetir, restauración de configuración ante error, rechazo sin autorización y detención si se pierde la protección antes de publicar servicios. Las operaciones externas están sustituidas por mocks y los archivos son temporales.
- Rechazo de una regla ajena con nombre administrado y retirada Windows limitada al grupo de esta instalación, conservando los nombres actuales.

Comando ejecutado: `python3 -m unittest discover -s infrastructure/lan -v`. Resultado: **104 pruebas, 102 aprobadas, 2 omitidas, cero fallos/errores**. Las dos omitidas requieren PowerShell, que no está instalado en este entorno Linux. La inspección de delimitadores no sustituye al parser real ni acredita Windows Defender Firewall/UAC reales. No se modificó el firewall del anfitrión ni se ejecutó `start --auto` contra sus servicios. Las regresiones de servicios de la tabla histórica siguiente no se repitieron para este cambio limitado al generador y sus pruebas.

Para ejecutar las pruebas en Windows, sin aplicar reglas:

```powershell
py -3 -m unittest discover -s infrastructure/lan -p test_firewall_scripts.py -v
```

Con una instalación LAN ya configurada, `py -3 infrastructure/lan/lan.py firewall-check` consulta el estado real sin aplicar reglas. Debe comprobarse en un anfitrión Windows; las pruebas de fixtures no certifican ese entorno. `start --auto` conserva su autorización interactiva para cualquier modificación del firewall.

## Ejecuciones finales (19 de noviembre de 2026)

| Suite | Pruebas | Fallos / errores / omitidas |
|---|---:|---|
| Auth con PostgreSQL aislado | 87 | 0 / 0 / 0 |
| Gateway | 13 | 0 / 0 / 0 |
| Game con integración Redis/Mongo/HTTP/WebSocket habilitada | 257 | 0 / 0 / 0 |
| Eureka | 1 | 0 / 0 / 0 |
| Lobby con Redis Streams/SAGA aislados | 13 | 0 / 0 / 0 |
| Herramienta LAN, archivos temporales y mocks de operaciones | 92 | 0 / 0 / 0 |
| Ciclo TLS real con JDK/keytool y CA exclusiva de pruebas | 5 | 0 / 0 / 0 |
| Adaptadores iptables/nftables reales en contenedor sin red | 2 | 0 / 0 / 0 |
| **Total** | **470** | **0 / 0 / 0** |

Las 92 pruebas de herramienta incluyen las cinco anteriores y 87 nuevas. Comprueban selección de red ambigua/manual, descarte de interfaces virtuales, perfiles públicos, conservación de secretos, escritura atómica, permisos, recuperación y bloqueo concurrente; certificado reutilizado/renovado, CA propia/común, CSR pendiente y firma rechazada; autorización de firewall, no duplicación, retirada solo de reglas propias y reanudación Windows de una aplicación parcial; proyecto Compose existente, construcción/recreación mínima, puertos, fallo de arranque, partidas activas/desconocidas y otra comprobación de partidas después de construir imágenes. El flujo completo start --auto se prueba con operaciones peligrosas reemplazadas por mocks.

Las cinco pruebas TLS ejecutan realmente Docker/keytool y el validador Java sobre material de prueba: SAN correcto e incorrecto, clave/certificado incompatibles, renovación con la misma CA/clave/contraseña, CSR y firma de CA común, CA ajena rechazada y certificado vencido. No copian material privado de la CA de desarrollo.

Las dos pruebas de firewall ejecutan realmente ambos adaptadores en un contenedor desechable `--network none --cap-add NET_ADMIN`, sin publicar puertos y montando únicamente el código de herramientas en modo lectura. El propio test exige `/.dockerenv` y que la única interfaz sea loopback; aborta si ese aislamiento no se verifica. Comprueban sintaxis/verificación, repetición sin duplicados y cambio de subred manteniendo reglas ajenas. **No modifican el firewall del anfitrión y no equivalen a una prueba física del tráfico LAN.** Windows Defender Firewall y elevación UAC se verifican mediante mocks, no en Windows real.

## Integración por Gateway

| Probe, ambos mapas y dos identidades | HTTP/WS local aislado | HTTPS/WSS en IP privada del mismo anfitrión |
|---|---:|---:|
| Pares, HOME, coronación, trivia/pausa, reconexión y reinicio | 8 resultados | 8 resultados |
| Sala compartida, movimientos y reconexión multijugador | 2 resultados | 2 resultados |
| Bots, vencimientos de animación/movimiento/trivia, SOPLAR y reinicio | 6 resultados | 6 resultados |
| **Total** | **16** | **16** |

Además pasó el probe de transporte: CA/SAN, rechazo de certificado ajeno y nombre incorrecto, TLS sin fallback HTTP, REST/JWT, conexiones WSS concurrentes, rechazo de query/origin, inicio OAuth Desktop PKCE y métricas no expuestas en LAN. No hubo consentimiento Google real.

Se mantuvieron las aserciones de los probes existentes. Durante la preparación se corrigieron problemas del entorno aislado: contraseña TLS tomada de otro archivo de prueba, selección de refresh desde la base unitaria en vez de auth_runtime y dependencias Python de probes ausentes. En la fase LAN una respuesta de trivia devolvió INVALID_CHALLENGE y un inicio de sala devolvió 409. Se conservaron esos logs y se repitieron solamente las fases fallidas con partidas/salas nuevas, sin modificar el motor ni relajar aserciones; las ejecuciones finales de ambos mapas pasaron. Estos fallos intermitentes no se presentan como aprobados en sus intentos originales.

La revisión XML detectó también que el primer comando Maven de Game omitía las 14 pruebas condicionadas por game.integration. Se ejecutó de nuevo con `-Dgame.integration=true`: la ejecución final contabiliza 257, sin omisiones.

## Hallazgos corregidos en la herramienta

- El PKCS12 existente ya contiene el alias de la CA pública. Renovar elimina ese alias únicamente en la copia privada temporal antes de importar la misma CA; conserva la clave de servidor y la CA de producción.
- nftables normaliza el JSON de una tabla IPv4 omitiendo el campo redundante de familia de la dirección. La generación/verificación usa su representación canónica. La prueba real de namespace pasó tras corregirlo.
- La última consulta de partidas/TLS/firewall ocurre después de construir imágenes, para no ocultar partidas iniciadas durante una compilación larga. No se introduce un bloqueo de creación de partidas en el dominio; el anfitrión debe anunciar mantenimiento antes de autorizar cambios.

## Desarrollo y conservación

El proyecto `docker` quedó en LAN, con diez servicios ejecutándose y sus siete healthchecks saludables. Los cuatro servicios están registrados UP en Eureka; los cinco objetivos Prometheus están disponibles. Se comprobó HTTP local, HTTPS con la CA y WS/WSS con rechazo de JWT inválidos sin entrar ni actuar en partidas reales. El plan de Compose detecta cero recreaciones y cero imágenes necesarias en la instalación actual.

Los hashes de `.env` y `lan.env` se conservaron. Los volúmenes de desarrollo coinciden con los anteriores; los recuentos agregados mantienen 1 usuario, 18 refresh tokens, 32 preguntas, 6 cartas y 0 estados Game de desarrollo. Redis Streams termina sin pendientes ni lag y SAGA sin inicios pendientes. No se ejecutó start --auto completo contra el firewall real durante estas pruebas: la planificación de Compose y la conectividad de desarrollo se verificaron en lectura. El entorno de integración se detuvo al finalizar conservando sus volúmenes. No se alteró el proyecto ajeno sprintdev.

## Reproducción de las pruebas nuevas

Herramienta, sin Docker ni firewall real:

```sh
python3 -m unittest discover -s infrastructure/lan -v
```

TLS real, con Docker/JDK disponible; todo el material es nuevo y temporal:

```sh
PIXELCHISS_LAN_TLS_TEST=1 python3 infrastructure/lan/integration_tls.py
```

Windows PowerShell, para el mismo test TLS:

```powershell
$env:PIXELCHISS_LAN_TLS_TEST = '1'
py -3 infrastructure/lan/integration_tls.py
Remove-Item Env:PIXELCHISS_LAN_TLS_TEST
```

Linux, pruebas de firewall **solo en namespace aislado**, con la imagen Lobby existente:

```bash
docker build -f infrastructure/lan/firewall-test.Dockerfile -t pixelchiss-lan-firewall-test:local infrastructure/lan
docker run --rm --network none --cap-add NET_ADMIN --env PIXELCHISS_FIREWALL_NAMESPACE_TEST=1 --mount "type=bind,source=$(pwd)/infrastructure/lan,target=/tools,readonly" --workdir /tools --entrypoint python pixelchiss-lan-firewall-test:local integration_firewall.py
```

No sustituir --network none por host ni ejecutar estos tests con el namespace del anfitrión. La regresión JVM usa los comandos Maven existentes contra PostgreSQL 55432, Redis 16379 y Mongo 17017 aislados; Game exige explícitamente game.integration=true. Los logs completos de esta revisión están en `/tmp/pixelchiss-auto-*.log`, y sus hashes están en el JSON enlazado.

## Límites y verificaciones pendientes

No se ejecutaron: anfitrión Windows físico, cambio real de Wi-Fi, Godot real, dos computadores físicos ni login Google con cuentas reales. Los cambios de IP/subred de la herramienta se simularon con fixtures; los tests de firewall Linux se ejecutaron solo en el namespace aislado. El uso real de sudo/UAC y el tráfico desde la subred permitida/fuera de ella deben verificarse por el equipo. Una CA común sigue necesitando firma administrativa cuando cambia el SAN; nunca se sustituye por una CA individual para omitir esa aprobación.

Procedimiento de uso y recuperación: [manual](MANUAL_SERVIDOR_LAN.md#inicio-automático-y-cambios-de-wi-fi), [actualización](GUIA_ACTUALIZACION.md) y [Godot](GODOT_INTEGRACION_LAN.md).

Commit funcional: `fb41189` (asistente, adaptadores y pruebas). El commit de documentación incorpora esta evidencia y las guías sin cambiar el código verificado.
