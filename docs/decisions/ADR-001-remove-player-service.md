# ADR-001: Eliminación de Player Service

## Estado

Aceptada

## Contexto

La arquitectura inicial de PixelChiss incluía un `Player Service` como microservicio independiente.

Durante la revisión de las historias de usuario y de las responsabilidades de cada dominio, se identificó que las funcionalidades actualmente definidas no asignan una responsabilidad propia y suficientemente diferenciada a este servicio.

Las funcionalidades relacionadas con la identidad y autenticación pertenecen al `Auth Service`, mientras que las funcionalidades principales del videojuego corresponden a otros servicios del sistema.

Mantener un microservicio sin una responsabilidad clara añadiría complejidad arquitectónica y operativa sin una necesidad funcional identificada.

## Decisión

Se elimina `Player Service` de la arquitectura de PixelChiss.

El concepto de usuario relacionado con autenticación permanecerá dentro de `Auth Service`.

Las responsabilidades relacionadas con las funcionalidades del videojuego se distribuirán entre los microservicios que correspondan según el dominio definido por las historias de usuario.

## Consecuencias

### Positivas

* Se reduce la cantidad de microservicios que deben mantenerse.
* Se evita crear responsabilidades artificiales para justificar la existencia de un servicio.
* Se reduce la complejidad de comunicación entre servicios.
* La arquitectura queda más alineada con las responsabilidades reales del dominio.

### Negativas

* Si en el futuro aparece una responsabilidad claramente diferenciada relacionada con jugadores, podría ser necesario crear nuevamente un servicio independiente.
* La distribución de responsabilidades deberá mantenerse documentada para evitar que otros servicios asuman funciones que no les corresponden.

## Nota

Esta decisión puede revisarse si las futuras historias de usuario o requisitos del proyecto justifican la existencia de un servicio independiente para gestionar información específica de los jugadores.
