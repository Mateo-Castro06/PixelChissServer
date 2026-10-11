import py_eureka_client.eureka_client as eureka_client

from app.core import config

async def register():
    await eureka_client.init_async(
        eureka_server=config.EUREKA_URL,
        app_name=config.SERVICE_NAME,
        instance_host=config.SERVICE_HOST,
        instance_port=config.SERVICE_PORT,
    )

async def deregister():
    await eureka_client.stop_async()
