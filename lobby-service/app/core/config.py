import os

REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379/0")
EUREKA_URL = os.getenv("EUREKA_URL", "http://localhost:8761/eureka/")

JWT_PUBLIC_KEY_PATH = os.getenv("JWT_PUBLIC_KEY_PATH")

SERVICE_NAME = "LOBBY-SERVICE"
SERVICE_HOST = "localhost"
SERVICE_PORT = 8083