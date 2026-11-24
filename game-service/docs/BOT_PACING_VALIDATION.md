# Validación del ritmo de bots

Validación realizada el 23 de noviembre de 2026. La rama `main` coincidía con
`origin/main` antes de editar (`c39ccc6af091770a033034b081a37322c7febaad`).
`demo-rum/` se conservó sin incluirlo en el cambio.

## Resultado

El runner comprobaba y ejecutaba hasta una acción cada 500 ms, mientras Godot
necesita aproximadamente 1900 ms para dados y 1100 ms por desplazamiento.
Ahora el polling de 500 ms es independiente de una reserva visual por partida:
2100 ms dados; 1300 ms movimiento/coronación; 1500 ms trivia; 500 ms cierre;
800 ms otras acciones. Los eventos de liberación, captura, SOPLAR y movimiento
por carta amplían la reserva cuando hay varios efectos. Por ejemplo: dados con
dos liberaciones = 4700 ms, cuatro = 7300 ms, captura y movimiento = 2600 ms.

El hash Redis guarda `botReadyAt` fuera del agregado. El CAS comprueba y guarda
la reserva junto con el estado y la publicación. Redis proporciona el reloj;
reiniciar Game y tener varias instancias no elimina ni ignora la reserva.
Los recibos e identificadores existentes mantienen la idempotencia. El cambio
no modifica el motor, política de decisiones, temporizadores o snapshot público.
MongoDB sigue siendo el catálogo, no un segundo almacén del estado de partidas.

El cambio de turno puede seguir formando parte del último movimiento. No se
retiene artificialmente ese resultado. El humano conserva 60 segundos de
preparación de tirada, 10 segundos máximos para confirmar los dados y los
30 segundos de movimiento desde su confirmación. Godot debe confirmar después
de reproducir la cola previa; confirmar al recibir el estado no acredita una
animación terminada. No hay ACK obligatorio de espectadores. Una cola excepcional
que exceda el plazo de confirmación puede producir timeout; no se promete
sincronización perfecta ni se amplían los tiempos reglamentarios.

## Pruebas

Comando final, desde `game-service/` (bases separadas del entorno LAN):

```sh
GAME_TEST_REDIS_URL=redis://127.0.0.1:16379/0 \
GAME_TEST_MONGO_URI=mongodb://127.0.0.1:17017/pixelchiss_bot_pacing \
GAME_TEST_GATEWAY_JAR="$PWD/../api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar" \
mvn -o verify -q -Dgame.integration=true
```

Resultado: **359 pruebas, 0 fallos, 0 errores, 0 omitidas**. Incluye 26 casos de
`BotPacingTest` y 30 de `InfrastructureIntegrationTest`. Se comprobaron límites
exactos con reloj controlado, dados→movimientos, movimiento/coronación→cambio de
turno, trivia→continuación, cartas, efectos múltiples, partidas independientes,
dos/tres bots, tres pares, victoria, turnos sin movimientos, duplicados, versiones
obsoletas, carrera de schedulers, fallo de persistencia y limpieza al finalizar.
Las pruebas existentes de captura/SOPLAR, liberación parcial, restricciones de
captura, geometría de ambos mapas y temporizadores siguen pasando.

La integración utiliza Redis 8 y MongoDB 7 reales en el proyecto separado
`pixelchiss-bot-pacing-test`, persistencia recuperada por otro proceso Game,
recibos tras recuperación y Gateway→Game por WebSocket en ambos mapas.
La prueba existente de bot en Redis conserva todas sus aserciones de reglas;
adelanta solo el metadato de su namespace de prueba para evitar esperas largas.

Medición del script Redis: **86 ms** de separación de commits/publicaciones para
una configuración de **80 ms** (una ejecución anterior midió 82 ms). Es una
prueba corta del intervalo configurable, no una medición de renderizado. Los
presupuestos predeterminados se verifican con reloj controlado: una acción a
2099 ms tras dados se rechaza y a 2100 ms se admite; los movimientos simples
reservan otros 1300 ms. El polling añade normalmente hasta 500 ms, además de
carga y tiempo de procesamiento. No se afirma haber medido esos intervalos de
producción en una sesión Godot.

## Despliegue y LAN

Se inspeccionó Compose y se detectó el overlay LAN existente. Redis informó
**0 partidas activas** antes de actualizar. Solo se reconstruyó y recreó
`game-service` mediante los archivos Compose y archivos de entorno existentes,
con `--no-deps --no-build` al levantarlo. No se recrearon Gateway, Auth, Lobby,
Eureka ni bases de datos, ni se modificó `lan.py`.

Se compararon huellas de archivos y configuración antes/después: variables de
Compose, certificados, `.env`, `lan.env`, montajes y puertos preservados. La
imagen reconstruida tiene otro valor predeterminado de `JAVA_VERSION`; no cambió
ninguna variable definida por el proyecto. Redis tenía **42 claves antes y después**,
con cero partidas activas. No se eliminaron datos LAN ni volúmenes.

Game, Gateway, Auth y Eureka respondieron `UP`; Game figura `UP` en Eureka.
Lobby respondió correctamente a su prueba Redis; Prometheus y Grafana respondieron
correctamente. Redis, MongoDB y PostgreSQL permanecieron en sus contenedores
existentes y saludables.

Se verificaron HTTPS en `https://192.168.1.11:8443` con CA y hostname válidos,
certificado público idéntico mediante descubrimiento automático de CA y WSS
Gateway→Game con rechazo `INVALID_TOKEN` para una credencial de prueba.
Las acciones autenticadas Gateway→Game se probaron en la integración aislada;
no se crearon partidas LAN para probar el despliegue. La prueba de transporte LAN
fue desde el mismo anfitrión hacia su dirección privada, no desde otro equipo físico.

## Validación pendiente del cliente

No se ejecutó Godot ni se editó `presenter.gd`. No se ha probado visualmente que
no haya saltos o cola creciente. Seguir el procedimiento de tres bots y un humano
en ambos mapas documentado en [WEBSOCKET.md](WEBSOCKET.md#comprobación-reproducible-en-godot).
Mantener `LAG_BUDGET` como recuperación y revisar `MOVE_WAIT` solo si las mediciones
reales lo justifican. No es obligatorio cambiar Godot si ya confirma los dados
después de representarlos y procesar los eventos anteriores.
