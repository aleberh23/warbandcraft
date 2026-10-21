# H14 — Caminos (plan)

> Estado (2026-10-13): hecho, 4 GameTests nuevos (107/107 con el resto). Falta que Ale lo pruebe. Ale dormía y pidió seguir solo. Decisiones de Claude siguiendo el documento de diseño,
> a revisar al probar.

## Qué pide el documento de diseño

"Construir: una cuadrilla arma el camino de a tramos, rápido: una ruta mediana queda en un par de minutos.
Camino de grava de 3 a 4 bloques de ancho que sigue el terreno sin escalones bruscos y esquiva casas. Montaña en
el medio: túnel con antorchas. Río chico: puente simple de madera. Mar: un muelle en cada costa y el cruce se hace
navegando." Con camino el viaje tarda un cuarto de lo que tardarías a pie (sin camino, la mitad; eso ya está en H13).

## Decisiones (a revisar por Ale)

1. Se encarga desde el **Panel (K) → Rutas → Camino** sobre una ruta registrada y activa.
2. Cuesta **1 esmeralda cada 10 bloques** de ruta (mínimo 5): primero de tu inventario, lo que falte de tus
   Campamentos cargados.
3. El trazado se mide al encargarlo (se cargan los chunks de la ruta una vez). Cada columna del centro del camino:
   - Piso suavizado: mediana de 5 y después nunca más de 1 bloque de diferencia entre pasos (sin escalones de 2).
   - Superficie de grava 3 de ancho (con piedra abajo si hay aire, para que no se caiga). No toca caminos de
     aldea (dirt_path), casas ni nada con datos; donde hay una casa el camino se angosta o se corta.
   - Corte: si el terreno queda hasta 2 arriba del camino, se despeja a cielo abierto.
   - **Túnel**: si queda 3 o más arriba, se cava 3×3 con techo y una antorcha cada 8 bloques.
   - **Puente**: agua de hasta 24 bloques seguidos → tablones de roble a 1 sobre el agua, barandas de cerco y
     pilotes de tronco cada 6 bloques hasta el fondo.
   - **Muelles**: agua de más de 24 → un muelle de 3×7 en cada costa con farol en la punta; el resto se cruza
     navegando (el viaje rápido igual cuenta el camino).
   - Terraplén: si el terreno queda abajo del camino, se rellena con tierra.
4. Tiempo: 5 bloques de ruta por segundo, entre 60 s y 5 min. La obra avanza por tiempo; los tramos sin cargar
   se ponen cuando se cargan (al pasar). Un aldeano constructor (como en H12) acompaña el frente de obra si está cargado.
5. Al terminar el tiempo la ruta queda "con camino" (viaje ×0,25). Volver a registrar la ruta con otro trazado le
   saca el camino (y corta la obra); con el mismo trazado lo conserva.

## Código

- `upgrade/BuilderNpc`: el aldeano constructor (sale de `Upgrades`, lo comparten obras y caminos).
- (Las obras de caminos tienen su propia clase, `RouteData.RoadJob`: se ponen por tramos cargados, no en orden estricto.)
- `route/RoadPlanner` (el trazado), `route/Roads` (encargar, avanzar, terminar), `RouteData.roads` (obras).
- Panel: pestaña Rutas con botón Camino, costo y avance.

## Pruebas (H14GameTests)

- Piso plano → grava de 3 de ancho siguiendo el trazado.
- Canal de agua → puente con barandas; pared de piedra → túnel con antorcha; escalón de 3 → sin escalones de 2.
- La obra termina y la ruta queda con camino (viaje más corto); re-registrar con otro trazado lo saca.
- Costo: sin esmeraldas no arranca.
