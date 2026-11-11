# Integración de Game

La creación recuperable y la mensajería interna se describen en [SAGA y EDA](../../docs/second-cut/start-saga.md).

## Lobby

Lobby autoriza y reserva el inicio, luego invoca `POST /internal/games` con su plan persistido:

```json
{
  "gameId":"game-123",
  "lobbyId":"room-123",
  "mapId":"classic",
  "creatorPlayerId":"57",
  "participants":[
    {"playerId":"bot-room-123-1","bot":true,"seat":3},
    {"playerId":"42","bot":false,"seat":0},
    {"playerId":"57","bot":false,"seat":2}
  ]
}
```

Header `X-Lobby-Token` debe coincidir con `GAME_LOBBY_TOKEN`, mínimo 32 caracteres, compartido mediante configuración segura. Sin configuración responde503; credencial incorrecta403. Es autenticación interna de servicio, no login de usuario. No exponer `/internal/games` al Gateway público; restringir a red interna y usar TLS si atraviesa redes.

Se admiten 2–4 participantes únicos. Humanos usan exactamente `sub` de Auth; bots usan IDs distintos, preferentemente `bot-`. Mapas iniciales: `classic`, `science`; nuevos mapas necesitan banco MongoDB.

**El participante indicado en `creatorPlayerId` empieza.** Lobby conserva el creador original y lo envía si sigue en la sala; si salió, envía al anfitrión actual. Game verifica que esté entre los participantes. No hay sorteo ni sustitución por el primer elemento del array. Falta/vacío: HTTP400 `MISSING_CREATOR`; ID ajeno: HTTP400 `INVALID_CREATOR`. Game confía en la identificación de Lobby por la credencial interna; no lee el estado privado de Lobby. Godot no puede enviar una acción para cambiar el creador o el turno inicial.

**Asientos explícitos e inmutables:** `seat` entero único de0 a3; rojo0, azul1, verde2, amarillo3. Esta secuencia representa las posiciones físicas en sentido horario `0→1→2→3→0`. Pueden existir huecos; no se compactan asientos al retirar un participante. El array puede llegar en cualquier orden: Game ordena por `seat`, asigna color y fichas al asiento y rota al siguiente asiento activo. En el ejemplo: `57(seat2) → bot(seat3) → 42(seat0) → 57`. El creador puede ocupar cualquier asiento. Un asiento ausente/duplicado/fuera de rango provoca HTTP400; Bean Validation puede devolver `INVALID_REQUEST`, el motor devuelve `INVALID_SEATS`.

`firstPlayerId` permanece como campo **opcional y deprecado** para consumidores que lo envíen junto con los campos nuevos; debe coincidir con `creatorPlayerId` o se rechaza con `INVALID_FIRST_PLAYER`. No sustituye al creador. Payloads antiguos con solo `firstPlayerId` o sin asientos deben actualizarse: no hay fallback silencioso. Es el único cambio necesario en el contrato de inicio; consultas REST y mensajes WebSocket mantienen su estructura.

Dobles repiten el asiento actual según las reglas existentes; al terminar el turno extra se sigue al próximo asiento horario. Retirados se omiten conservando sus asientos. Bots, timers y reconexiones usan la misma secuencia; conectar o sincronizar un socket no la reorganiza.

Reintentar exactamente el mismo payload devuelve estado existente. Otro payload con igual gameId responde409. Lobby entrega el gameId a sus participantes. Lobby administra incorporación/eliminación de bots antes de iniciar; Game ejecuta comportamiento, sin administración de anfitrión, votos ni mapas. Estados Redis anteriores sin `creatorPlayerId` siguen siendo legibles y conservan turno/asientos; no se inventa retrospectivamente su creador ni se reinician partidas activas.

Game registra `GameInitialized` al confirmar la creación. Lobby usa ese evento para completar la SAGA aunque se pierda la respuesta HTTP.

## Gateway HTTP y WebSocket

La integración ya está implementada en el Gateway WebMVC existente; no se migró de runtime ni se cambiaron las rutas de Auth. El informe y pruebas están en [GAME_INTEGRATION.md](../../api-gateway/docs/GAME_INTEGRATION.md).

| Pública en Gateway | Interna en Game |
|---|---|
| GET `/api/game/board` | `/api/game/board` |
| GET `/api/game/games/{gameId}` | `/api/game/games/{gameId}` |
| GET `/api/game/test/mongodb` | `/test/mongodb`, solo si diagnostics=true |
| GET `/api/game/test/redis` | `/test/redis`, solo si diagnostics=true |
| GET + Upgrade `/api/game/ws` | `/ws/game` |
| GET `/api/game/openapi` | `/v3/api-docs/public-game` |
| GET `/api/game/swagger-ui/index.html` | HTML del Gateway y assets `/swagger-ui/**` |

REST usa `lb://GAME-SERVICE`; `RewritePath` se usa exclusivamente cuando cambia el path interno. No se elimina el prefijo de consultas que ya empieza por `/api/game`. El inicio `POST /internal/games` permanece privado y no tiene ruta pública.

WebMVC5.0.3 no ofrece un handler proxy ws/wss en sus rutas YAML. `GameWebSocketProxy` añade un puente raw específico: `LoadBalancerClient.choose("GAME-SERVICE")`, conexión a la instancia elegida `/ws/game`, luego upgrade público. No usa un destino de puerto fijo ni altera el motor; conserva la instancia durante toda la sesión, mensajes bidireccionales y cierres.

SecurityConfig permite exclusivamente el handshake GET `/api/game/ws` sin Bearer; Game exige AUTH en el primer mensaje, firma/expiración/subject válidos y luego pertenencia/turno/propiedad. Las otras operaciones del juego siguen exigiendo JWT. Tokens en query strings se rechazan400. Origin se verifica en ambos tramos; coordinar `GATEWAY_GAME_ALLOWED_ORIGINS` y `GAME_ALLOWED_ORIGINS`. El Gateway mantiene pings/pongs propios para detectar clientes perdidos y liberar la conexión hacia Game.

Swagger público excluye `/internal/games`; contiene paths públicos y servidor `/` para evitar prefijos duplicados. Game conserva Swagger directo y documento completo para la integración privada. Las cinco operaciones de controlador se auditan con y sin diagnósticos. Ver [GATEWAY.md](GATEWAY.md).

Los probes usan instancias nuevas18080/18081/18084. Compilar no sustituye el contenedor Gateway existente8080: deberá arrancarse con el nuevo artefacto mediante su procedimiento habitual. Las pruebas de esta etapa usan un entorno aislado; consultar la evidencia del segundo corte.

## Auth, Eureka y despliegue

Se conserva clave RSA pública y subject de Auth, sin nuevo login. [JJWT elige algoritmo RSA según tamaño de clave](https://github.com/jwtk/jjwt); Game acepta RS256/384/512, exclusivamente con la clave configurada. Verifica firma, subject, expiración y nbf si existe; no exige issuer/audience porque Auth no los emite. No necesita modificar Auth.

Se mantiene aplicación `game-service`, cliente Eureka y `${EUREKA_URL}`. Puertos reales: Gateway 8080, Auth 8081, Lobby 8083, Game 8084, Eureka 8761. No se cambiaron a los orientativos del diagrama.

Compose declara Game en la red interna, sin publicar el puerto 8084. Monta la clave pública en solo lectura y proporciona MongoDB, Redis, Eureka y la credencial de Lobby. Ver [manual de instalación](../../docs/MANUAL_INSTALACION.md).

Si se necesita conservar cada última jugada tras reinicio Redis, verificar AOF/RDB externamente. No se agregó Log Aggregator, puerto5000, Player Service, logging adicional ni código Godot.

## Evidencia del cierre de validación

En el cierre previo se comprobó el registro UP en Eureka del JAR y de una imagen Docker nueva, sin
cambiar este servidor. Tras la refactorización también se observó UP del JAR nuevo y se probaron turnos horarios, bots y sockets directos; resultados en [CLEAN_CODE.md](CLEAN_CODE.md). También se reutilizó la clase compilada JwtService de Auth
con una clave aislada para producir tokens y consumir Game; no hubo login OAuth
real ni token de sesión disponible. No se modificó Auth ni se leyó su clave privada.

En el cierre anterior el Gateway desplegado respondió401 al handshake `/ws/game` sin Bearer; esa evidencia histórica no describe el nuevo endpoint público `/api/game/ws`. Lobby no
escucha en localhost8083. Los probes de inicio autorizado simulan al llamador Lobby
y los sockets de aquella validación se conectaron directamente a Game. La nueva integración sí prueba Gateway→Eureka→Game; el flujo completo con Lobby y Godot sigue pendiente. Ver [VALIDATION.md](VALIDATION.md) para comandos y resultados.

La comprobación anterior de `firstPlayerId` se conserva como evidencia histórica en
[VALIDATION.md](VALIDATION.md). La regla definitiva la sustituye: creador original
más asientos explícitos. La validación de esta corrección y la refactorización se
documenta en [CLEAN_CODE.md](CLEAN_CODE.md).
