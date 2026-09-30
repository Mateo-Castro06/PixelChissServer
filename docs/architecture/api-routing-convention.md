# Convención de Rutas de la API

## Objetivo

Definir una separación clara entre las rutas públicas expuestas al cliente Godot y las rutas internas utilizadas por el API Gateway para comunicarse con los microservicios.

## API pública

El cliente Godot se comunica únicamente con el API Gateway.

Las solicitudes del cliente utilizan la siguiente estructura:

```text
/api/{funcionalidad}