from contextlib import asynccontextmanager

import redis.asyncio as redis
from fastapi import Depends, FastAPI

from app.core import eureka
from app.core.exceptions import LobbyError, lobby_error_handler
from app.core.redis import get_redis
from app.core.security import CurrentUser, get_current_user


@asynccontextmanager
async def lifespan(app: FastAPI):
    await eureka.register()
    yield
    await eureka.deregister()


app = FastAPI(title="PixelChiss Lobby Service", lifespan=lifespan)
app.add_exception_handler(LobbyError, lobby_error_handler)


@app.get("/health/redis")
async def redis_health(r: redis.Redis = Depends(get_redis)):
    try:
        await r.ping()
        return {"redis": "UP"}
    except redis.RedisError:
        return {"redis": "DOWN"}


@app.get("/me")
async def me(user: CurrentUser = Depends(get_current_user)):
    """Temporal: sirve para comprobar que el JWT del Auth se valida bien."""
    return {"id": user.id, "email": user.email}