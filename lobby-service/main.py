import asyncio
import hmac
import os
from time import perf_counter
from contextlib import asynccontextmanager

import redis.asyncio as redis
from fastapi import Depends, FastAPI, HTTPException, Request, Response
from fastapi.responses import JSONResponse
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from app.api import lobbies
from app.core import eureka
from app.core.exceptions import LobbyError
from app.core.redis import get_redis
from app.events import workers
from app.observability.metrics import HTTP


@asynccontextmanager
async def lifespan(app: FastAPI):
    await eureka.register()
    tasks = workers.launch()
    try:
        yield
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        await eureka.deregister()


app = FastAPI(title="PixelChiss Lobby Service",
    description="Salas de espera de PixelChiss: crear, unirse, preparar la partida e iniciarla. ",
    version="0.1.0",
    lifespan=lifespan,
)
async def lobby_error_handler(request: Request, exc: LobbyError):
    return JSONResponse(status_code=exc.status_code, content={"detail": exc.detail})


app.add_exception_handler(LobbyError, lobby_error_handler)
app.include_router(lobbies.router)


@app.get("/health/redis", tags=["Estado"], summary="Estado de la conexión con Redis")
async def redis_health(r: redis.Redis = Depends(get_redis)):
    try:
        await r.ping()
        return {"redis": "UP"}
    except redis.RedisError:
        return {"redis": "DOWN"}


@app.middleware("http")
async def record_http(request: Request, call_next):
    begin = perf_counter()
    status = 500
    try:
        response = await call_next(request)
        status = response.status_code
        return response
    finally:
        if request.url.path != "/internal/metrics":
            route = getattr(request.scope.get("route"), "path", "unmatched")
            method = request.method if request.method in {"GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD"} else "OTHER"
            HTTP.labels(route, method, str(status)).observe(perf_counter() - begin)


@app.get("/internal/metrics", include_in_schema=False)
async def metrics(request: Request):
    key = os.getenv("PIXELCHISS_METRICS_KEY", "")
    supplied = request.headers.get("x-metrics-key", "")
    if len(key) < 32 or not hmac.compare_digest(key.encode(), supplied.encode()):
        raise HTTPException(403, "Acceso denegado")
    return Response(generate_latest(), headers={"Content-Type": CONTENT_TYPE_LATEST})
