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
