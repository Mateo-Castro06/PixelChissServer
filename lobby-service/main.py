from contextlib import asynccontextmanager

import redis.asyncio as redis
from fastapi import Depends, FastAPI

from app.api import lobbies
from app.core import eureka
from app.core.exceptions import LobbyError, lobby_error_handler
from app.core.redis import get_redis


@asynccontextmanager
async def lifespan(app: FastAPI):
    await eureka.register()
    yield
    await eureka.deregister()


app = FastAPI(title="PixelChiss Lobby Service",
    description="Salas de espera de PixelChiss: crear, unirse, preparar la partida e iniciarla. ",
    version="0.1.0",
    lifespan=lifespan,
)
app.add_exception_handler(LobbyError, lobby_error_handler)
app.include_router(lobbies.router)


@app.get("/health/redis", tags=["Estado"], summary="Estado de la conexión con Redis")
async def redis_health(r: redis.Redis = Depends(get_redis)):
    try:
        await r.ping()
        return {"redis": "UP"}
    except redis.RedisError:
        return {"redis": "DOWN"}
