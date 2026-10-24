# H20 — Ejércitos de campaña

Pedido de Ale (2026-10-13): "Desde el edificio de un barracón, crear ejércitos y asignar soldados de distintos
Campamentos. Al invocarlo (solo en colinas, desiertos o biomas aptos para batallas) aparecen todos en formación, bloques
de 10, infantería adelante, arqueros atrás y caballería. Manteniendo el terreno. Sirve para emboscar o librar batallas
con ejércitos enemigos que estén dando vueltas o atacando una aldea o castillo. También desde el Panel de control.
Máximo 100 soldados. (Acepto sugerencias.)"

> Estado (2026-10-13): **hecho, a probar** (3 GameTests nuevos, 158/158 con el resto). Ale pidió seguir solo; lo que
> quedó afuera o se decidió sin él está al final, en "Hecho".

Decidido con Ale: los soldados **tardan según la distancia** (como un envío de rutas, más rápido) y aparecen todos
juntos, ya formados, al terminar la cuenta.

## Armar un ejército (sin sacar a nadie todavía)

- `army/ArmyData` (SavedData por dimensión): ejércitos por jugador. Cada uno: nombre, estandarte (el de tu facción),
  y una lista de "pedidos" = **Campamento + clase + cantidad** (no soldados sueltos: si uno muere o se mueve, se toma
  otro de la misma clase). Máx. **100 soldados** por ejército, máx. 3 ejércitos por jugador (a revisar).
- Pantalla **Ejércitos** (pestaña nueva del Campamento y pestaña nueva del Panel K): a la izquierda tus ejércitos, a la
  derecha cada Campamento con sus soldados por clase y botones +1 / +10 / todos. Arriba: total, poder estimado
  (`BattleGeneral.power`) y comida que lleva.
- Un soldado asignado a un ejército sigue en su barracón hasta que se invoca.

## Invocar

- Botón **Invocar aquí** (o desde el Panel, en tu posición). Requisitos:
  - Bioma apto: llanuras, sabana, desierto, colinas/praderas de montaña, estepa, taiga abierta (tag propio
    `warbandcraft:battlefield`, editable). No: océano, río, pantano, jungla, picos, cuevas.
  - Terreno: 60×40 bloques alrededor con poco desnivel (la mayoría a ±4) y sin agua en el medio.
  - No dentro de una aldea/castillo (ni tuyo ni ajeno).
- **Cuenta regresiva**: tiempo de viaje del Campamento más lejano (por rutas si las hay; si no, en línea recta) ÷ 3,
  entre 10 s y 3 min. Barra arriba en pantalla ("El ejército llega en 1:20"). Si te alejás >64 bloques, se cancela.
- Al llegar, aparecen **formados** mirando hacia donde mirabas:
  - Infantería adelante en bloques de 10 (2 filas de 5), con 2 bloques entre bloques.
  - Arqueros/ballesteros 6 bloques atrás, en bloques de 10.
  - Caballería en los dos flancos.
  - Zapadores y capitán atrás en el centro (el capitán lleva el estandarte).
- Quedan **manteniendo el terreno** (estado 3 en su puesto de formación, como `BattleGeneral`): no persiguen lejos;
  pelean a lo que se acerque a ~16 bloques y vuelven a su lugar.

## Mandar el ejército

- Vista aérea (V): el ejército aparece como un grupo propio. Órdenes nuevas: **Mantener** · **Avanzar** (la formación
  camina hacia el punto, sin romperse) · **Cargar** (todos al enemigo; usa la IA de H9) · **Retirada** · **Licenciar**.
- **Emboscada**: si un ejército enemigo de la IA (`InvasionData.March` / ejército invasor) pasa a ≤48 bloques del tuyo
  mientras está quieto y "escondido" (bioma con árboles o detrás de una loma), sus soldados no te ven hasta que cargás:
  tu primera carga hace +30% de daño los primeros 10 s (a revisar).
- **Batalla de campo**: si cargás contra una marcha de la IA, la marcha se vuelve una batalla real (lo de `AiWars` /
  `Ambush.spring` ya existente): aparecen sus soldados y pelean con el general de H9. Ganar: prisioneros a tu barracón
  más cercano + botín + opinión (+10 con la facción atacada si defendías su aldea).
- Ayudar a una aldea/castillo atacado: invocar cerca de la batalla y cargar = te sumás al bando defensor (`AiWars.onHit`).

## Licenciar / volver

- **Licenciar**: cada soldado vuelve a su barracón (envío por ruta con su tiempo, o directo si no hay ruta).
- Muertos: se borran del pedido; el ejército queda con menos. Rendidos tuyos que perdiste: como en H9.
- Comida: mientras está afuera, el ejército come de su Campamento de origen (como el barracón).
- Si te desconectás o te alejás >256 bloques, se guarda solo en sus barracones (como el guardado automático de H8).

## Sugerencias mías (para que Ale elija)

- **Ver los ejércitos enemigos**: hoy salen en el mapa M (rombo). Sumar en el Panel una lista "Ejércitos cerca" con
  distancia y tamaño, y un botón "Invocar en su camino" que te marca el punto de la ruta por donde van a pasar.
- **Moral del ejército**: si cae el capitán o pierden 50%, retroceden solos (ya existe en H17 para la IA).
- **Experiencia**: los que ganan una batalla suben de nivel más rápido (usa lo del entrenamiento de H19).
- **Estandarte en el piso**: al invocar se planta tu estandarte; mientras esté de pie, +moral. Si lo tiran, −moral.

## Pruebas (GameTests)

- Crear ejército con 2 Campamentos (12 + 8 soldados) → invocar → aparecen 20 en formación (infantería adelante,
  arqueros atrás), los barracones quedan sin ellos.
- Invocar en océano/pantano → rechazado.
- Licenciar → vuelven a sus barracones.
- Ejército contra una marcha de la IA → empieza la batalla real.

## A revisar por Ale

- Biomas aptos, tamaño del terreno, divisor de tiempo de viaje (÷3), máximo de ejércitos por jugador.

## Hecho (2026-10-13, a probar)

- Todo lo de "Armar", "Invocar", "Mandar" y "Licenciar" de arriba, con estos detalles decididos sin Ale:
  - La pantalla Ejércitos es aparte (pestaña "Ejércitos" del Panel K, botón en el Barracón del Campamento y
    `/warband ejercitos`); el Campamento no tiene una pestaña nueva.
  - La cuenta regresiva es en línea recta (no por rutas): distancia / 4,3 / 3, entre 10 s y 3 min.
  - Licenciar los guarda directo en su barracón (sin envío por ruta); si está lleno, al Campamento más cercano con lugar.
  - Batalla de campo ganada: +40 de experiencia a cada sobreviviente (la sugerencia "Experiencia"); sin prisioneros ni
    botín aparte (los caídos sueltan lo suyo) y sin +10 de opinión.
  - Emboscada = la primera carga desde un lugar con hojas pega +30% por 10 s; el enemigo no queda "ciego".
  - Ayudar a una aldea atacada: invocarlo a más de 64 bloques y Avanzar/Cargar; los atacantes de la IA cuentan como enemigos.
- No hecho: moral propia del ejército, estandarte plantado, "Ejércitos cerca" / "Invocar en su camino" en el Panel.
