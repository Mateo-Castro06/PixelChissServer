## Revisión de reglas v4-100

Board, GameState, DiceRules, RuleEngine y TurnManager mantienen reglas en el dominio; GameService coordina commits CAS. El scheduler usa fechas absolutas persistidas y los adaptadores no deciden movimientos. La política probabilística se mantiene en BotPolicy/TriviaSelection, con DoubleSupplier inyectable. REST, JWT, Redis Streams y SAGA conservan sus rutas y esquemas. [Plazos y persistencia](../../game-service/docs/ENGINE.md), [API WS](../../game-service/docs/WEBSOCKET.md).

# Puertos y servicios de PixelChiss

| Componente | Puerto por defecto | Uso |
|---|---:|---|
| API Gateway | 8080 | Entrada HTTP y WebSocket del cliente |
| Auth | 8081 | OAuth, JWT, refresh y perfil; acceso directo de desarrollo |
| Lobby | 8083 | API de salas; acceso directo de desarrollo |
| Game | 8084 | REST y WebSocket, interno en Compose |
| Eureka | 8761 | Descubrimiento y registro |
| PostgreSQL | 5432 | Usuarios y refresh tokens |
| MongoDB | 27017 | Catálogo de cartas y preguntas |
| Redis | 6379 | Partidas, salas, Pub/Sub y Streams |
| Prometheus | 9090 | Métricas, loopback y perfil `monitoring` |
| Grafana | 3000 | Dashboard, loopback y perfil `monitoring` |

El Gateway descubre servicios en Eureka. Lobby llama a Auth y al endpoint privado de Game por Eureka. Game guarda las partidas y la presencia en Redis DB 0 y publica el evento de creación en un stream de esa base. Lobby conserva las salas y los intentos de inicio en DB 1, y consume la confirmación desde el stream de Game. MongoDB no guarda partidas ni entidades de mapa.

Ver [diagrama y capas](../second-cut/architecture.md), [contratos HTTP](../MANUAL_INSTALACION.md#operaciones-rest) y [EDA/SAGA](../second-cut/start-saga.md). Los puertos publicados de desarrollo no constituyen una configuración de producción.

Game utiliza la geometría `v4-100`, aislada en el dominio `Board`; el marcador persistido evita interpretar partidas antiguas con índices nuevos. HOME no ocupa posición física. No se modifican los puertos ni los contratos EDA/SAGA: véase [ADR-003](../decisions/ADR-003-board-layout-100.md).

Las transiciones de liberación exclusiva por par, premio de coronación y pausa/reanudación del saldo de movimiento pertenecen a RuleEngine/TriviaRules. GameService conserva recibos y CAS; RedisGameRepository persiste el agregado y publica el lote atómicamente. Los campos de pausa y marcadores de liberación se agregan a Turn; no hay nuevo servicio ni cambios al protocolo de SAGA/Streams.
