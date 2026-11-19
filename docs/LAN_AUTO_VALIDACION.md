# Validación del inicio automático LAN

Baseline: `c93f82a6e2d44a9396c3bf55a9c40bda2b7cffd6`. Fecha: 19 de noviembre de 2026. Esta entrega amplía la herramienta LAN existente; no modifica código de Auth, Gateway, Lobby, Game o Eureka, contratos Godot ni reglas del motor. No hay migraciones de datos ni nuevas variables obligatorias en `.env`.

[Evidencia estructurada y hashes de logs/fuentes](lan-auto-validation.json).

## Ejecuciones finales

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
