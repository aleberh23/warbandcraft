# H9 — Batallas (plan)

> Estado (2026-10-13): hecho, con 12 GameTests nuevos (83/83 con el resto). Falta que Ale lo pruebe jugando.
> Pedido de Ale después: en aldeas y bosques no importa que se separen, que busquen y ataquen; si quedan
> encerrados, el zapador pica, el resto busca la puerta y si no, teletransporte afuera (`battle/BattleEscape`).
> Y los defensores de un castillo se quedan adentro defendiendo (no salen a buscar a nadie).

> Respuestas de Ale (2026-10-13):
> 1. Alcance: batallas reales primero. IA táctica en los ataques que ya existen (aldeas y castillos),
>    prisioneros y guardar los ataques a medio terminar. La autoresolución y que la IA te conquiste pasan
>    al H15, junto con las invasiones (sin invasiones no hay batallas sin vos).
> 2. Tus tropas: los enemigos siempre pelean con táctica; los tuyos solo si les das la orden "Batalla".
> 3. Prisioneros: los dos tipos. Los que se rinden heridos y los defensores que quedan vivos al final.

## Qué pide el documento de diseño

"IA táctica en tres capas, como Bannerlord: **Ejército** (el plan: atacar, aguantar, retirarse según quién
tiene más fuerza), **Formación** (infantería en línea, arqueros atrás, caballería por el flanco, reagruparse),
**Soldado** (pegar, cubrirse, disparar: Recruits). Aldeas: el atacante deja una reserva. El defensor pone
arqueros en las torres. **Prisioneros**: los sobrevivientes del perdedor van a los barracones del ganador. Los
tuyos capturados pueden terminar en las filas enemigas, con su nivel. Reemplaza lo de H4. **Guardado**: si
cerrás el juego en medio de una batalla, al volver sigue donde estaba."

## Lo que ya resuelve Recruits

- La capa Soldado entera: pegar, escudo, disparar (los arqueros tiran hasta ~60 bloques si tienen objetivo).
- `NPCArmy`/`PatrolLeaderAttackController` es un general para sus líderes de patrulla, pero no se puede copiar
  (licencia) y está atado a su entidad líder. Armamos el nuestro (`battle/BattleGeneral`), que solo da órdenes
  con lo de siempre: `setHoldPos` + estado 3 (formarse), estado 0 + `setTarget` (pelear), `setShouldBlock`.

## 1. El general (`battle/BattleGeneral`, cada 1 s por ataque en curso)

Dos bandos: **defensores** (la guarnición, siempre) y **atacantes** (tus soldados con la marca
`warbandcraft:battle`, a ≤96 bloques de la aldea). Cada soldado se clasifica: caballería (montado), a distancia
(arco/ballesta), infantería (el resto); capitán aparte.

**Ejército (el plan).** Fuerza de un soldado = vida × (1 + armadura/10) × (1 + 0,15 × nivel), ×1,5 a caballo;
el jugador cuenta como 2 soldados. Con la relación de fuerzas:
- Defensor: > 1,8 = **Cargar** (todos a pelear) · 0,6–1,8 = **Línea** a 12 bloques del centro hacia el enemigo ·
  < 0,6 = **Replegarse** (línea apretada en el centro).
- Atacante: > 1,8 = **Cargar** · si no = **Avanzar** (la línea camina hacia el enemigo) · < 0,4 = **Aguantar**
  (se quedan donde están, escudos arriba, arqueros tirando).

**Formación.**
- Infantería: línea de 2 filas perpendicular al enemigo, 1,75 bloques entre soldados; escudos arriba mientras se
  forma. Un soldado con un enemigo a ≤8 bloques sale a pelear (y no se lo vuelve a formar mientras tenga
  enemigos a ≤16).
- A distancia: línea 6 bloques detrás de la infantería, quietos, disparando al enemigo más cercano.
- Caballería: va a un flanco (18 bloques al costado del enemigo) y de ahí carga contra los arqueros enemigos
  (si no hay, al más cercano). Si se demora más de 15 s en llegar, carga igual.
- Reserva (solo atacante, con ≥5 infantes): el 20% de la infantería espera detrás de los arqueros y entra cuando
  el plan es Cargar o cuando la infantería de adelante quedó en menos del doble de la reserva.
- Castillo: los arqueros defensores suben a lo más alto del castillo (muralla, torres), repartidos.
- Capitán: se queda en el centro, pelea solo si alguien llega a ≤6 bloques.

**Tu orden "Batalla"** (botón Batalla en la vista aérea, o `/warband batalla`): marca a los elegidos (sin elegidos,
a todos los tuyos a ≤64 bloques). Se puede dar antes del ataque: arrancan solos cuando empieza. Se apaga al
terminar el ataque (vuelven a seguirte), al darles cualquier otra orden de la vista aérea, o si el general nota
que alguien los movió (otra orden de Recruits): cada orden que da el general se anota y si el soldado ya no
está en eso, sale de la Batalla.

## 2. Rendirse (`battle/Surrender`)

- Durante un ataque, un soldado (de la guarnición o tuyo cerca del ataque) que recibe un golpe mortal puede
  rendirse en vez de morir: chance 45% − 7% por nivel (mínimo 10%). El capitán no se rinde.
- Rendido: queda agachado, quieto (velocidad 0), con poca vida, invulnerable, sin pelear y nadie lo ataca.
  Marca `warbandcraft:surrendered` (centro del ataque). No cuenta como defensor vivo.
- Si **ganás**: los enemigos rendidos + los defensores que quedan vivos (castillo con capitán caído) van como
  **prisioneros** al barracón de tu Campamento más cercano. Tus rendidos se levantan.
- Si **perdés** (abandonás el ataque): tus rendidos se pasan al enemigo: quedan como guarnición de esa aldea, con
  su nivel y su equipo (teñido con los colores de la facción). Los enemigos rendidos se levantan.
- Sin Campamento, o con la prisión llena: los que no entran quedan libres ahí (como en H4: se pueden contratar).
- Un rendido que estaba en un chunk sin cargar cuando terminó el ataque se levanta solo al cargarse.

## 3. Prisioneros en el barracón

- Lista aparte en el barracón del Campamento (no comen, no ocupan lugar de soldado). Máximo 40 por Campamento.
- En la pestaña Barracón, debajo de los soldados: una fila por tipo (nombre + nivel) con
  **Reclutar** (pasa a ser soldado tuyo guardado en el barracón, con su clase y nivel; necesita lugar) y
  **Rescate** (lo devolvés a su facción: esmeraldas al Campamento, 3 + 2 por nivel, el capitán 30; +3 de opinión).
- Los soldados de guarnición se marcan al aparecer con su clase y nivel de kit (`warbandcraft:troop_kit`,
  `warbandcraft:troop_tier`) para que el prisionero reclutado siga siendo Hachero/Arquero/etc.

## 4. Guardado

- Los ataques se guardan (`raid/RaidData`, en el overworld): aldea, facción, atacante, caídos, equipo de los
  caídos, rendidos y estado. La barra de asalto se rehace al cargar.
- Un ataque ganado sin decidir (Saquear/Conquistar) vuelve a abrir la pantalla al entrar el jugador.
- Los rendidos son entidades del mundo: se guardan solos; al cargarse se les vuelve a poner la quietud.
- Lo que no se guarda: las posiciones que eligió el general (las recalcula cada segundo).

## Fuera de este hito

- Autoresolución, invasiones, que la IA te conquiste, batallas entre facciones de la IA → H15.
- Reclutar prisioneros desde el panel de control → H11 (por ahora, pestaña Barracón).
- Grupos que atacan entradas/campana/torres por separado: el atacante tiene línea + reserva + flanco.

## Pruebas (H9GameTests)

1. Golpe mortal con chance forzada → rendido (vivo, invulnerable, fuera de la guarnición viva).
2. Ganar con rendidos → prisioneros en tu barracón; tus rendidos se levantan.
3. Abandonar → tus rendidos pasan a la guarnición enemiga, sin dueño y con su nivel.
4. Reclutar un prisionero → soldado tuyo en el barracón con su clase. Rescate → esmeraldas.
5. General defensor: infantería entre el centro y el enemigo; arqueros más lejos del enemigo que la infantería.
6. Caballería atacante va a un flanco (al costado, no de frente).
7. Orden "Batalla" pisada por otra orden → el soldado sale de la Batalla.
8. Guardar y cargar un ataque (con equipo de caídos y rendidos).
