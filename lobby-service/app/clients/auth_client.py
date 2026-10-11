import logging

import py_eureka_client.eureka_client as eureka_client

from app.core import config
from app.core.security import CurrentUser

logger = logging.getLogger(__name__)


class AuthClient:
    async def get_display_name(self, user: CurrentUser):
        fallback = f"Jugador {user.id}"
        try:
            profile = await eureka_client.do_service_async(
                app_name=config.AUTH_SERVICE_NAME,
                service="/api/auth/me",
                return_type="json",
                headers={"Authorization": f"Bearer {user.token}"},
            )
            return profile.get("displayName") or fallback
        except Exception:
            logger.warning("No se pudo obtener el perfil desde el Auth; se usa un nombre genérico", exc_info=True)
            return fallback