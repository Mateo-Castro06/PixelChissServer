from prometheus_client import Counter, Gauge, Histogram

HTTP = Histogram("pixelchiss_lobby_http_request_seconds", "Duración HTTP de Lobby",
                 ["route", "method", "status"])
START = Counter("pixelchiss_lobby_start_operations_total", "Transiciones y recuperaciones de inicio",
                ["operation", "outcome"])
EVENT = Counter("pixelchiss_lobby_integration_events_total", "Procesamiento de GameInitialized",
                ["outcome"])
PENDING = Gauge("pixelchiss_lobby_events_pending", "Mensajes entregados aún sin confirmar")
START_DUE = Gauge("pixelchiss_lobby_starts_unresolved", "Intentos indexados para recuperación")
