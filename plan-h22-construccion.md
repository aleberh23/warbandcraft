# H22 — Construir desde el modo Gestión (plan)

Pedido de Ale: las mejoras de una aldea (taberna, capilla, muralla, torres, empalizada, puestos de control, casas,
granero...) se **plantan a mano** desde el modo Gestión, estilo Stronghold / SimCity. También **allanar** y
**rellenar con tierra**. Todo consume (y a veces deja) recursos del almacén de la aldea: piedra, madera, tierra...
más algunas esmeraldas. Una obra a la vez, salvo la muralla que se dibuja entera. El constructor la va haciendo.

## Qué copiamos de cada juego (ingeniería inversa)

| Juego | Cómo lo hace | Qué tomamos |
|---|---|---|
| Stronghold | Menú de edificios por categoría (Castillo / Pueblo / Comida...). El edificio sigue al mouse como "fantasma" verde/rojo. Click = se planta y se paga **en el acto** con madera/piedra/oro. Muros: arrastrás y se dibuja la línea; torres y portones se apoyan sobre el muro. Demoler devuelve parte. | Menú por categoría, fantasma verde/rojo con el motivo, pagar al plantar, muralla dibujada con torres y portones encima, demoler con devolución. |
| SimCity | Herramientas de terreno: subir, bajar, **aplanar** arrastrando un rectángulo; topadora; costo según el tamaño, que se ve antes de soltar. | Allanar / Rellenar arrastrando un rectángulo, con el costo en vivo mientras arrastrás. |
| Lo que ya tenemos (H12/H16) | Obras bloque a bloque (`TownData.Job`), constructor que acompaña, edificios en código (`Blueprints.building`), rectángulos en el piso (zonas de trabajo), territorio. | Todo eso se reusa: lo nuevo es **elegir el lugar** y **pagar en materiales**. |

## Decidido con Ale

- Puesto de control = **portón en la muralla** (tus soldados y aldeanos pasan, los enemigos no).
- Materiales: **todo al encargar**; si no alcanza, no se planta. Cancelar devuelve lo que no se puso.
- Terreno desparejo: **el edificio se allana solo** y el allanado entra en la cuenta (tierra que falta; lo cortado va al almacén).
- **Solo a mano**: todo se planta desde Gestión. El Panel K muestra lo hecho y la obra en curso, sin botón de encargar.
- **Casas automáticas apagadas**: la casa también se planta a mano.
- **Castillos: por ahora no** se construye a mano (sus mejoras siguen eligiendo lugar solas, desde el Panel K).
- **Murallas por niveles**, de madera a piedra (H22c):
  | Nivel | Muralla | Portón (puesto de control) | Torre |
  |---|---|---|---|
  | 1 | Empalizada de troncos | Portón de madera (cerco de troncos + puertas de cerco) | Torre de madera (troncos, plataforma, escalera) |
  | 2 | Muralla de piedra con almenas | Portón de piedra (arco de ladrillo, rastrillo de rejas) | Torre de piedra (la de H12) |
  Subir de nivel un tramo/portón/torre = obra que reemplaza la madera por piedra (la madera vuelve al almacén).

## Cómo se juega

1. Vista aérea (V) → Gestión → pestaña nueva **Construir** (reemplaza a "Mejoras"; en el Panel K, Mejoras queda solo para mirar).
2. Categorías a la izquierda:
   - **Defensa**: Empalizada, Muralla, Torre, Portón / Puesto de control.
   - **Pueblo**: Casa, Granero, Mercado, Taberna, Capilla (nueva), Herrería, Cuartel, Campo de entrenamiento, Taller del constructor.
   - **Terreno**: Allanar, Rellenar, Demoler.
3. Elegís uno → el contorno del edificio sigue al mouse sobre el piso. **R** lo gira 90°. Verde = se puede; rojo con el
   motivo abajo ("Fuera del territorio", "Pisa una casa", "Desnivel de 5: allaná primero", "Falta piedra: 24").
   Abajo del mouse: la cuenta ("Madera 40 · Piedra 12 · 6 esmeraldas"). Click = se encarga y se paga.
4. **Muralla / Empalizada**: click, click, click... cada click agrega un tramo (línea recta, se ve la cuenta del total).
   Doble click o Enter = encargar todo el trazado como **una obra**. Click derecho = sacar el último punto. Si cerrás
   el dibujo sobre el primer punto queda un anillo. Botón "Anillo automático" = dibuja el anillo de H12 para que lo edites.
5. **Torre**: se planta sobre un tramo de muralla (se pega sola al muro más cercano) o suelta.
   **Portón**: sobre un tramo de muralla; reemplaza 3 de muro por un portón de 3 de ancho con puertas de cerco que se
   abren solas para los tuyos (soldados, aldeanos, trabajadores) y se cierran con enemigos a ≤8. Los caminos de H12
   (`dirt_path`) que cruzan el trazado ponen un portón solo.
6. **Allanar**: arrastrás un rectángulo; la altura es la del primer bloque que clickeaste (rueda del mouse = ±1).
   Lo que sobra se corta y **va al almacén** (tierra, piedra→adoquín, arena...); los pozos se rellenan con tierra del almacén.
   **Rellenar**: igual pero solo sube (pone tierra hasta esa altura, nunca saca).
7. Mientras hay una obra, la pestaña muestra la barra y "Cancelar" (devuelve lo que no se puso).
8. **Demoler**: click en un edificio nuestro → se saca y devuelve el 50% de los materiales.
   **Mejorar** (Taberna 1→2→3, Taller, Entrenamiento): click en el edificio ya hecho → "Mejorar" con la cuenta.

## Materiales

- Cada bloque del plano cuesta su material (tabla en `BuildCost.add`; pasa a datos si hace falta):
  - Madera: tablones = ¼ de tronco; troncos, troncos pelados, tablones, cercas, escaleras de madera → se pagan con
    **troncos o tablones** del almacén (equivalencia automática: 1 tronco = 4 tablones).
  - Piedra: piedra, adoquín, ladrillos de piedra, losas → **adoquín o piedra** (1 = 1).
  - Tierra: tierra, tierra de cultivo, camino → **tierra** (para rellenar también sirven grava y arena; a revisar).
  - Vidrio, lana (camas = 3 lana + 3 madera), hierro (faroles 1, yunque 8, caldero 7...): su material.
  - Bloque sin entrada en la tabla = gratis (adornos).
- Encima de los materiales, cada edificio cobra esmeraldas como hoy (`Upgrade.cost`, más bajo: la mitad, porque ahora
  paga materiales).
- Primero sale del almacén de la aldea (el Campamento de H16 + sus cofres vinculados), después de tu inventario.
- Allanar: emeraldas por tamaño (1 cada 64 bloques movidos, mín. 1) + tierra que falte; lo cortado vuelve al almacén
  (si el almacén se llena, se tira al lado del Campamento).

## Reglas

- Una obra por aldea (ya es así). La muralla dibujada es UNA obra aunque tenga 20 tramos.
- Todo adentro del territorio (`Territory`; la muralla puede ir hasta 2 bloques afuera del borde).
- Nunca pisa block entities, camas, campanas, el Campamento, zonas de trabajo marcadas (excepto Allanar sobre una zona, con aviso).
- Terreno desparejo: el plano del edificio lleva el allanado de su huella +1 de borde (hasta 8 de desnivel; más, rojo).
- Velocidad: 8 bloques/s × nivel del taller (ya existe `LEVEL_BONUS`). Sin taller: 4 bloques/s.
- Límites de terreno: rectángulo ≤ 32×32 y ≤ 8 de diferencia de altura por obra.
- Los edificios únicos (Granero, Mercado, Herrería, Cuartel, Capilla) siguen siendo uno por aldea; Casas y Torres, varias.

## Cambios en el código

- `upgrade/Upgrade`: + `CHAPEL`, `GATE` (portón), `TOWER` (una torre suelta, no `listed()`), `EARTH_LEVEL`, `EARTH_FILL`,
  `DEMOLISH`. Las que ya existen siguen con su id (mundos guardados).
- `upgrade/Blueprints.building(level, u, site, rotation)`: el ayudante `b.set/fill` ya trabaja en coordenadas locales:
  se le suma girar 0/90/180/270. `Blueprints.wall(level, points, stone)`: tramos con Bresenham sobre columnas, misma
  sección que el anillo de hoy (altura, almenas, pasarela). `Blueprints.earth(level, rect, y, fillOnly)`.
- `upgrade/TownData.Town`: lugar + giro de cada edificio (`sites` pasa a guardar también el giro); `walls` = lista de
  trazados (puntos) para que `WallRepair`, `Quarters.towerPosts` y Popularidad sigan andando con murallas dibujadas;
  `towers` = posiciones de cada torre suelta.
- `upgrade/BuildCost` (nuevo): plano → cuenta de materiales (tabla de datos) + esmeraldas; `canPay`, `pay`, `refund`.
- `upgrade/Placement` (nuevo): valida un lugar (lo mismo que dibuja el cliente en rojo: territorio, desnivel, pisa
  algo, ya hay obra) — misma idea que `ManagePanel.rectProblem` ↔ `WorkZones.mark`.
- `network/ManageActionMessage`: `PLACE` (upgrade, esquina, giro), `WALL` (lista de puntos), `EARTH` (rect, y, modo),
  `DEMOLISH`, `CANCEL_JOB`. Respuesta: la cuenta ya calculada para la vista previa (`PREVIEW`, se pide al mover el mouse
  a otra columna, máx. 5 por segundo).
- `client/aerial/ManagePanel`: pestaña Construir (categorías, lista, cuenta), fantasma (contorno de la huella + base
  verde/roja, como hoy se dibujan las zonas), tramos de muralla, rectángulo de terreno.
- Capilla: edificio 9×9 (piedra, vitrales, campana no: un atril); efecto: +popularidad en `Popularity.factors`
  (lo que H21 dejó pendiente).
- Panel K → Mejoras: sin botón de encargar (lista, estado y obra en curso). `Panel.build` se saca.
- `Popularity`: + factor capilla.

## Estado

- **H22a hecho (a probar)**: pestaña Construir (Edificios / Defensa / Otros), Plantar con vista previa verde/roja, R gira,
  frente marcado, terrenos existentes en celeste; cobro en materiales (`BuildCost`, tabla en código) + esmeraldas a la mitad;
  el terreno se allana solo (piso = mediana, hasta 8 de desnivel); lo que se saca del terreno va al almacén; Cancelar obra
  devuelve lo no puesto; capilla (+2 popularidad); casas automáticas apagadas; Panel K sin "Encargar" para edificios.
- **H22b hecho (a probar)**: Construir → Terreno: Allanar / Rellenar (arrastrar; altura = la del primer click, rueda ±1;
  máx. 32×32 y 8 de desnivel por columna; casas, agua y cosas con datos se saltean) y Demoler (click en un edificio o torre
  celeste; lo que se saca, cofres incluidos, va al almacén; el terreno queda libre).
- **H22c hecho (a probar)**: Construir → Defensa: Empalizada / Muralla de piedra dibujadas (click por punto, click derecho saca
  el último, Enter encarga; hasta 32 puntos y 512 bloques; los caminos quedan abiertos para un portón), Portón de madera /
  piedra (click sobre la muralla; se abre solo para tus soldados, aldeanos y trabajadores, y se cierra con enemigos a ≤8) y
  Torre de madera / piedra (5×5; la de piedra va encima de la de madera). Lo dibujado se guarda para Reparar murallas, los
  arqueros del cuartel suben a las torres sueltas, y la empalizada/muralla/torres cuentan para la defensa y la popularidad.
  La empalizada, muralla y torres automáticas de H12 ya no se encargan en las aldeas.

## A revisar por Ale

- Tabla de materiales y precios: números a ojo (en código, `BuildCost.add`; un yunque = 8 de hierro). Los troncos de
  las paredes cuestan 1 tronco cada uno: el granero (paredes de troncos) sale ~100 troncos.
- Hecho después: las zonas de trabajo impiden plantar encima; Demoler saca un tramo de muralla (lo dibujado a 4 bloques
  del click, sin las torres).
- Los portones son puertas de cerco que se abren solas: la malla de navegación de H17 no las cuenta como puertas, así que
  los invasores las tienen que romper o trepar (y tus soldados de a pie las usan cuando se abren al acercarse).
- Esmeraldas: empalizada 1 cada 8 bloques (mín. 2), muralla 1 cada 4; portón 4 / 8; torre 6 / 12; terreno 1 cada 64 bloques.

## Pruebas (GameTests H22)

- Plantar una taberna en un lugar elegido y girada: aparecen los bloques donde y como corresponde; se cobró madera/piedra.
- Sin materiales no se encarga y avisa qué falta.
- Muralla de 3 tramos: una obra; `WallRepair` encuentra un bloque roto de un tramo dibujado.
- Allanar una loma: queda plano, el almacén tiene la tierra cortada; Rellenar un pozo gasta tierra.
- Demoler devuelve la mitad. Cancelar devuelve lo no puesto.

## Viabilidad

**Alta.** Lo pesado ya existe: obras bloque a bloque con constructor, edificios en código, almacén de ítems,
rectángulos y territorio en la vista aérea. Lo nuevo es el lugar elegido, el giro, pagar con materiales y el terreno.

Riesgos:
- **Murallas dibujadas y los asedios**: los asedios caminan por bloques reales (malla de H17), así que andan solos;
  lo que hay que rehacer es lo que hoy asume "un anillo": `WallRepair`, puestos de torre del cuartel, popularidad.
- **Fantasma en el cliente**: un contorno + base coloreada es fácil; ver el edificio entero en transparente es mucho
  más trabajo (lo dejo afuera; a revisar).
- **Allanar mucho terreno**: 32×32×8 = 8.000 bloques por obra, aguanta bien; más grande, se parte en varias.

Tamaño: parecido a H16. Se puede partir en **H22a** edificios elegidos + materiales + capilla, **H22b** terreno,
**H22c** murallas dibujadas + torres + portones.
