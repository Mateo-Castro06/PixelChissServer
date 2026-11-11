# Mantenimiento anterior del catálogo de desarrollo

Este registro conserva el estado anterior al catálogo definitivo. Ver [catálogo vigente](CATALOGO.md).

Consulta y mantenimiento del 10 de noviembre de 2026. Compose resolvió el destino a `mongodb/pixelchiss` en el volumen existente `docker_mongodb_data`. Se abrió el volumen original con MongoDB 7 en un contenedor de mantenimiento sin red externa. Game Service no se inició contra ese volumen.

## Cartas retiradas

El conjunto consultado en `pixelchiss.game_cards` coincidió con los cuatro identificadores, nombres y tipos del catálogo de referencia. Se revalidó la instantánea antes de ejecutar un borrado por esos cuatro `_id`, con confirmación de escritura en journal.

| Identificador | Nombre | Tipo |
|---|---|---|
| `capture-next` | Alcance rival | BUFF |
| `choose-dice` | Elección | BUFF |
| `dice-limit` | Dados reducidos | DEBUFF |
| `move-safe` | Refugio | BUFF |

MongoDB confirmó **4 borrados**. El conteo posterior, repetido en una consulta independiente, fue **0**. La colección y sus índices permanecen.

| Otra colección de aplicación | Documentos antes y después | Huella de contenido antes y después |
|---|---:|---|
| `pixelchiss.game_questions` | 8 | `e5d7eabeb1b7b5cb758342a7d2e540f1` |
| `test.test` | 1 | `1e37a8c6321570b5b84612ed23a866e2` |

Las huellas, opciones e índices de las demás colecciones de aplicación coincidieron; también se compararon los documentos completos de trivia. No se ejecutaron seeds ni se crearon cartas o preguntas. No se borraron bases ni volúmenes.

## Inventario de trivia

Hay **8 preguntas** en `pixelchiss.game_questions`: cuatro para `classic` y cuatro para `science`. El tema se deriva del mapa de Lobby: «El juego» para `classic` y «Ciencia» para `science`. MongoDB no guarda una categoría independiente en estos documentos. Las opciones se muestran en el orden almacenado; `correctIndex` empieza en cero.

| Identificador | Mapa | Enunciado | Opciones | Respuesta correcta (índice) |
|---|---|---|---|---|
| `classic-1` | `classic` | ¿Cuántas caras tiene un dado del juego? | 4 · 6 · 8 | 6 (`1`) |
| `classic-2` | `classic` | ¿Cuántas fichas tiene cada jugador? | 2 · 3 · 4 | 4 (`2`) |
| `classic-3` | `classic` | ¿Cuántas casillas tiene el tablero? | 96 · 100 · 64 | 96 (`0`) |
| `classic-4` | `classic` | ¿Qué protege una casilla segura? | El dado · Las fichas frente a captura normal · El reloj | Las fichas frente a captura normal (`1`) |
| `science-1` | `science` | ¿Cuál es la fórmula del agua? | CO2 · H2O · O2 | H2O (`1`) |
| `science-2` | `science` | ¿Qué planeta está más cerca del Sol? | Mercurio · Marte · Júpiter | Mercurio (`0`) |
| `science-3` | `science` | ¿Qué órgano bombea sangre? | Pulmón · Cerebro · Corazón | Corazón (`2`) |
| `science-4` | `science` | ¿Cuántos lados tiene un triángulo? | 3 · 4 · 5 | 3 (`0`) |

Todos los `mapId` coinciden con mapas admitidos y los índices apuntan a opciones existentes. El modelo real es `QuestionDefinition` en Game y su adaptador Mongo usa `CatalogDocuments.QuestionDocument`. El motor consulta preguntas por `mapId`. Con el catálogo de cartas vacío, la trivia sigue evaluando respuestas; la resolución registra `CARD_DISCARDED` con motivo `EMPTY_CATALOG` cuando no hay recompensa.

## Evitar que se repueble el catálogo

El seeder existente usa `$setOnInsert` y volvería a crear los IDs que falten. En esta instalación se dejó `GAME_SEED_ENABLED=false` en el `.env` local ignorado. El Compose lo transmite a Game. Si se ejecuta Game fuera de Compose contra esta base, debe exportarse la misma opción antes de arrancar. El valor predeterminado para instalaciones nuevas se conserva.
