import redis.asyncio as redis

from app.core import config

redis_client = redis.from_url(config.REDIS_URL, decode_responses=True)

def get_redis() -> redis.Redis:
    return redis_client