# Ports and Services

## Application Services

| Component | Technology | Port | Communication |
|---|---|---:|---|
| API Gateway | Spring Cloud Gateway | 8080 | HTTP |
| Auth Service | Kotlin + Spring Boot | 8081 | REST |
| Lobby Service | Python + FastAPI | 8083 | REST |
| Game Service | Java + Spring Boot | 8084 | REST + WebSocket |
| Eureka Server | Spring Boot | 8761 | Service Discovery |

## Infrastructure

| Component | Technology | Port |
|---|---|---:|
| PostgreSQL | PostgreSQL | 5432 |
| MongoDB | MongoDB | 27017 |
| Redis | Redis | 6379 |

## Main Communication Flow

Godot communicates with the API Gateway.

The API Gateway routes requests to the appropriate microservice.

Eureka Server provides service discovery for the application services.

REST is used for Auth and Lobby operations.

WebSocket is used by the Game Service for real-time game communication.

## Databases

- Auth Service → PostgreSQL
- Lobby Service → Redis
- Game Service → Redis and/or MongoDB depending on the required game state