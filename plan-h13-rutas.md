# H13 — Rutas y viaje (plan)

> Estado (2026-10-13): hecho completo (pasos 1 a 5) con 8 GameTests nuevos (70/70 con el resto). Falta que Ale
> lo pruebe jugando.

> Respuestas de Ale (2026-10-12):
> 1. Sin camino construido se viaja igual, pero más lento: la mitad que a pie. Con camino (H14), un cuarto.
> 2. Durante el viaje quedás quieto e intocable; al terminar aparecés con los que te siguen. Esc cancela.
> 3. Envíos también a aldeas y castillos (solo soldados: no tienen almacén hasta H10).
> 4. Rutas encadenadas: de A a C pasando por B, sumando los tramos.

## Qué pide el documento de diseño

"Solo se viaja rápido por rutas construidas; a cualquier otro lado se va a pie. **Ley: no se puede trazar
una ruta por terreno sin explorar.** 1. Trazar: en el mapa de Recruits, con su herramienta de rutas. Los dos
extremos tienen que ser tuyos (Campamento, aldea o castillo). 3. Viajar: se elige el destino, pantalla de
'viajando...' con una barra. **Envíos.** Tropas y recursos se mandan entre dos puntos unidos por una ruta.
Viajan 'en camino' y llegan pasado el tiempo del viaje."

Construir el camino (grava, puentes, túneles, muelles) es H14. Acá la ruta ya tiene el dato
`road` (siempre falso por ahora) para que H14 solo tenga que prenderlo.

## Lo que ya resuelve Recruits

El mapa (tecla M) tiene una herramienta de rutas: se dibujan puntos y se guardan **en la compu del
jugador** (`ClientManager.getRoutesList()`, archivos en la carpeta del juego). El servidor no las conoce.
Por eso hay que "registrar" la ruta: el cliente manda sus puntos y el servidor la valida y la guarda.

## 1. Puntos tuyos

Campamentos (`CampRegistry`) + aldeas y castillos conquistados (`VillaagreggeData`, `owner` = vos).
Un extremo de ruta "toca" un punto si está a ≤ 32 bloques (`Routes.ENDPOINT_RADIUS`).
Si un punto deja de ser tuyo (Campamento roto, aldea perdida), sus rutas quedan inactivas (no se borran).

## 2. Registrar una ruta (pantalla Rutas)

- Lista tus rutas del mapa de Recruits; cada una dice qué une y si ya está registrada.
- **Registrar**: el servidor revisa que
  - el primer y el último punto toquen dos puntos tuyos distintos,
  - **todo el trazado esté explorado**: cada chunk por el que pasa ya fue generado (los chunks se generan
    solo cuando un jugador anda cerca). Si no, avisa en qué coordenada no se exploró.
- Se guarda en `route/RouteData` (por dimensión): id (el mismo de Recruits, re-registrar la reemplaza),
  dueño, nombre, puntos, extremos, largo en bloques, `road`.
- **Borrar** la saca de las tuyas (no toca la de Recruits).

## 3. Viajar

- Desde la pestaña **Rutas** del Campamento, o con la tecla **J** / `/warband rutas` estando a ≤ 32 bloques de
  un punto tuyo.
- Destinos: todos los puntos conectados por rutas activas (encadenadas, el camino más rápido).
- Tiempo = largo / 4,3 bloques por segundo (caminar) × 0,5 sin camino (× 0,25 con camino, H14).
  Ej.: 600 bloques sin camino ≈ 70 s.
- Mientras dura: pantalla "Viajando a X..." con barra. Quieto (si te movés más de 2 bloques se corta) y sin
  recibir daño. Esc = cancelar. Desconectarse cancela.
- Al llegar: aparecés al lado del destino, con tu caballo si ibas montado y con los soldados tuyos que te
  seguían a ≤ 32 bloques (con sus caballos).

## 4. Envíos (pestaña Envíos del Campamento)

- Origen: el Campamento donde estás. Destino: cualquier punto conectado (se elige con ◀ ▶).
- Qué se manda:
  - **Soldados del barracón**: por fila (clase y nivel), con − / +.
  - **Ítems**: una caja de carga de 18 espacios en la misma pantalla. Solo si el destino es un Campamento.
- Al mandar: las fichas salen del barracón y los ítems de la caja; quedan "en camino" en `RouteData`
  con hora de llegada (reloj del mundo). Lo que quede en la caja al cerrar vuelve a tu inventario.
- Capacidad: no se puede mandar más de lo que entra en el barracón del destino (contando lo que ya va en camino).
- Al llegar (revisión cada 1 s):
  - **a un Campamento**: los soldados entran a su barracón (atados a él); los ítems entran a su almacén
    cuando el Campamento esté cargado (lo que no entra va al más cercano, como el botín).
  - **a una aldea o castillo**: los soldados quedan **de guardia** ahí, repartidos alrededor del centro.
    Se anotan como guardias en el barracón del Campamento más cercano a esa aldea (de ahí comen), así que
    aparecen solos cuando pasás cerca (lo de H8) y se guardan solos cuando te alejás.
  - Si el destino ya no es tuyo: todo va a tu Campamento más cercano.
- Aviso por chat al llegar: "Llegó un envío a Fuerte del Río: 6 soldados, 4 stacks."
- Lo que va en camino también se ve en la pestaña Envíos (destino y cuánto falta).

## Fuera de este hito

- Construir el camino (H14) y que el viaje rápido pida camino terminado.
- Emboscadas en el camino (H15).
- Mandar recursos a aldeas (no tienen almacén hasta H10).

## Pruebas (H13GameTests)

1. Registrar: extremos que no tocan puntos tuyos → rechazada; uno solo → rechazada; dos → aceptada.
2. Terreno sin explorar (un punto a 100 000 bloques) → rechazada.
3. Encadenadas: A–B y B–C → C alcanzable desde A, tiempo = suma; ruta con un extremo perdido no cuenta.
4. Envío de soldados a un Campamento: salen del barracón, llegan al otro después del tiempo, atados a él.
5. Envío de ítems: llegan al almacén del destino.
6. Envío a una aldea conquistada: quedan como guardias con puesto cerca del centro, en el barracón más cercano.
7. Capacidad: no se puede mandar más de lo que entra.
8. Viajar: el jugador y el soldado que lo sigue aparecen en el destino; el que estaba quieto no.

## Orden de trabajo

1. `RouteData` + puntos + registrar/validar + grafo (pruebas 1-3).
2. Envíos (pruebas 4-7).
3. Viaje (prueba 8).
4. Pantallas: Rutas (registrar, borrar, viajar), Viajando, Envíos; tecla J y `/warband rutas`.
5. CLAUDE.md, documento de diseño, compilar.
