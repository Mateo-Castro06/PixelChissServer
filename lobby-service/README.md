# Lobby Service

Microservicio de salas de espera de PixelChiss. Permite crear salas, invitar
jugadores mediante un código, gestionar participantes y preparar el inicio de
una partida. Está implementado con Python y FastAPI; utiliza Redis para el
estado de las salas y se registra en Eureka como `LOBBY-SERVICE`.

## Tecnologías e integración

| Elemento | Uso |
| --- | --- |
| Python + FastAPI | API REST y documentación Swagger/OpenAPI |
| Redis | Estado temporal de las salas |
| Eureka | Registro y descubrimiento del microservicio |
| Auth Service | Validación de JWT y consulta del nombre visible del jugador |
| Game Service | Creación de la partida al iniciar una sala |

Las salas admiten hasta cuatro participantes, contando bots. Para iniciar una
partida deben quedar al menos dos participantes y todos deben estar listos.
Solo el anfitrión puede añadir o retirar bots, expulsar participantes e iniciar
la partida. Los mapas disponibles se consultan mediante la API.

## Swagger

Con Lobby ejecutándose en Docker, abre Swagger UI directamente en:

- Swagger UI: <http://localhost:8083/docs>
- OpenAPI JSON: <http://localhost:8083/openapi.json>
- Salud de Redis: <http://localhost:8083/health/redis>

La documentación Swagger se sirve desde Lobby; las operaciones de sala también
se enrutan por API Gateway en `http://localhost:8080/api/lobbies`.

Para probar operaciones protegidas:

1. Obtén un `accessToken` (JWT) iniciando sesión con Auth Service.
2. En Swagger, selecciona **Authorize** e introduce el JWT de usuario.
   No introduzcas `GAME_LOBBY_TOKEN`: es una credencial interna entre Lobby y
   Game, no sirve para autenticar usuarios.
3. Ejecuta `POST /api/lobbies` para crear una sala y guarda el código devuelto.
4. Usa ese código en las rutas `/{code}`. Puedes añadir un bot como anfitrión
   para completar los dos participantes mínimos; los bots siempre están listos.
5. Marca al anfitrión como listo con `PUT /api/lobbies/{code}/ready` y
   `{"ready": true}`. Cuando haya al menos dos participantes y todos estén
   listos, el anfitrión puede ejecutar `POST /api/lobbies/{code}/start`.

Los endpoints de la tabla, excepto `/health/redis`, requieren un JWT válido en
el encabezado `Authorization: Bearer <accessToken>`. Swagger utiliza el esquema
HTTP Bearer y agrega este encabezado al autorizarse.

## Tabla de Request Mapping

La siguiente tabla documenta los endpoints de Lobby según las columnas
solicitadas para el proyecto: funcionalidad, mapping, microservicio, método
HTTP, parámetros/tipos de datos y retorno.

| Funcionalidad | Request Mapping | Microservicio | Método HTTP | Parámetro / Tipo de dato | Retorno |
| --- | --- | --- | --- | --- | --- |
| Consultar estado de Redis | `/health/redis` | `LOBBY-SERVICE` | GET | Sin parámetros. No requiere JWT. | `200`: `{"redis":"UP"}` si Redis responde; `{"redis":"DOWN"}` si Redis no está disponible. |
| Crear sala | `/api/lobbies` | `LOBBY-SERVICE` | POST | Sin parámetros ni body. Requiere JWT. | `201`: `LobbyResponse` con código de sala de 6 caracteres, anfitrión y participantes. |
| Listar mapas | `/api/lobbies/maps` | `LOBBY-SERVICE` | GET | Sin parámetros ni body. Requiere JWT. | `200`: lista de `MapResponse`. |
| Consultar sala | `/api/lobbies/{code}` | `LOBBY-SERVICE` | GET | `code` (string, path). Requiere JWT y pertenecer a la sala. | `200`: `LobbyResponse`. |
| Unirse a sala | `/api/lobbies/{code}/join` | `LOBBY-SERVICE` | POST | `code` (string, path). Requiere JWT. | `200`: `LobbyResponse` actualizada. |
| Salir de sala | `/api/lobbies/{code}/leave` | `LOBBY-SERVICE` | POST | `code` (string, path). Requiere JWT y pertenecer a la sala. | `204`: sin body. |
| Cambiar estado de preparación | `/api/lobbies/{code}/ready` | `LOBBY-SERVICE` | PUT | `code` (string, path); `ReadyRequest` (JSON body: `{"ready": true}` o `{"ready": false}`). Requiere JWT. | `200`: `LobbyResponse` actualizada. |
| Añadir bot | `/api/lobbies/{code}/bots` | `LOBBY-SERVICE` | POST | `code` (string, path). Requiere JWT de anfitrión. | `201`: `LobbyResponse` actualizada. |
| Retirar bot | `/api/lobbies/{code}/bots/{bot_id}` | `LOBBY-SERVICE` | DELETE | `code` y `bot_id` (string, path). Requiere JWT de anfitrión. | `200`: `LobbyResponse` actualizada. |
| Votar por mapa | `/api/lobbies/{code}/vote` | `LOBBY-SERVICE` | PUT | `code` (string, path); `VoteRequest` (JSON body: `{"map_id": "classic"}`; mapa válido obtenido de `/api/lobbies/maps`). Requiere JWT y pertenecer a la sala. | `200`: `LobbyResponse` actualizada con los votos. |
| Expulsar jugador | `/api/lobbies/{code}/players/{player_id}` | `LOBBY-SERVICE` | DELETE | `code` y `player_id` (string, path). Requiere JWT de anfitrión. | `200`: `LobbyResponse` actualizada. |
| Iniciar partida | `/api/lobbies/{code}/start` | `LOBBY-SERVICE` | POST | `code` (string, path). Requiere JWT de anfitrión; mínimo dos participantes y todos listos. | `200`: `LobbyResponse` con estado iniciado, mapa elegido e identificador de partida. |

`LobbyResponse` contiene el código, anfitrión, estado, versión, cupos,
participantes, votos, mapa e identificador de partida cuando corresponda.
Las respuestas de error usan un objeto con `detail`; los códigos habituales
son `400` para una solicitud inválida, `401` para JWT ausente/inválido/expirado,
`403` para permisos o membresía insuficientes, `404` para sala/mapa/participante
no encontrado y `409` para conflictos como sala llena o partida ya iniciada.

## Ejecución con Docker Compose

Desde la raíz del repositorio, asegúrate de que
`infrastructure/docker/.env` tenga `GAME_LOBBY_TOKEN` configurado con al menos
32 caracteres. El mismo secreto debe estar configurado para Lobby y Game.
Conserva también las variables de entorno y claves existentes; no publiques el
archivo `.env`.

En Git Bash, ejecuta:

```bash
docker compose --env-file infrastructure/docker/.env -p docker -f infrastructure/docker/docker-compose.yml config --quiet
docker compose --env-file infrastructure/docker/.env -p docker -f infrastructure/docker/docker-compose.yml up -d --build eureka-server redis lobby-service
docker compose --env-file infrastructure/docker/.env -p docker -f infrastructure/docker/docker-compose.yml ps
```

Si Lobby no aparece como `healthy`, revisa sus logs:

```bash
docker compose --env-file infrastructure/docker/.env -p docker -f infrastructure/docker/docker-compose.yml logs lobby-service
```

El servicio publica el puerto `8083` en el host para Swagger. En Compose,
`EUREKA_URL`, `REDIS_URL`, `JWT_PUBLIC_KEY_PATH`, `SERVICE_HOST`,
`SERVICE_PORT` y `GAME_LOBBY_TOKEN` se configuran desde el entorno del
contenedor.

## Ejecución local de desarrollo

Con Python instalado y Eureka y Redis disponibles localmente, desde el
directorio `lobby-service`:

```bash
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements.txt
export EUREKA_URL=http://localhost:8761/eureka/
export REDIS_URL=redis://localhost:6379/1
export JWT_PUBLIC_KEY_PATH=../auth-service/keys/public_key.pem
export SERVICE_HOST=localhost
export SERVICE_PORT=8083
.venv/Scripts/uvicorn.exe main:app --reload --host 0.0.0.0 --port 8083
```

Los comandos usan los ejecutables del entorno virtual directamente y no
requieren activarlo. El endpoint Swagger queda en <http://localhost:8083/docs>.
