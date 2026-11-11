from dataclasses import dataclass


@dataclass
class GameMap:
    id: str
    name: str
    theme: str
    description: str


MAPS = [
    GameMap(
        id="classic",
        name="Clásico",
        theme="Cultura general",
        description="Preguntas de geografía, historia, arte, literatura, cultura y deportes.",
    ),
    GameMap(
        id="science",
        name="Ciencia",
        theme="Ciencia",
        description="Preguntas de cultura científica: química, astronomía, cuerpo humano y geometría.",
    ),
]

MAP_IDS = [m.id for m in MAPS]