## Reglas vigentes v4-100

Las fichas salen en 5/22/39/56. Un par libera hasta cuatro (1,1), tres (6,6) o dos (otros pares), sin liberar coronadas. Si todas las no coronadas están HOME hay tres intentos fallidos. Se pueden apilar cuatro propias en normales/trivia; una captura exacta devuelve todas las rivales. Seguros normales admiten dos; salidas cuatro. Son protegidos salvo cartas explícitas y captura especial al liberar HOME en salida propia.

Una captura legal obliga a capturar al menos una vez en el turno. Omitirla permite SOPLAR sobre la ficha identificable; si hay varias candidatas no se elige al azar. Tres pares habilitan coronar una ficha previamente activa propia. La coronación normal es exacta.

Godot tiene hasta 10 segundos para confirmar la animación; después comienzan 30 segundos de movimiento. Al vencer se conservan jugadas aceptadas, se pierden dados pendientes y se evalúa SOPLAR antes de avanzar. No se mueven fichas automáticamente. Reconectar no renueva plazos. Los bots responden trivia probabilísticamente (acierto medio esperado 50 %). [Reglas y recorrido](../game-service/docs/BOARD.md), [protocolo](../game-service/docs/WEBSOCKET.md). Esta sección sustituye reglas contradictorias del texto histórico siguiente.

# Manual de usuario — PixelChiss

Esta guía describe las operaciones soportadas por el servidor. La interfaz y los nombres de botones del cliente dependen de la versión de Godot de tu equipo.

1. **Inicia sesión** desde el cliente. Completa la autorización en el navegador y regresa al cliente cuando termine el callback local. No compartas los enlaces de retorno ni sus tokens.
2. **Crea o únete a una sala.** Comparte el código con tus compañeros. La sala admite hasta cuatro participantes contando bots. Puedes salir mientras siga en espera; si sale el anfitrión, otro participante humano toma el rol.
3. **Prepara la partida.** Los miembros votan por Clásico (cultura general) o Ciencia. Ambos usan el mismo tablero. Un empate se resuelve entre las opciones con más votos; sin votos todas empatan. Marca que estás listo. El anfitrión puede añadir o quitar bots y expulsar jugadores; un expulsado no puede volver a esa sala.
4. **Inicia.** El anfitrión necesita al menos dos participantes, con los demás humanos listos. Los bots cuentan como listos. Si el inicio devuelve indisponibilidad, consulta la misma sala: puede haber quedado pendiente de confirmación. No crees otra sala solo por un timeout.
5. **Juega por turnos.** Lanza los dados, elige un movimiento permitido y termina el turno cuando corresponda. El servidor comprueba el dado, ficha, origen y destino. Los bots actúan solos. Las fichas requieren dobles para salir de casa; el tablero tiene casillas seguras y de trivia.
6. **Resuelve cartas y trivia.** Responde dentro del plazo cuando aparezca una pregunta. Un acierto da una ventaja; un fallo o tiempo agotado da una penalización. Si la carta pide objetivo, elige la ficha propia o la casilla que ofrece el servidor. Las cartas de captura de casilla pueden capturar incluso en un seguro. No se solicita al cliente que determine la respuesta correcta.
7. **Finalización y reconexión.** Gana quien corona sus cuatro fichas. En el carril final, un dado mayor que la distancia restante no permite mover esa ficha; usa el valor exacto para coronar. Un jugador que pierde conexión puede sincronizar antes de ser retirado. El cliente debe volver a autenticar si expiró el JWT y consultar el estado de la misma partida. Si ya fue retirado, el servidor no admite volver a jugarla.

Para demostración técnica sin Godot, usa las rutas descritas en [Swagger y API](MANUAL_INSTALACION.md#operaciones-rest), el [protocolo WebSocket](../game-service/docs/WEBSOCKET.md) y la [prueba de recorrido](second-cut/evidence.md).

El tablero tiene **100 casillas físicas**: 68 comunes, en cuatro sectores de 17, y cuatro corredores finales de ocho (la octava es la corona). Las casas son estados de las fichas: una ficha HOME no ocupa casilla. Los dos mapas comparten la geometría y difieren en la temática de sus 16 preguntas.

| Color / asiento | Sector | Salida compartida | Seguros | Corredor | Corona |
|---|---|---:|---|---|---:|
| Rojo / 0 | 0–16 | 5 | 0, 5, 12 | 68–75 | 75 |
| Azul / 1 | 17–33 | 22 | 17, 22, 29 | 76–83 | 83 |
| Verde / 2 | 34–50 | 39 | 34, 39, 46 | 84–91 | 91 |
| Amarillo / 3 | 51–67 | 56 | 51, 56, 63 | 92–99 | 99 |

Salir exige dobles y consume ambos dados; el valor no añade avance a esa liberación. Cada salida admite cuatro fichas; los seguros normales admiten dos. Un seguro protege de captura ordinaria; las dos cartas de captura de casilla pueden ignorarlo. Trivia se activa en 8, 25, 42, 59. La coronación exige valor exacto: faltando una casilla, 1 corona y 2 no permite mover; faltando dos, 2 corona y 3 no permite mover. Una coronada no vuelve a avanzar.

El servidor representa cualquier regreso a casa como `{"status":"HOME","position":null,"progress":-1}`; no existe una casilla de cárcel. Si una partida anterior a esta geometría devuelve `UNSUPPORTED_BOARD_LAYOUT`, no puede reanudarse con el servidor nuevo: sus datos se conservan. Consulta al administrador para usar la versión anterior aislada o iniciar una sala nueva; no borres datos ni intentes reutilizar coordenadas. Véanse [fórmulas y acceso confirmado](../game-service/docs/BOARD.md) y [estado de validación](BOARD100_VALIDACION.md). La interfaz Godot real todavía requiere validación.

El seguro de acceso de cada color coincide con su salida: rojo 5, azul 22, verde 39 y amarillo 56. La ficha debe volver a ese seguro tras la vuelta completa antes de entrar al corredor. Al finalizar, rojo sigue 4→5→68; azul 21→22→76; verde 38→39→84; amarillo 55→56→92. El seguro sigue siendo una sola casilla física. El cliente debe distinguir el progreso de salida (0) del regreso (68); la corona exige progreso exacto 76.


### Actualización de pares y temporizadores

Un par concede otra tirada una vez resuelto. Si quedan fichas HOME, libera obligatoriamente hasta 4 con (1,1), 3 con (6,6) o 2 con los demás pares; consume ambos dados incluso si libera menos. No se puede mover con esos dados. Sin HOME, admite suma o uso separado. Se conservan los tres intentos HOME y la captura especial de salida. Tres pares conceden coronación de una ficha propia activa previa (recorrido o corredor); el jugador conserva la selección CROWN_REQUEST y después termina el turno, sin cuarta tirada ni penalización por tres pares.

Movimiento: 30 segundos tras confirmar cada animación (máximo 10 segundos para confirmar). Las liberaciones no abren reloj de movimiento. Trivia: 15 segundos independientes; pausa el saldo de movimiento y lo reanuda sin reiniciarlo. Sin respuesta se resuelve como incorrecta; se rechazan respuestas tardías. Al vencer movimiento se conservan jugadas aceptadas, se descartan dados pendientes, se evalúa SOPLAR solo por oportunidades reales omitidas y pasa turno sin lanzamiento extra pendiente.

Godot debe reconocer HOME_RELEASE_RESOLVED, los dados consumidos y ROLL_AVAILABLE, además de MOVEMENT_TIMER_PAUSED/RESUMED y trivia.deadline. Reconectar mantiene contadores, saldo y plazos del servidor. Véase [contrato WebSocket](../game-service/docs/WEBSOCKET.md). No cambia el tablero v4-100, el catálogo ni las acciones existentes. Los campos nuevos son aditivos; tiradas/trivias ya persistidas antes de actualizar conservan su estado, y las nuevas reglas se aplican desde la siguiente activación, sin borrar partidas o volúmenes.
