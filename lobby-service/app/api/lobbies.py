import redis.asyncio as redis
from fastapi import APIRouter, Depends, Response

from app.clients.auth_client import AuthClient
from app.core.redis import get_redis
from app.core.security import CurrentUser, get_current_user
from app.repositories.lobby_repository import LobbyRepository
from app.models.game_map import MAPS
from app.schemas.lobby import (ErrorResponse, LobbyResponse, MapResponse, ReadyRequest,
                               VoteRequest)
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
    description="Genera un código único de 6 caracteres y deja a quien la crea "
                "como anfitrión. La respuesta incluye el código para compartirlo.",
    responses={**UNAUTHORIZED},
)
async def create_lobby(
    user: CurrentUser = Depends(get_current_user),
    service: LobbyService = Depends(get_lobby_service),
):
    return await service.create_lobby(user)

@router.get(
    "/maps",
    response_model=list[MapResponse],
    summary="Listar los mapas",
    description="Devuelve los mapas disponibles con su nombre, temática y descripción "
                "para mostrar la vista previa antes de votar. La imagen de la vista previa la pone el cliente, "
                "usando el id del mapa.",
    responses={**UNAUTHORIZED},
)

@router.get(
    "/{code}",
    response_model=LobbyResponse,
    summary="Consultar sala",
    description="Devuelve el código, el estado y los jugadores de la sala. "
                "Solo pueden consultarla quienes están dentro. El campo `version` sube con cada cambio.",
    responses={
        **UNAUTHORIZED,
        **NOT_FOUND,
        403: {"model": ErrorResponse, "description": "No perteneces a esta sala"},
    },
)


async def list_maps(user: CurrentUser = Depends(get_current_user)):
    return MAPS

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
    description="Entra a la sala con su código. Si ya estabas dentro, devuelve la sala sin cambios.",
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


@router.post(
    "/{code}/leave",
    status_code=204,
    summary="Salir de la sala",
    description="El jugador abandona la sala de espera. Si era el anfitrión, el rol pasa "
                "al jugador que entró primero; si era el último, la sala se borra. "
                "No responde contenido (204). Solo se puede salir mientras la sala está en espera.",
    responses={
        **UNAUTHORIZED,
        **NOT_FOUND,
        403: {"model": ErrorResponse, "description": "No perteneces a esta sala"},
        409: {"model": ErrorResponse, "description": "La partida ya inició"},
    },
)
async def leave_lobby(
    code: str,
    user: CurrentUser = Depends(get_current_user),
    service: LobbyService = Depends(get_lobby_service),
):
    await service.leave_lobby(code, user)
    return Response(status_code=204)


@router.put(
    "/{code}/ready",
    response_model=LobbyResponse,
    summary="Marcarse como listo",
    description="Marca (ready: true) o quita (ready: false) el estado de preparación "
                "del jugador. Todos los jugadores de la sala lo ven al consultarla. "
                "Si el estado ya era ese, no pasa nada y la version no cambia.",
    responses={
        **UNAUTHORIZED,
        **NOT_FOUND,
        403: {"model": ErrorResponse, "description": "No perteneces a esta sala"},
        409: {"model": ErrorResponse, "description": "La partida ya inició"},
    },
)
async def set_ready(
    code: str,
    body: ReadyRequest,
    user: CurrentUser = Depends(get_current_user),
    service: LobbyService = Depends(get_lobby_service),
):
    return await service.set_ready(code, user, body.ready)

@router.post(
    "/{code}/bots",
    response_model=LobbyResponse,
    status_code=201,
    summary="Añadir un bot",
    description="Solo el anfitrión. Agrega un bot a la sala. "
                "Ocupa un cupo (el máximo de 4 incluye a los bots). Se llama Bot N, su id es bot-CODIGO-N"
                "y siempre aparece como listo. Sus decisiones en la partida las toma el Game, sin elegir dificultad.",
    responses={
        **UNAUTHORIZED,
        **NOT_FOUND,
        403: {"model": ErrorResponse, "description": "No perteneces a la sala o no eres el anfitrión"},
        409: {"model": ErrorResponse, "description": "La sala alcanzó el máximo de 4 o la partida ya inició"},
    },
)
async def add_bot(
    code: str,
    user: CurrentUser = Depends(get_current_user),
    service: LobbyService = Depends(get_lobby_service),
):
    return await service.add_bot(code, user)


@router.delete(
    "/{code}/bots/{bot_id}",
    response_model=LobbyResponse,
    summary="Quitar un bot",
    description="Solo el anfitrión. Retira al bot de la sala y libera su cupo. "
                "Con este endpoint no se puede sacar a jugadores humanos.",
    responses={
        **UNAUTHORIZED,
        404: {"model": ErrorResponse, "description": "La sala o el bot no existen"},
        403: {"model": ErrorResponse, "description": "No perteneces a la sala o no eres el anfitrión"},
        409: {"model": ErrorResponse, "description": "La partida ya inició"},
    },
)
async def remove_bot(
    code: str,
    bot_id: str,
    user: CurrentUser = Depends(get_current_user),
    service: LobbyService = Depends(get_lobby_service),
):
    return await service.remove_bot(code, user, bot_id)

@router.put(
    "/{code}/vote",
    response_model=LobbyResponse,
    summary="Votar el mapa",
    description="**US-012 esc. 1.** Cualquier jugador de la sala vota por un mapa mientras está en espera. "
                "Puede cambiar su voto las veces que quiera; cuenta el último. Los bots no votan. "
                "El mapa ganador (el de más votos; si hay empate o nadie votó, uno al azar entre las opciones) "
                "se fija cuando el anfitrión inicia la partida.",
    responses={
        **UNAUTHORIZED,
        **NOT_FOUND,
        400: {"model": ErrorResponse, "description": "El mapa no existe"},
        403: {"model": ErrorResponse, "description": "No perteneces a esta sala"},
        409: {"model": ErrorResponse, "description": "La partida ya inició"},
    },
)
async def vote_map(
    code: str,
    body: VoteRequest,
    user: CurrentUser = Depends(get_current_user),
    service: LobbyService = Depends(get_lobby_service),
):
    return await service.vote_map(code, user, body.map_id)