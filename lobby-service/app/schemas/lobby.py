from pydantic import BaseModel, Field

from app.models.enums import LobbyStatus, PlayerRole

class PlayerResponse(BaseModel):
    user_id: str = Field(description="Id del usuario (claim sub del JWT)", examples=["42"])
    display_name: str = Field(description="Nombre que se muestra en la sala", examples=["Ana"])
    role: PlayerRole = Field(description="HOST es el anfitrión actual de la sala")

class LobbyResponse(BaseModel):
    code: str = Field(description="Código de 6 caracteres para compartir la sala", examples=["K7M2QX"])
    host_id: str = Field(description="Id del anfitrión actual", examples=["42"])
    creator_id: str = Field(description="Id del creador original. No cambia nunca", examples=["42"])
    status: LobbyStatus = Field(description="WAITING mientras se espera; PLAYING cuando la partida ya inició")
    version: int = Field(description="Sube con cada cambio. Sirve para saber si la sala cambió al consultarla cada cierto tiempo", examples=[3])
    max_players: int = Field(description="Cupo máximo de la sala, contando bots", examples=[4])
    players: list[PlayerResponse] = Field(description="Participantes, en el orden en que entraron")

class ErrorResponse(BaseModel):
    detail: str = Field(description="Mensaje de error para mostrar al jugador", examples=["El código de sala es incorrecto"])