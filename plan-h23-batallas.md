# H23 — Meterse en batallas, asediar con un botón, la Hueste y el tamaño de batalla

Pedido de Ale (2026-10-14). Lo que no dijo lo decidí yo: queda marcado **(a revisar)**.

## 1. Batallas entre IA: meterse con un botón

- **Aviso con tiempo**: cuando a una marcha le faltan `AiWars.WARN_TICKS` (2 min) para llegar, los jugadores a
  ≤`WARN_RANGE` (400) de la aldea reciben el aviso con coordenadas y "estate a ≤100 para la batalla".
- Al empezar la batalla real, a cada jugador a ≤`BATTLE_NEWS_RANGE` le aparece una pantalla (`ChoiceScreen`):
  **Ayudar a <atacante>** · **Ayudar a <defensor>** · **No involucrarse**. Al que llega después, le aparece una vez.
  La tecla **B** la vuelve a abrir.
- Ayudar (`AiWars.join`): −`JOIN_OPINION` (15) con el bando contrario (una vez). Tus soldados a ≤`JOIN_RADIUS` (64)
  quedan marcados (`warbandcraft:ai_join`) y pelean solos contra el otro bando (cada segundo, el enemigo más cercano);
  el otro bando los ataca a ellos y a vos (`GarrisonDefense.isHostile`). Tus ejércitos de H20 los cuentan como enemigos.
  Pegarle a un soldado sin elegir = elegir el otro bando (como antes).
- Si gana el bando que ayudaste: +10 de opinión y **botín**: `LOOT_BASE` (4) + `LOOT_PER_KILL` (1) esmeraldas por
  enemigo caído (a tu inventario), y si tomaron la aldea, la mitad de su almacén a tu Campamento más cercano **(a revisar)**.
  Al terminar, tus soldados vuelven a la orden que tenían.

## 2. Asediar con un botón

- En una aldea/castillo de facción, el cartelito dice "[B] Asediar". La tecla **B** abre la confirmación (nombre,
  guarnición, tus soldados cerca, cuánto baja la opinión). **Asediar** = `RaidManager.start` + orden Batalla a tus
  soldados a ≤64 (los que ya están en un ataque tuyo también). Si el ataque ya está en curso, B suma a los de cerca.
- También `/warband asediar`.

## 3. La Hueste (llamado a la batalla de campo)

- Cada facción junta **hombres para la hueste** (`InvasionData.hostPool`): `POOL_PER_SETTLEMENT` (6) por día por
  aldea/castillo, tope `POOL_CAP` (150).
- **Cada día**, por cada facción en guerra con vos (y con aldeas tuyas), sin hueste activa contra vos y sin enfriamiento:
  chance = `CHANCE_BASE` (10%) + `CHANCE_HATE` (30%) × odio (opinión −40 → 0, −100 → 1). Más te odian, más seguido
  vienen (estilo Total War: la "postura" de la IA). **(a revisar)**
- **Tamaño** (estilo Bannerlord: la IA arma el ejército para ganarle al tuyo): tu fuerza = soldados en tus barracones +
  cuarteles + sueltos. Hueste = tu fuerza × `STRENGTH_RATIO` (1,3) × azar ±15 %, entre `MIN_SIZE` (30) y `MAX_SIZE` (100),
  y nunca más que lo que juntó. Si no junta 30, no sale.
- Acampa cerca de la aldea tuya más cercana a sus tierras: un **campo de batalla válido** (el mismo control que Invocar:
  bioma, plano, sin agua, a >64 de aldeas) a 90-180 bloques, del lado de sus tierras primero.
- Aviso al dueño (y al entrar): facción, hombres, coordenadas, **3 días** (`DEADLINE_DAYS`). Se ve en el mapa M (rombo
  quieto), en el Panel K (fila de la aldea en rojo) y en el cartelito. Cada día te recuerda cuánto falta.
- **Presentar batalla**: invocar un ejército tuyo a ≤`ARMY_RANGE` (96) del campamento (cuando llega, empieza) o
  acercarte vos a ≤`TRIGGER_DISTANCE` (24). La hueste aparece formada a `GAP` (40) bloques, mirándote; 20 s formada (o
  hasta que la tocás) y carga. Arqueros detrás, caballería en los flancos.
- **Tamaño de batalla** (`battle/BattleSize.MAX_PER_SIDE`, 60, en el archivo de configuración): en el campo hay como
  mucho tantos de cada lado; el resto entra en oleadas por la retaguardia a medida que caen. Vale para la hueste, para
  tus ejércitos de H20 y para las invasiones grandes (`Assault`).
- **Gana**: te quedan como máx. 30% → se desbanda (huyen y se van). Botín `LOOT_PER_SOLDIER` (1) esmeralda por hombre
  al Campamento más cercano, +40 XP a tus soldados cerca, la facción se cansa de la guerra (+20 de opinión), 7 días sin
  hueste. **(a revisar)**
- **Te vas** (a >160 del campo 30 s, o se descarga): lo que queda de la hueste marcha contra la aldea.
- **No vas en 3 días**: la hueste entera marcha contra la aldea (invasión con general, 2 min de cuenta). Si estás, se
  pelea (con oleadas); si no, autoresolución con esos números.

## 4. Probar

- `/warband prueba hueste [cantidad]`: guerra con la facción más cercana y una hueste acampada 50 bloques adelante tuyo.
- `/warband prueba rendimiento [cantidad]`: tus soldados gratis + una hueste que ya pelea; medidor de rendimiento.
- `/warband prueba medir`: prende/apaga el medidor (ms por tick, TPS, soldados cargados) arriba de la barra.
- `/warband prueba guerra`: como antes; ahora aparece la pantalla para meterse.
- GameTests: `H23GameTests`.

## Cambios después de probar (pedido de Ale, 2026-10-14)

- La hueste acampa más lejos: el punto de reunión a 220-340 bloques de la aldea y sus carpas 40 más allá.
- En el mapa M: un círculo amarillo "Invocá tu ejército acá" (48 bloques) en el punto de reunión, y el rombo en las carpas.
- Empieza **solo** si llegás con un ejército invocado ahí. Vos solo o con soldados que te siguen, no.
- Botín de toda pelea contra soldados de la IA (`battle/BattleLoot`): armaduras, armas, arcos, ballestas, escudos y flechas
  de los caídos + 1 de armamento y media esmeralda por caído, al Campamento, aldea o castillo más cercano cuando termina
  (20 s sin bajas, o al ganar). **(a revisar)**
- Batallas más largas: entre soldados se pegan la mitad (`BattleSize.NPC_DAMAGE` 0,5); la hueste se desbanda con 20%.
