# H24 — Tácticas de la IA en batalla (estilo Bannerlord) — HECHO, a probar

Pedido de Ale (2026-10-14): que la IA pelee con tácticas, sobre todo en campo abierto. Esto es un plan para que Ale lo
revise antes de programar nada.

## Cómo lo hace Bannerlord (lo que copiamos en idea, no en código)

Bannerlord separa la IA de batalla en tres capas:

1. **El general del bando (Team AI)** elige una **táctica** para todo el ejército y la vuelve a pensar cada pocos
   segundos. Las tácticas tienen un "peso" según la situación (quién es más fuerte, qué tropas tiene cada uno, el terreno)
   y gana la de más peso. Para que no cambie de idea a cada rato, la táctica actual tiene un plus (histéresis).
   Ejemplos: Ataque total, Línea defensiva, Hostigar con arqueros, Carga de caballería de frente, Retirada ordenada,
   Defender una colina.
2. **Cada formación** (infantería, arqueros, caballería, arqueros a caballo) recibe un **comportamiento** de la táctica:
   Avanzar, Cargar, Mantener la línea, Hostigar a distancia, Flanquear, Proteger el flanco, Cubrir a los arqueros,
   Reserva, Retirarse. El comportamiento decide adónde va la formación, cómo se ordena (línea, muro de escudos,
   suelta, cuadro, columna) y cuándo se larga a pelear.
3. **Cada soldado** sigue a su formación: va a su lugar, mantiene la distancia con el de al lado y, cuando hay enemigos
   cerca, pelea con el que tiene adelante. La **moral** de cada uno baja con las bajas cercanas, si lo atacan por el
   flanco o por detrás, o si cae el general; con poca moral huye.

## Lo que ya tenemos y se aprovecha

- `Armies.layout` (bloques de infantería, arqueros atrás, caballería en los flancos) y la Hueste que forma y avanza.
- `BattleGeneral` (ataques a aldeas: plan Cargar / Línea / Replegarse según la relación de fuerzas, roles, reserva).
- `Assault` (moral por soldado, el general que sube la moral, la retirada) y `SiegeLanes` (asedios: quedan como están).
- `BattleSize` (oleadas), `BattleLoot` (botín), la malla de navegación (`nav/`, solo de a pie).

## Cómo lo haría (por etapas, cada una jugable)

### H24a — Formaciones de verdad (la base)

- Clase nueva `battle/tactics/Formation`: sus soldados, su tipo (infantería, arqueros, caballería), su orden (centro +
  hacia dónde mira), su **disposición** (línea, muro de escudos —solo con escudos—, suelta, columna, cuadro) y su
  comportamiento actual.
- Movimiento en bloque: la formación avanza a la velocidad del más lento y espera a los rezagados (no se desarma al
  caminar). Gira sobre su centro.
- La usan la Hueste, tus ejércitos de H20 (Mantener / Avanzar ya pasan a ser órdenes a formaciones) y las batallas de
  campo entre facciones.

### H24b — Comportamientos de cada formación

| Formación | Comportamientos |
| --- | --- |
| Infantería | Avanzar en línea · Mantener la línea · Muro de escudos contra arqueros · Cargar cuando está cerca · Cubrir a los arqueros |
| Arqueros | Hostigar: tirar desde ~30 bloques y retroceder si la infantería enemiga se acerca · Ponerse detrás de su infantería · Buscar altura |
| Caballería | Flanquear: dar la vuelta por un costado y pegar por detrás · Cazar arqueros enemigos · Proteger el flanco propio · Reserva para rematar a los que huyen · No cargar de frente contra lanceros en línea |
| Todos | Retirarse en orden (sin desarmarse) · Huir (moral rota) |

### H24c — El general elige la táctica

| Táctica | Cuándo pesa más |
| --- | --- |
| Ataque total | Más fuerte que el enemigo (como el Cargar de `BattleGeneral`) |
| Línea defensiva | Más débil o con más arqueros; mejor si hay una loma cerca |
| Hostigar con arqueros | Muchos arqueros: la infantería espera y los arqueros desgastan; cargan cuando el enemigo se acerca |
| Carga de caballería | Mucha caballería y el enemigo sin lanceros |
| Defender la colina | Hay altura cerca y es más débil |
| Retirada ordenada | Perdiendo por mucho (antes de que se rompa la moral) |

Cada 5 s vuelve a pensar, con histéresis. Te avisa arriba de la barra: "El enemigo forma una línea defensiva en la
loma", "¡La caballería enemiga flanquea por la izquierda!".

### H24d — Moral y flancos

- La moral de `Assault` pasa a ser de todas las batallas: −por compañero caído cerca, −mucho si cae el general,
  −si le pegan por el **flanco** (+25% de daño) o por **detrás** (+50%), +si va ganando su formación.
- Una formación con poca moral se retira; con muy poca, huye. Así **rodear sirve**: pegarle por detrás desarma su
  línea, como pediste.

### H24e — Terreno

- Al empezar, el general mira el campo en una grilla (altura, agua, árboles) y marca lomas, bosques y pasos angostos.
- Los arqueros prefieren la altura, la línea defensiva se pone arriba de la loma, la caballería evita el bosque y el
  agua, y la emboscada sale de los árboles (ya hay `Armies.hidden`).

### H24f — Dónde se usa y "Delegar el mando"

- Hueste, batallas de campo entre facciones, emboscadas, patrullas, el combate de los ataques a aldeas sin muralla
  (reemplaza el plan de `BattleGeneral` por esto).
- Para tus ejércitos, un botón **Delegar** en la vista aérea: el mismo general maneja a los tuyos (como en Bannerlord).
  Sin delegar, siguen tus órdenes como ahora.

## Cómo se prueba

- `/warband prueba tactica <nombre> [cantidad]`: una batalla de prueba con esa táctica forzada.
- Un modo para ver qué piensa la IA: partículas sobre cada formación con su comportamiento, y la táctica en pantalla.
- GameTests chicas por comportamiento: los arqueros retroceden, la caballería sale por el flanco, la línea se cierra.
- El medidor de rendimiento (`/warband prueba medir`) con 60 contra 60: las decisiones son por formación (1 por
  segundo), no por soldado, así que no debería pesar.

## Preguntas para Ale

1. ¿Que el juego te diga qué táctica usa el enemigo, o que lo tengas que descubrir mirando?
2. ¿Querés el botón Delegar el mando para tus ejércitos?
3. ¿Muro de escudos y cuadro contra la caballería (necesitan escudos o lanzas en el kit)?
4. ¿Una opción de dificultad (IA más o menos lista)?
5. Orden sugerido: H24a → H24b → H24d → H24c → H24e → H24f. ¿Te parece o querés primero algo en particular?

## Respuestas de Ale (2026-10-14) y cómo quedó

1. No avisa nada (como Bannerlord): lo que hace el enemigo se ve mirando.
2. Delegar el mando: sí (botón Delegar en la fila del ejército de la vista aérea).
3. Muro de escudos con el escudo del infante (lanzas todavía no hay, así que no hay cuadro contra caballería).
4. Dificultad: `Tactics.DIFFICULTY` en la configuración (0 fácil, 1 normal, 2 difícil).
5. Todo junto, para probar al final. Detalle de cómo quedó en CLAUDE.md (sección H24).

Quedó afuera (a revisar): los ataques a aldeas (H3/H9) siguen con `BattleGeneral`; las patrullas, sueltas; arqueros a
caballo dando vueltas (hoy hostigan como los de a pie, más rápido); cuadro contra caballería (sin lanzas).
