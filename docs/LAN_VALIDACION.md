# Evidencia de la entrega LAN

Fecha: 19 de noviembre de 2026. Baseline del motor: `5464aa22c62ce0a816612d0939ec778528f2aaf8`. Esta evidencia corresponde a la implementación LAN entregada sobre esa baseline. La continuación tras la interrupción revisó documentación, Git y estado de contenedores; **no repitió las 376 pruebas ni el desarrollo LAN**. Los resultados se recuperaron de los informes XML y logs existentes, no se dedujeron del código.

Los datos agregados, resultados de probes, hashes SHA256 de los logs y digests de imágenes están en [lan-validation.json](lan-validation.json). Los logs completos quedaron en `/tmp/pixelchiss-lan-*.log` en el equipo de verificación; no se publican claves, tokens, `.env`, certificados privados ni datos personales.

## Suites ejecutadas antes de la interrupción

| Suite | Pruebas | Fallos / errores / omitidas en la ejecución final |
|---|---:|---|
| Auth, PostgreSQL aislado y validación Google simulada | 87 | 0 / 0 / 0 |
| Gateway | 13 | 0 / 0 / 0 |
| Game, Redis/Mongo/HTTP/WebSocket reales | 257 | 0 / 0 / 0 |
| Eureka | 1 | 0 / 0 / 0 |
| Lobby, Redis Streams y SAGA | 13 | 0 / 0 / 0 |
| Herramienta LAN | 5 | 0 / 0 / 0 |
| **Total** | **376** | **0 / 0 / 0** |

No se eliminaron regresiones del motor. `game-service/src/main`, `lobby-service/app` y `eureka-server/src/main` no tienen diferencias con la baseline. La imagen Game conserva el digest de la validación anterior. Auth agrega pruebas de PKCE, consumo único persistido, canje Desktop, handoff Web y validación criptográfica de ID tokens firmados de prueba; **el proveedor Google se simula en estas pruebas**.

## Integración ejecutada

| Probe por Gateway | HTTP/WS loopback | HTTPS/WSS en IP privada no loopback |
|---|---:|---:|
| Pares, HOME, coronación, trivia/pausa y reinicio | 8 resultados | 8 resultados |
| Dos identidades, sala compartida, acciones y reconexión | 2 resultados | 2 resultados |
| Bots y vencimientos reales de animación/movimiento/trivia, reinicio y SOPLAR | 6 resultados | 6 resultados |
| **Total** | **16** | **16** |

Se ejercitaron ambos mapas. Los dos clientes usaron JWT distintos, REST y WebSocket por el mismo Gateway; las reconexiones conservaron piezas/contadores/plazos. Los probes se ejecutaron en un entorno Docker aislado con persistencia separada de desarrollo. La dirección privada pertenece al mismo equipo que ejecutó los probes: **no acredita dos computadores físicos ni una partida Windows↔Linux**.

El probe adicional de transporte confirmó CA/SAN válidos, rechazo de CA ajena y nombre incorrecto, ausencia de fallback HTTP en el puerto TLS, HTTP local conservado, misma identidad por ambos conectores, WSS concurrentes, rechazo de JWT inválido/query/origin, inicio Desktop S256, callback loopback del jugador y rechazo de verifier incorrecto antes de contactar a Google. El conector LAN rechaza el login Web local y no publica métricas. Se verificó que Gateway monta únicamente server.p12 de solo lectura, sin la clave CA privada. Se generó además otro certificado de servidor, con clave propia, firmado por la misma CA del equipo; la confianza pública del cliente se conservó.

## Docker, datos y coordinación

- Desarrollo conserva el proyecto Compose `docker`: diez servicios en ejecución; los siete que tienen healthcheck están saludables. Eureka registra API-GATEWAY, AUTH-SERVICE, GAME-SERVICE y LOBBY-SERVICE. Prometheus/Grafana permanecen ejecutándose.
- Desarrollo quedó en **modo local**, sin activar LAN ni inventar credenciales Google Desktop. Su inicio Desktop devuelve 503 hasta que el administrador lo configure; no se presenta como login de usuario final validado. La adaptación TLS se comprobó en el entorno aislado, que al finalizar se detuvo conservando sus volúmenes.
- Las publicaciones de desarrollo se comprobaron accesibles por loopback y rechazadas por la IP privada desde el propio anfitrión; no se comprobó ese aislamiento desde un segundo computador físico. El override LAN reutiliza exactamente los servicios/volúmenes y publica solo TLS fuera de loopback.
- Los grupos Redis Streams de coordinación terminaron con pendientes 0 y lag 0; las pruebas Lobby/SAGA y el inicio de partidas por Gateway terminaron correctamente.
- `.env` mantiene exactamente el hash anterior. La comprobación agregada de datos conserva 1 usuario, 18 refresh tokens, 32 preguntas y 6 cartas; había 0 estados Game de desarrollo antes y después. La única adición de esquema es login_attempts. No se borraron partidas/volúmenes ni se alteraron otros proyectos Docker. `demo-rum/` permanece fuera de los commits.

## Incidencias resueltas y alcance

Durante la preparación hubo errores de compilación de Kotlin/genéricos Java, corregidos antes de las ejecuciones finales. El primer filtro TLS utilizaba sendError y Spring transformaba el resultado en 401; se corrigió para devolver directamente el JSON/estado previsto y los probes finales comprobaron 400/404. Tras recrear las bases por sus nuevos bindings, la conexión Redis de Game conservó una dirección antigua; reiniciar Game recuperó la conexión y su estado sin modificar código del motor. La actualización documenta ese reinicio.

Un primer probe de inicio encontró indisponibilidad transitoria de descubrimiento y una sala que SAGA ya había iniciado. Una ejecución posterior con sala nueva pasó conservando la exigencia de respuesta exitosa. Otra aserción histórica de reconexión exigía que cada jugador tuviera una ficha BOARD incluso después de capturas legales: se sustituyó por comprobar la igualdad de **todas** las piezas antes/después de reconectar. Esta comprobación pasó por HTTP y HTTPS. No se ocultaron fallos funcionales ni se cambiaron reglas para satisfacer el probe.

**No ejecutado:** Godot real, autorización Google con cuentas reales, anfitrión Windows real, dos computadores físicos, aplicación de reglas al firewall del equipo y comprobación de bloqueo desde fuera de la subred. Estas verificaciones manuales siguen siendo responsabilidad del equipo antes de declarar su instalación LAN validada. No hay un fallo crítico reproducible pendiente en las verificaciones ejecutadas; las limitaciones anteriores no se cuentan como pruebas aprobadas.

## Configuración pendiente para cada equipo

Actualizar el backend local requiere reconstruir Auth/Gateway y recrear el Compose existente, conservando `.env`, claves y volúmenes. Activar LAN agrega tareas manuales obligatorias: Google Desktop real/consentimiento, certificados/SAN y CA verificada, firewall acotado, arranque con ambos env-files/Compose y cliente Godot actualizado. El callback Web anterior cambia a código de un uso incluso en local; un cliente que esperaba tokens en la URL requiere adaptación.

Procedimientos completos y checklist: [actualización](GUIA_ACTUALIZACION.md), [anfitrión Windows/Linux](MANUAL_SERVIDOR_LAN.md) e [implementación Godot](GODOT_INTEGRACION_LAN.md).

Commits funcionales: `6594b05` (Auth Desktop/handoff) y `8e737d0` (Gateway/infraestructura LAN). El commit de documentación agrega esta evidencia y las guías, sin modificar código ejecutable ni repetir suites.
