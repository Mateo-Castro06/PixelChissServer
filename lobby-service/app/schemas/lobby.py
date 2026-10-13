from typing import Optional

from pydantic import BaseModel, Field

from app.models.enums import LobbyStatus, PlayerRole


class PlayerResponse(BaseModel):
    user_id: str = Field(description="Id del usuario (claim sub del JWT)", examples=["42"])
    display_name: str = Field(description="Nombre que se muestra en la sala", examples=["Ana"])
    role: PlayerRole = Field(description="HOST es el anfitrión actual de la sala")
    ready: bool = Field(description="True si el jugador marcó que está listo. Los bots siempre están listos", examples=[False])
    is_bot: bool = Field(description="True si es un bot añadido por el anfitrión", examples=[False])
    voted_map_id: Optional[str] = Field(default=None, description="Id del mapa por el que votó. Es null si aún no votó", examples=["classic"])


class LobbyResponse(BaseModel):
    code: str = Field(description="Código de 6 caracteres para compartir la sala", examples=["K7M2QX"])
    host_id: str = Field(description="Id del anfitrión actual", examples=["42"])
    creator_id: str = Field(description="Id del creador original. No cambia nunca", examples=["42"])
    status: LobbyStatus = Field(description="WAITING mientras se espera; STARTED cuando la partida ya inició")
    version: int = Field(description="Sube con cada cambio. Sirve para saber si la sala cambió al consultarla cada cierto tiempo", examples=[3])
    max_players: int = Field(description="Cupo máximo de la sala, contando bots", examples=[4])
    map_id: Optional[str] = Field(default=None, description="Mapa definitivo. Es null hasta que el anfitrión inicia la partida", examples=["classic"])
    map_votes: dict[str, int] = Field(description="Votos que lleva cada mapa (incluye los que tienen 0)", examples=[{"classic": 2, "science": 1}])
    players: list[PlayerResponse] = Field(description="Participantes, en el orden en que entraron")


class MapResponse(BaseModel):
    id: str = Field(description="Id del mapa. Es el mismo que usa el Game", examples=["classic"])
    name: str = Field(description="Nombre para mostrar", examples=["Clásico"])
    theme: str = Field(description="Temática de las preguntas de trivia", examples=["El juego"])
    description: str = Field(description="Texto de la vista previa del mapa", examples=["Preguntas sobre el propio juego."])


class VoteRequest(BaseModel):
    map_id: str = Field(description="Id del mapa por el que se vota (ver GET /api/lobbies/maps)", examples=["classic"])


class ReadyRequest(BaseModel):
    ready: bool = Field(description="True para marcarse como listo, False para quitar la marca", examples=[True])


class ErrorResponse(BaseModel):
    detail: str = Field(description="Mensaje de error para mostrar al jugador", examples=["Lobby no encontrado"])