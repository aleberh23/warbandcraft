# H21 — Crecimiento de aldeas (estilo Stronghold)

Pedido de Ale (2026-10-13): "que la aldea cumpla ciertas condiciones hace que vengan más aldeanos. Estos aldeanos se
tienen que poder convertir en workers." Solo plan: no hay nada hecho todavía.

Idea de Stronghold: cada aldea tuya tiene una **popularidad** (0 a 100). Con 50 o más llegan aldeanos nuevos si hay
cama; con menos de 50 se van. La popularidad sale de varias cosas que el jugador maneja (comida, impuestos, casas,
seguridad...). Los que llegan esperan en la campana (la "fogata" de Stronghold) hasta que les das un oficio.

Vale para aldeas conquistadas y fundadas (castillos no: tienen guarnición, no pueblo; a revisar).

## 1. Popularidad (`village/Popularity`)

Se guarda por aldea (`TownData.Town.popularity`, empieza en 50). Cada **1 minuto de juego** se calcula el **cambio por
minuto** sumando los factores y se aplica (tope 0..100). Así se mueve de a poco y se ve venir, como en Stronghold.

| Factor | Cómo se mide | Aporte por minuto |
|---|---|---|
| Raciones | elección del jugador (ver 2) | −4 / −2 / 0 / +2 / +4 |
| Variedad de comida | tipos distintos de comida en el almacén (pan, papa, carne, pescado...) | +1 por tipo después del primero, máx. +3 |
| Impuestos | elección del jugador (ver 3) | +2 / 0 / −2 / −4 / −6 |
| Casas | camas libres (de `Housing`) | 0 libres: −1 · 1-3: +1 · 4+: +2 |
| Seguridad | guardias (barracón + cuartel) por cada 10 aldeanos | 0: −2 · 1: 0 · 2+: +1; muralla: +1 |
| Peligro | invasión en marcha o pelea en la aldea | −3 mientras dure |
| Saqueo / derrota reciente | 3 días después de una invasión perdida a medias o un saqueo | −2 |
| Taberna (H19) | taberna con tabernero y comida | +1 / +2 / +3 por nivel |
| Mercado (H12) | mejora hecha | +1 |
| Capilla (nueva mejora, opcional) | edificio de H12 | +2 |

Todos los números son constantes en `Popularity` (a revisar con Ale después de probar).

## 2. Raciones (la comida que comen los aldeanos)

Hoy los aldeanos no comen nada del almacén. Nuevo: cada día cada aldeano y trabajador come del almacén de la aldea (lo
más barato primero, como el barracón: `Barracks.takeFood`).

| Ración | Puntos de comida por aldeano y día | Popularidad |
|---|---|---|
| Ninguna | 0 | −4 |
| Media | 2 | −2 |
| Normal (de entrada) | 4 | 0 |
| Abundante | 6 | +2 |
| Doble | 8 | +4 |

Si no alcanza la comida para la ración elegida, cuenta como "Ninguna" ese día y avisa en el chat.

## 3. Impuestos

Hoy el impuesto es fijo (`VillageEconomy.taxOf`: base + 1 cada 2 aldeanos). Nuevo: el jugador elige el nivel.

| Impuesto | Esmeraldas | Popularidad |
|---|---|---|
| Sin impuestos | ×0 | +2 |
| Bajos | ×0,5 | 0 |
| Normales (de entrada) | ×1 | −2 |
| Altos | ×1,5 | −4 |
| Abusivos | ×2 | −6 |

Con "Normales" y raciones "Normal" la aldea queda un poco en contra (−2), así que hace falta algo más (casas libres,
variedad de comida, taberna) para que crezca. Esa es la gracia.

## 4. Llegan y se van

- **Tope de población** por tamaño de aldea (H12): aldea 12 · Pueblo 24 · Ciudad 48 (aldeanos + trabajadores).
- **Llegan**: popularidad ≥ 50, hay al menos 1 cama libre y está por debajo del tope → llega 1 aldeano cada
  5 min con popularidad 50-69, cada 3 min con 70-89, cada 2 min con 90+. Aparece en el borde del territorio (el lado
  del camino más cercano si hay uno) y camina hasta la campana. Mensaje: "Llegó un aldeano a <aldea> (12/24)".
- **Se van**: popularidad < 50 → se va 1 cada 5 min (con < 25, cada 2 min). Primero los libres, después los
  trabajadores sin zona, nunca los que tienen zona mientras queden otros. Caminan hasta el borde y desaparecen.
- **Aldea descargada**: se calcula todo como números (perezoso, como el Herrero): popularidad, comida y llegadas
  pendientes. Los que llegaron "en papel" aparecen en la campana cuando se carga.
- Los bebés de vanilla siguen naciendo (si hay camas), pero cuentan para el tope; al llegar al tope no llega nadie.

## 5. Los nuevos se convierten en trabajadores

Esto ya existe en parte (modo Gestión → Aldeanos → Asignar, H16). Cambios:

- Los que llegan vienen **sin oficio** y con la marca `warbandcraft:settler`: se les saca la IA de vanilla que busca
  puesto de trabajo, así no se vuelven bibliotecarios solos y quedan "libres" para vos.
- **Asignar** acepta también a los aldeanos con oficio de vanilla (hoy solo los libres); estos aparecen abajo, con su
  oficio, y se avisa que pierden sus tratos.
- **Auto-asignar** (botón SÍ/NO en Gestión → Aldeanos, apagado de entrada): si hay una zona de trabajo sin
  trabajador (se le murió o se fue), el primer aldeano libre la toma solo.
- Pestaña nueva **Pueblo** en el modo Gestión (y una columna en el Panel K → Aldeas):
  - barra de popularidad con la flecha de hacia dónde va (+3/min, −1/min);
  - la lista de factores con su número, en verde/rojo, como el informe de Stronghold;
  - botones ◀ ▶ para Raciones e Impuestos, con lo que cuesta/da por día;
  - población: aldeanos / trabajadores / libres / tope, y "próximo en llegar: 2:30".

## 6. Dónde va en el código

- `village/Popularity`: factores, tick por minuto (desde `VillageStorehouse.tick` o un tick propio en `RaidEvents`, no en
  `GameTestServer`; las pruebas llaman a mano), llegadas e idas.
- `TownData.Town`: `popularity`, `rations`, `taxLevel`, `nextArrivalTick`, `pendingSettlers`.
- `VillageEconomy.taxOf` multiplica por el nivel de impuestos; la comida de los aldeanos sale en `VillageEconomy.checkNewDay`.
- `VillageManagement`: pestaña Pueblo en `Data`, nuevas acciones `RATIONS`, `TAXES`, `AUTO_ASSIGN` en `ManageActionMessage`.
- `Panel.VillageInfo`: popularidad y población.

## 7. Pruebas (GameTests)

- Raciones Doble + sin impuestos + camas libres → la popularidad sube; sin comida y Abusivos → baja.
- Popularidad 60 con 2 camas libres → a los 5 min llegó 1 aldeano libre con la marca; en el tope, no llega nadie.
- Popularidad 30 → se va primero un libre, no un trabajador con zona.
- Un aldeano que llegó no toma oficio solo aunque haya un atril libre; Asignar lo convierte en trabajador.
- Auto-asignar: zona de granjero sin trabajador + 1 libre → se lo asigna solo.

## A revisar por Ale

- Todos los números (factores, raciones, impuestos, topes, cada cuánto llegan).
- ¿Castillos también crecen? (por ahora no).
- ¿La Capilla va como mejora nueva o la dejamos afuera?
- ¿Dónde va en el orden? Está pensado después de H19 (la taberna suma popularidad) y H20, pero puede ir antes: sin
  taberna, ese factor queda en 0 hasta que exista.

## Hecho (2026-10-13, a probar)

Popularidad, factores, raciones, impuestos, llegan/se van, recién llegados sin oficio, pestaña Pueblo. La capilla vino con H22.

Después, misma noche (a probar): crecer con la aldea descargada ("en papel" con lo último medido; al cargarse llegan o se
van de verdad), ataque reciente (−2 por 3 días después de rechazar una invasión; tus aldeas no se saquean), Asignar a
aldeanos con oficio de vanilla (pierden sus tratos), Auto-asignar a zonas vacías (botón en Gestión → Aldeanos, de entrada
sí) y la popularidad en el Panel K.
