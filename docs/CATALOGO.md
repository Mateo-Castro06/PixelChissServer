# Catálogo definitivo de PixelChiss

Fuente: [catalog.json](../game-service/src/main/resources/seed/catalog.json). Los índices comienzan en cero; las opciones se muestran en el orden que usa el protocolo.

## `classic` — Cultura general

| ID | Pregunta | Opciones | Respuesta (índice) |
|---|---|---|---|
| `classic-general-01` | ¿Cuál es la capital de Canadá? | Toronto · Ottawa · Vancouver | Ottawa (`1`) |
| `classic-general-02` | ¿En qué país se encuentra Machu Picchu? | Perú · Bolivia · Ecuador | Perú (`0`) |
| `classic-general-03` | ¿Qué océano separa a África de Australia? | Atlántico · Pacífico · Índico | Índico (`2`) |
| `classic-general-04` | ¿En qué año cayó el Muro de Berlín? | 1979 · 1989 · 1999 | 1989 (`1`) |
| `classic-general-05` | ¿En qué año alunizó por primera vez una misión tripulada? | 1959 · 1965 · 1969 | 1969 (`2`) |
| `classic-general-06` | ¿Quién escribió Don Quijote de la Mancha? | Miguel de Cervantes · Lope de Vega · Federico García Lorca | Miguel de Cervantes (`0`) |
| `classic-general-07` | ¿Quién pintó el Guernica? | Salvador Dalí · Pablo Picasso · Diego Velázquez | Pablo Picasso (`1`) |
| `classic-general-08` | ¿En qué idioma se publicó originalmente El principito? | Inglés · Español · Francés | Francés (`2`) |
| `classic-general-09` | ¿Qué instrumento tiene teclas que accionan martillos para golpear cuerdas? | Piano · Órgano de tubos · Acordeón | Piano (`0`) |
| `classic-general-10` | ¿Qué valor representa la letra L en números romanos? | 10 · 50 · 100 | 50 (`1`) |
| `classic-general-11` | ¿En qué ciudad se celebraron los primeros Juegos Olímpicos modernos en 1896? | Roma · París · Atenas | Atenas (`2`) |
| `classic-general-12` | ¿Qué selección ganó la primera Copa Mundial masculina de fútbol en 1930? | Uruguay · Brasil · Italia | Uruguay (`0`) |
| `classic-general-13` | ¿Cuántos puntos vale una canasta de baloncesto anotada desde detrás de la línea de tres puntos? | Dos · Tres · Cuatro | Tres (`1`) |
| `classic-general-14` | ¿En qué ciudad está la Torre Eiffel? | Lyon · Bruselas · París | París (`2`) |
| `classic-general-15` | ¿En qué continente se encuentra la cordillera de los Andes? | América del Sur · África · Asia | América del Sur (`0`) |
| `classic-general-16` | ¿Quién compuso la Novena Sinfonía que incluye la Oda a la alegría? | Johann Sebastian Bach · Ludwig van Beethoven · Frédéric Chopin | Ludwig van Beethoven (`1`) |

## `science` — Ciencia

| ID | Pregunta | Opciones | Respuesta (índice) |
|---|---|---|---|
| `science-1` | ¿Cuál es la fórmula del agua? | CO2 · H2O · O2 | H2O (`1`) |
| `science-2` | ¿Qué planeta está más cerca del Sol? | Mercurio · Marte · Júpiter | Mercurio (`0`) |
| `science-3` | ¿Qué órgano bombea sangre? | Pulmón · Cerebro · Corazón | Corazón (`2`) |
| `science-4` | ¿Cuántos lados tiene un triángulo? | 3 · 4 · 5 | 3 (`0`) |
| `science-5` | ¿Qué gas absorben las plantas como fuente de carbono en la fotosíntesis? | Oxígeno · Dióxido de carbono · Helio | Dióxido de carbono (`1`) |
| `science-6` | ¿Cuál es el número atómico del oxígeno? | 8 · 6 · 16 | 8 (`0`) |
| `science-7` | ¿Cuál es la unidad de fuerza en el Sistema Internacional? | Pascal · Julio · Newton | Newton (`2`) |
| `science-8` | ¿Qué planeta es el más grande del sistema solar? | Saturno · Júpiter · Neptuno | Júpiter (`1`) |
| `science-9` | ¿Cuál es la estrella más cercana a la Tierra? | El Sol · Sirio · Próxima Centauri | El Sol (`0`) |
| `science-10` | ¿Qué mineral tiene dureza 10 en la escala de Mohs? | Cuarzo · Corindón · Diamante | Diamante (`2`) |
| `science-11` | ¿Cómo se llaman las rocas formadas por la solidificación del magma o la lava? | Sedimentarias · Ígneas · Metamórficas | Ígneas (`1`) |
| `science-12` | ¿A qué grupo de vertebrados pertenecen las ranas? | Anfibios · Reptiles · Mamíferos | Anfibios (`0`) |
| `science-13` | ¿Cuál es el gas más abundante en la atmósfera terrestre seca? | Oxígeno · Argón · Nitrógeno | Nitrógeno (`2`) |
| `science-14` | ¿Cuál es la unidad de corriente eléctrica en el Sistema Internacional? | Voltio · Amperio · Ohmio | Amperio (`1`) |
| `science-15` | ¿En qué orgánulos de una célula animal se produce la mayor parte del ATP durante la respiración aeróbica? | Mitocondrias · Ribosomas · Lisosomas | Mitocondrias (`0`) |
| `science-16` | ¿Cuál es aproximadamente el pH del agua pura a 25 °C? | 2 · 12 · 7 | 7 (`2`) |

## Cartas

| ID | Tipo | Nombre |
|---|---|---|
| `kill-next` | BUFF | Matar al siguiente |
| `kill-chosen` | BUFF | Matar a tu elección |
| `advance-safe` | BUFF | Avanza al siguiente seguro |
| `skip-turn` | DEBUFF | Pierde un turno |
| `instant-death` | DEBUFF | Muerte instantánea |
| `doubles-death` | DEBUFF | Si alguno saca par muerte a una ficha |

Ver [efectos y reglas límite](../game-service/docs/ENGINE.md) y [actualización de catálogo](GUIA_ACTUALIZACION.md#migrar-mongodb).
