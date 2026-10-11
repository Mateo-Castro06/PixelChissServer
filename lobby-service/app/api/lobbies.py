import redis.asyncio as redis
from fastapi import APIRouter, Depends

from app.clients.auth_client import AuthClient
from app.core.redis import get_redis
from app.core.security import CurrentUser, get_current_user
from app.repositories.lobby_repository import LobbyRepository
from app.schemas.lobby import ErrorResponse, LobbyResponse
from app.services.lobby_service import LobbyService

router = APIRouter(prefix="/api/lobbies", tags=["Salas"])

UNAUTHORIZED = {401: {"model": ErrorResponse, "description": "Token ausente, inválido o expirado"}}
NOT_FOUND = {404: {"model": ErrorResponse, "description": "El código de sala es incorrecto"}}


def get_auth_client():
    return AuthClient()


def get_lobby_service(
    r: redis.Redis = Depends(get_redis),
    auth_client: AuthClient = Depends(get_auth_client),
):
    return LobbyService(LobbyRepository(r), auth_client)


@router.post(
    "",
    response_model=LobbyResponse,
    status_code=201,
    summary="Crear sala",
    description="**US-003 esc. 1.** Genera un código único de 6 caracteres y deja a quien la crea "
                "como anfitrión. La respuesta incluye el código para compartirlo.",
    responses={**UNAUTHORIZED},
)
async def create_lobby(
    user: CurrentUser = Depends(get_current_user),
    service: LobbyService = Depends(get_lobby_service),
):
    return await service.create_lobby(user)


@router.get(
    "/{code}",
    response_model=LobbyResponse,
    summary="Consultar sala",
    description="**US-003 esc. 2 y US-016.** Devuelve el código, el estado y los jugadores de la sala. "
                "Solo pueden consultarla quienes están dentro. El campo `version` sube con cada cambio.",
    responses={
        **UNAUTHORIZED,
        **NOT_FOUND,
        403: {"model": ErrorResponse, "description": "No perteneces a esta sala"},
    },
)
async def get_lobby(
    code: str,
    user: CurrentUser = Depends(get_current_user),
    service: LobbyService = Depends(get_lobby_service),
):
    return await service.get_lobby(code, user.id)


@router.post(
    "/{code}/join",
    response_model=LobbyResponse,
    summary="Unirse a una sala",
    description="**US-004.** Entra a la sala con su código. Si ya estabas dentro, devuelve la sala sin cambios.",
    responses={
        **UNAUTHORIZED,
        **NOT_FOUND,
        409: {"model": ErrorResponse, "description": "La sala está llena (máximo 4) o la partida ya inició"},
    },
)
async def join_lobby(
    code: str,
    user: CurrentUser = Depends(get_current_user),
    service: LobbyService = Depends(get_lobby_service),
):
    return await service.join_lobby(code, user)