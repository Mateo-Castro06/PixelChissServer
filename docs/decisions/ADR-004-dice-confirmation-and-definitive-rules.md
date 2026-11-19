# ADR-004: reglas v4-100 y confirmación de dados

Estado: implementado; validado con clientes automatizados. Godot real pendiente.

Se conserva la arquitectura y el CAS Redis con publicación atómica. El layout pasa a v4-100 porque las salidas se desplazan cinco posiciones. Las rutas repiten su salida en progresos 0/68 y conservan finales 69–76. Los estados antiguos se preservan y rechazan; no se reinterpretan.

Cada tirada genera UUID y fechas absolutas. DICE_ANIMATION_COMPLETED usa challengeId=rollId y actor JWT externo al comando. La espera de confirmación es de 10 segundos; el reloj de movimiento empieza solo al confirmar y dura 30 segundos. Bots comienzan directamente. La espera anterior al lanzamiento conserva la protección de 60 segundos.

El dominio resuelve vencimiento, descarte de dados y SOPLAR. La aplicación coordina persistencia, recibos, scheduler y eventos; los adaptadores no implementan movimientos. Un commit CAS ganador contiene la transición completa; los perdedores deben recargar. El scheduler puede cerrar estados vencidos tras reinicio. Los movimientos recibidos vencidos se rechazan aun antes de que actúe el scheduler.

SOPLAR conserva candidatos y testigos de oportunidades ejercitables. Una captura satisface el turno; oportunidades eliminadas externamente no penalizan. Varias candidatas producen BLOW_AMBIGUOUS sin víctima arbitraria. Las liberaciones son automáticas al lanzar pares, consumiendo ambos dados exclusivamente, y capturan rivales de salida de forma atómica.

Los pares adicionales conservan identidad del turno, contadores y captura satisfecha. Se conserva el descuento de efectos por repetición propia. Cada nueva tirada requiere confirmación humana independiente. La selección por tres pares solo admite fichas activas antes de la liberación de esa tirada.

Los bots muestrean p por pregunta, construyen pesos con precisión double y responden por el mismo caso de uso de trivia. No se altera catálogo, JWT, rutas HTTP ni mensajes de Redis Streams/SAGA.

Véanse [contrato](../../game-service/docs/WEBSOCKET.md), [motor](../../game-service/docs/ENGINE.md) y [evidencia](../AUDITORIA_REGLAS_DEFINITIVAS.md).

Actualización de pares: tres pares conservan selección de corona de activa previa y cierran el turno sin cuarta tirada. El contador llega a tres y se reinicia al cerrar. Trivia dispone de 15 segundos propios: pausa el saldo persistido de movimiento por rollId y lo reanuda al resolver sin restaurar 30 segundos; CAS evita doble resolución. Nuevos eventos HOME_RELEASE_RESOLVED y MOVEMENT_TIMER_PAUSED/RESUMED, sin cambiar acciones existentes.
