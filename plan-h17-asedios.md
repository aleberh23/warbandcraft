# H17 — Asedios: invasiones de verdad contra tus aldeas

Pedido de Ale (2026-10-13): "las invasiones vienen muy default: 8 soldados, aparecen todos juntos y dentro de la
aldea. Que aparezcan afuera (afuera de las murallas), que si no pueden pasar pongan escaleras y trepen, que los
zapadores revienten las murallas (y en Gestión, reparar murallas rotas), que ataquen por distintos lados. Puede
quedar la incursión como ahora, pero que también haya ejércitos comandados por un general."

## Hecho

- Aparecen afuera de la aldea / de los muros, del lado de donde vienen (`Assault.spawn`).
- Dos clases: incursión (como antes, pero afuera) y ejército con general (columnas por 2-3 lados, formación,
  arqueros afuera, zapadores, retirada ordenada, el general se queda atrás y si cae pelean sin orden).
- Entrar: puerta/camino abierto → por ahí; si no, zapador con TNT; si no, escaleras de mano y trepan.
- Reparar murallas desde Gestión (V) → Mejoras y el Panel (K): fila que aparece sola cuando falta algo del anillo.
- Pruebas: `H17GameTests` (medir muro + escalera + almena, ejército afuera en columnas + retirada, reparar,
  zapadores en la autoresolución).

## A revisar por Ale (decisiones tomadas sin preguntar)

- Números: ejército con leva ≥14 y 50% de chance; tamaño hasta 24; 2 zapadores; 3 columnas con ≥14 soldados;
  forman 15 s; se retiran con 60% de bajas (70% incursión, 40% si cayó el general) o a los 6 min.
- Las escaleras se sacan al terminar la pelea (para que no te queden atajos en el muro). La TNT del zapador rompe
  lo que rompa (también casas cerca del muro).
- Trepar es "guionado" (no la IA de Minecraft): sube a 3 bloques/s, pasa por arriba y cae adentro.
- Las torres no se reparan (solo el anillo). Castillos: no se reparan (no tienen plano).
- Las guerras entre facciones de la IA (H15b) siguen como estaban: esto es solo contra tus aldeas.

## Primera prueba de Ale (2026-10-13): "anda bastante mal"

- No ponían escaleras: la medición se colaba en diagonal entre dos bloques del anillo que se tocan por la esquina (y
  tomaba lo que no podía medir como abierto) → creían que había puerta. Corregido, y si un lado "abierto" no sirve en
  40 s se descarta.
- Agua: no aparecen ni forman en agua, no nadan (la rodean), el agua no cuenta como paso.
- Van en grupos de 3-5 hasta entrar; adentro se separan.

## Segunda prueba de Ale: "no trepan ni pasan las murallas; spawnean del otro lado del río"

Del log: ponen escaleras pero nadie llega a menos de 1,2 bloques del pie (Recruits los frena antes), así que nunca
suben; una columna apareció del otro lado del río y repitió "no encuentra por dónde entrar" 4 minutos.

Pedido: que trepen, pathfinding propio para asedios y lugares cerrados, botes para cruzar agua.

### Plan (propuesto, esperando el OK de Ale)

1. Arreglo rápido: llevarlos nosotros los últimos bloques al pie de la escalera; columnas solo en lados conectados por
   tierra (búsqueda simple).
2. Mapa del terreno de la batalla (~100 bloques alrededor, por columna y piso, con costos: caminar 1, escalón 1,5,
   puerta 2, escalera nueva 25, agua en bote 4/bloque, romper bloque 15 solo zapador). Se arma de a pedazos y se
   actualiza con cambios de bloques.
3. "Mapa de flechas" (Dijkstra desde el objetivo, en otro hilo): todos los soldados lo usan; donde el camino cruza el
   muro se ponen las escaleras; donde cruza agua, botes. Sirve también para batallas (hacia los enemigos, cada 2-3 s).
4. Movimiento propio tick a tick (MoveControl + salto) mientras marchan; Recruits solo pelea. Escalera, puerta y bote
   guionados. Grupos de 3-5 siguen al guía.
5. Botes: 2 por bote, los remamos nosotros; quedan en la costa. Caballería no.
6. Aparecer solo donde se llega (por tierra, o por agua con botes desde la orilla).
7. Después: batallas de H9 y castillos con lo mismo, sin teletransportes.

## Tercera vuelta: malla de navegación estilo Bannerlord (pedido de Ale, 2026-10-13)

Decidido con Ale: copiar el comportamiento de Bannerlord (no su código: es de TaleWorlds). Evitar el agua lo más
posible. Defensores = cuartel; si caen, refuerzos del barracón. Se reparten por los puntos de ataque; arqueros al mejor
ángulo; los demás tumban escaleras y si no pueden, esperan adentro para darles.

Hecho: `nav/` (malla + A* + Mover con escaleras, puertas y botes), `Assault` reescrito sobre carriles replaneados cada
8 s (hueco abierto primero; si no, escalera / TNT / cañón donde el camino cruza el muro), grupos de 3-5 por la malla,
moral por soldado, `SiegeDefense`. Pruebas: `NavGameTests` (incluye un recluta que trepa de verdad).

A revisar por Ale: costos (agua ×30, trepar 25 + alto, romper 30), 2 escaleras por carril, 12 s para volver a poner una
tumbada, tumbar desde el pie de adentro ("horquilla"), moral (−7 por compañero a 12, −25 el general, huye con <20),
cañón a 12-26 del muro, refuerzos cada 3 s.
Pendiente: batallas de H9 y castillos con la misma malla.

## Cuarta vuelta: asedio de castillos, batallas y campo abierto (Ale: "dale todo junto", se fue a merendar)

Pedido de Ale: en asedios, los montados desmontan y pelean de a pie. Después, aplicar el combate nuevo a batallas y a
asedios de castillos, todo junto.

Hecho:
- `battle/SiegeLanes` (atacante) y `battle/SiegeDefenders` (defensor) sacados de `Assault`/`SiegeDefense`, con un
  `Host` por clase de pelea. Invasiones igual que antes, encima de esas piezas.
- Tu ataque a un castillo o a una aldea con muralla, con soldados en Batalla: carriles, escaleras y TNT de tus
  zapadores; la guarnición defiende por carril; todos de a pie (tus caballos quedan ahí).
- Batallas entre facciones de la IA: aparecen afuera; contra castillos, asedio completo (con cañones).
- Campo abierto: el destrabador ya no teletransporta (malla de navegación o espera). Arqueros de torre del cuartel
  suben por la escalera.

A revisar por Ale (decidido sin preguntar):
- Tu asedio: 1 carril, 2 desde 10 soldados en Batalla (separados 40°). Tus soldados no arman cañones solos (los armás
  vos con el Zapador, como siempre) y no tienen moral (no huyen).
- Batallas de la IA: 2 carriles desde 10 soldados (50°); el jugador que mira no recibe avisos de escaleras.
- Caballería en campo abierto: sigue con el movimiento de Recruits (la malla es de a pie); trabada, saltito.
- No hay botón "Escaleras acá" en la vista aérea.

## Quinta vuelta: túneles, cañones solos y barra de quién va ganando (pedido de Ale)

- Los zapadores atacando pueden cavar un túnel por debajo del muro; las tropas entran por ahí cuando está listo.
- Pueden armar cañones solos (también tus soldados en Batalla, pagando 3 lingotes + 1 bloque de hierro como siempre).
- Barra arriba al estilo Total War con los soldados que le quedan a cada bando y quién va ganando.

A revisar por Ale: un cañón por asedio (el otro zapador cava); orden cañón > túnel > TNT > escaleras; el túnel baja 3 y
cava 1 tramo por segundo; los túneles no se tapan solos al terminar; "va ganando" con 60% de los que quedan.
