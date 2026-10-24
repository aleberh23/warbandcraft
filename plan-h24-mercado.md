# H24 — El Mercado: envíos programados, Lonja y vender a otros jugadores

Pedido de Ale (2026-10-14):
- "Darle una utilidad al mercado, estilo Age of Empires: me permite hacer envíos programados entre aldeas. Y el nivel 3,
  directamente acceder a los almacenes de cada una (esto no sé si es correcto)."
- "Se asignan aldeanos que trabajen en el mercado. Y otros jugadores (de otra facción) pueden venir y comprar cosas como
  armamento, comida y cualquier cosa que produzcan los aldeanos que se almacene en el almacén. Yo puedo poner un límite
  mínimo (hasta dónde se vende) de cada cosa, hablando con ese aldeano."

Hoy el Mercado (`Upgrade.MARKET`, H12) es un nivel solo: +4 esmeraldas por día (`Upgrades.MARKET_EMERALDS`) y +1 de
popularidad (`Popularity.MARKET`). Eso se queda como el nivel 1.

## 1. Tres niveles (como la Taberna)

`MARKET` sigue siendo el nivel 1 (mismo id `market`: los mundos guardados no pierden nada) + `MARKET_2`, `MARKET_3`
(`isLevelUp`, mismo terreno; plano más grande en cada nivel: más puestos, toldos, un carro). Aldeas y castillos.

| Nivel | Nombre            | Costo | Mercaderes | Envíos programados | Extra                                     |
|-------|-------------------|-------|------------|--------------------|-------------------------------------------|
| 1     | Mercado           | 16    | 1          | 2                  | vender a otros jugadores, +4 esm./día     |
| 2     | Plaza de mercado  | 32    | 2          | 5                  | envíos 25% más rápidos                    |
| 3     | Lonja             | 64    | 3          | 10                 | ver todos tus almacenes y pedir (ver 3)   |

Los envíos programados y la Lonja **no necesitan mercader**; vender a otros jugadores **sí**.

## 2. Envíos programados (`market/Schedules`)

- Se arman desde el Mercado de la aldea de origen: **origen** (esta aldea) → **destino** (otra aldea/castillo tuyo o un
  Campamento conectado por ruta, H13), **qué** (hasta 6 ítems) y **cada cuántos días** (1-7). Salen al amanecer del día
  que toca (`VillageEconomy.checkNewDay`) como un envío normal de H13 (`RouteData.Shipment`, mismo tiempo de viaje).
- Dos modos por ítem:
  - **Fijo**: mandar N cada vez.
  - **Reponer**: que el destino tenga al menos N (manda lo que falta). Sirve para tener un castillo de frontera siempre
    con comida y armamento.
- El origen nunca baja de su **mínimo** (el mismo que se pone para vender, ver 5): así un envío no vacía la aldea.
- Origen descargado: al salir se carga un momento el chunk del almacén (`level.getChunk`) y se sacan los ítems de
  verdad; si además tiene producción "en papel" (esmeraldas/comida/armamento de H10), se usa eso primero.
- Sin ruta activa ese día (se perdió un extremo) → no sale y avisa; el envío programado queda en pausa, no se borra.
- Se ven en el Panel (K) → pestaña Envíos: fila "Programado: A → B, cada 2 días, próximo mañana" con Pausar / Borrar.

## 3. Lonja (nivel 3)

Decidido con Ale: **no** hay acceso instantáneo a todos los almacenes (rompería rutas, caminos y envíos). La Lonja
facilita armar envíos:

- **Ver** todos los almacenes de tus aldeas/castillos/Campamentos en una sola pantalla (cuenta de cada ítem por lugar,
  aunque estén descargados: última cuenta, como el Panel de H11).
- **Pedir** desde ahí: elegís ítems de uno o varios almacenes y cantidades, y sale un envío normal de H13 de cada uno
  hacia esta aldea (mismo tiempo de viaje, sin comisión). Respeta el mínimo de cada origen. Necesita ruta.

## 4. El Mercader (trabajador nuevo) y vender a otros jugadores

- `worker/MerchantEntity` (`warbandcraft:merchant`, como el Herrero y el Tabernero: hereda de `AbstractWorkerEntity` de
  Workers, sin copiar código; `Job.MERCHANT`). Se asigna desde Gestión → Aldeanos; atiende en un puesto del mercado
  (`Market.workplace`, o donde se le marque con un click). Máx. = mercaderes del nivel.
- **Click derecho de otro jugador** → pantalla de comercio **de vanilla** (la de los aldeanos: `MerchantMenu` con
  ofertas armadas al momento). Se paga en esmeraldas (y para cosas caras, bloques de esmeralda). Las esmeraldas van al
  almacén de la aldea; se avisa al dueño si está conectado ("Steve compró 32 pan por 6 esmeraldas en Robledal").
- Qué se vende: **todo lo que hay en el almacén** de la aldea (Campamento + cofres vinculados) **por encima del mínimo**
  de cada ítem y con la venta prendida. Cada oferta = un lote (p. ej. 16 pan, 4 armamento) a su precio; el stock de la
  oferta = cuántos lotes hay por encima del mínimo.
- Precios: tabla en `data/warbandcraft/warband_prices/*.json` (pan, carne, trigo, armamento, lingotes, carbón, madera,
  piedra...; lo que no está, no se vende salvo que el dueño le ponga precio). El nivel del mercado da más ofertas
  visibles a la vez (1: 8, 2: 16, 3: 24).
- **Quién puede comprar**: jugadores que no son de tu facción de Recruits. Los que están **en guerra** con vos
  (diplomacia de Recruits) no: el mercader dice "No le vendo al enemigo". Los de tu facción usan el almacén directamente.
  Sin mercader trabajando (lejos del puesto, durmiendo, de noche) no hay venta.
- **Hablar con el mercader siendo el dueño** → pantalla **Mercado** (nuestra), con pestañas:
  - **Ventas**: lista de lo que hay en el almacén: ítem · cuánto hay · **mínimo** (◀ ▶, +/− 1/16/64) · precio por lote
    (◀ ▶) · Vender SÍ/NO. Abajo: últimas 20 ventas (quién, qué, cuánto).
  - **Envíos**: los programados de esta aldea (crear, editar, pausar, borrar).
  - **Lonja** (nivel 3).
  La misma pantalla se abre desde Gestión (V) → pestaña nueva **Mercado**, para no tener que caminar hasta el puesto.

## 5. Guardado y piezas

- `market/MarketData` (SavedData por dimensión, por centro de aldea): reglas de venta (`ítem → {mínimo, precio, sí/no}`),
  envíos programados, ventas recientes. Se borra si la aldea cambia de dueño (los envíos
  programados que la tienen como destino quedan en pausa).
- `market/Market` (ofertas, venta, quién puede comprar), `market/Schedules` (salidas diarias, modo reponer),
  `market/Exchange` (Lonja: cuentas por almacén, pedidos).
- Red: `OpenMarketMessage` (servidor → cliente: datos de la pantalla Mercado), `MarketActionMessage` (cliente →
  servidor: mínimo, precio, sí/no, envío programado, pedido de la Lonja). Pantalla `client/market/MarketScreen`.
- Números en la configuración (`Tuning`): lotes, ofertas por nivel, envíos por nivel, rapidez del nivel 2.

## Pruebas (GameTests `H24GameTests`)

- Venta: almacén con 100 pan, mínimo 40 → la oferta tiene (100−40)/16 = 3 lotes; comprar uno deja 84 pan y suma las
  esmeraldas al almacén; con 40 pan no hay oferta.
- Comprador en guerra → no hay ofertas. Comprador de tu facción → tampoco (usa el almacén).
- Programado fijo (cada 1 día, 16 pan) → al día siguiente sale un envío y respeta el mínimo del origen.
- Programado "reponer hasta 64" con 50 en destino → manda 14.
- Lonja: pedir 20 pan de otra aldea → sale un envío normal hacia la Lonja y el origen no baja de su mínimo.

## Decidido con Ale (2026-10-14)

- Nivel 2: **sin** esmeraldas por comercio entre mercados (no da nada extra más que envíos más rápidos y más cupos).
- Nivel 3: la Lonja muestra lo de cada almacén y se pide desde ahí; envío normal, nada instantáneo.

## A revisar por Ale

1. ¿Los de tu propia facción (aliados en multijugador) pueden comprar, o solo los de otras facciones? (por ahora no)
2. Precios y tamaño de los lotes (tabla en JSON, fácil de cambiar después).
3. ¿Los otros jugadores también pueden **venderte** cosas? (por ahora no)

## Hecho (2026-10-14, a probar)

Todo lo de arriba. Decidido además (a revisar): el mínimo de partida de cada cosa es 32 y se vende salvo que lo apagues;
un Campamento común no tiene mínimo (la Lonja puede pedir todo lo que tenga); "Mandar ahora" en cada envío programado.
Falta: mostrar los envíos programados en el Panel (K) → Envíos (por ahora se ven solo en la pantalla Mercado).
Pruebas: `MarketGameTests` (vender lo que sobra, envíos programados fijo/reponer/mínimo/día, Lonja y Plaza más rápida).
