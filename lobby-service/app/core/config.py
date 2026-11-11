import os

from app.domain.capacity import MIN_PLAYERS, MAX_PLAYERS

REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379/0")
EUREKA_URL = os.getenv("EUREKA_URL", "http://localhost:8761/eureka/")

JWT_PUBLIC_KEY_PATH = os.getenv("JWT_PUBLIC_KEY_PATH")

SERVICE_NAME = "LOBBY-SERVICE"
SERVICE_HOST = os.getenv("SERVICE_HOST", "localhost")
SERVICE_PORT = int(os.getenv("SERVICE_PORT", 8083))

AUTH_SERVICE_NAME = "AUTH-SERVICE"

LOBBY_TIMEOUT = 2*60*60

GAME_SERVICE_NAME = "GAME-SERVICE"
GAME_LOBBY_TOKEN = os.getenv("GAME_LOBBY_TOKEN", "")
GAME_TIMEOUT = 10
