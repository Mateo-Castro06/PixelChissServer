import os

import redis
import py_eureka_client.eureka_client as eureka_client
from fastapi import FastAPI

app = FastAPI()

REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379/0")
EUREKA_URL = os.getenv("EUREKA_URL", "http://localhost:8761/eureka/")

redis_client = redis.from_url(
    REDIS_URL,
    decode_responses=True
)


@app.on_event("startup")
async def register_with_eureka():
    await eureka_client.init_async(
        eureka_server=EUREKA_URL,
        app_name="LOBBY-SERVICE",
        instance_host="localhost",
        instance_port=8083,
    )


@app.get("/")
async def root():
    return {"message": "Hello World"}


@app.get("/hello/{name}")
async def say_hello(name: str):
    return {"message": f"Hello {name}"}


@app.get("/health/redis")
async def redis_health():
    try:
        redis_client.ping()
        return {
            "redis": "UP"
        }
    except redis.RedisError:
        return {
            "redis": "DOWN"
        }