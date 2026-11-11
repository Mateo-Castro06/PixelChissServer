# ADR-002: Redis Streams y SAGA orquestada para iniciar partidas

**Estado: aceptada.**

Lobby y Game confirman estado local separado. Un timeout del POST no distingue rechazo de creación confirmada. Redis Pub/Sub no recupera notificaciones perdidas.

| Alternativa | Evaluación |
|---|---|
| Redis Streams | Ya está Redis 8; grupos, pendientes, retención y confirmación. El registro del evento cabe en el mismo script que crea Game. |
| RabbitMQ | Confirms y acknowledgements adecuados; otro servicio y outbox para unir la escritura en Redis con el broker. |
| Kafka | Registro particionado, reproducción y grupos; capacidad operativa innecesaria para un evento de bajo volumen y doble escritura que exige outbox. |

Elegimos Streams para un contrato mínimo y Lobby como orquestador: mantiene autorización y respuesta HTTP, hace visibles estados y compensaciones y centraliza la recuperación. Una coreografía completa con comandos asíncronos complica la espera síncrona y la identificación de la autoridad. No se usa CQRS distribuido porque la lectura autoritativa de Game debe estar al día y no hay otra carga de consultas demostrada. No se usa Event Sourcing ni transacción distribuida: snapshots, CAS y una SAGA acotada cubren el caso.

AOF `everysec` y política `noeviction` no proporcionan alta disponibilidad ni pérdida cero. Se requiere dimensionar memoria y respaldos en producción. Referencias: [Redis Streams](https://redis.io/docs/latest/develop/data-types/streams/), [confirms de RabbitMQ](https://www.rabbitmq.com/docs/confirms), [documentación de Kafka](https://kafka.apache.org/documentation/), [persistencia Redis](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/). Ver [contrato y estados](../second-cut/start-saga.md).
