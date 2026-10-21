# H6 — Tu facción (plan)

> Estado (2026-10-12): pasos 1 a 6 hechos y con GameTests (45/45). Falta que Ale lo pruebe jugando.

> Respuestas de Ale (ronda 1):
> 1. Preguntó si el Zapador puede poner TNT o armar cañones simples → sí, ver sección 5.
>    Con eso la doctrina **Ingeniería** queda (mejora al Zapador).
> 2. Cambiar de doctrina: la primera vez gratis, después 20 esmeraldas del Campamento y 1 día de espera. ✔
> 3. La GUI va **en el Campamento**, con pestañas: Cofre · Doctrinas · Kits. ✔
> 4. Al cambiar un kit, los soldados ya armados **se re-arman solos si hay materiales**. ✔
>
> Ronda 2: Ale pidió poder **vincular más cofres al almacén del Campamento** (se queda corto).
> Hecho junto con el paso 1 (ver "Cofres vinculados").

## Qué pide el documento de diseño

"Al crear tu facción elegís: **nombre, colores y estandarte** (tus soldados lo llevan), una
**doctrina** (bonificación de estilo), **kits propios** (qué equipo usa cada clase) y **nombres de
tropas** (Infante → Guardia de Hierro)."

## Lo que ya resuelve Recruits (no se rehace)

Recruits ya tiene su pantalla de facción (`FactionEditScreen`): nombre, color del equipo, color de
unidad y un espacio para poner el estandarte que diseñaste en el telar. Todo eso queda guardado en
su `RecruitsFaction` (`teamDisplayName`, `teamColor`, `unitColor`, `banner`). Además, cuando
conquistás una aldea ya te creamos una facción de Recruits si no tenías ("Warband de Ale",
`RecruitsClaimBridge`).

Así que **nombre, colores y estandarte se editan en la pantalla de Recruits** y nosotros solo los
**leemos y los aplicamos** a tus soldados.

## Dónde se guarda

Un `SavedData` nuevo, `faction/PlayerFactionData` (archivo `warbandcraft_player_factions`, en el
overworld, igual que `Relations`), una entrada por jugador (UUID):

- `doctrine`: doctrina elegida (o ninguna), si ya se eligió alguna vez, y el día del último cambio.
- `classNames`: id de clase → nombre propio (`warbandcraft:infante` → "Guardia de Hierro").
- `kits`: id de clase → nivel → equipo propio, más un número de **versión** por clase que sube cada
  vez que guardás (sirve para saber qué soldados tienen el kit viejo).

Va por jugador y no por facción de Recruits: Ale juega solo, y así no depende de tener una facción
creada.

## La GUI del Campamento (pestañas)

Hoy el Campamento abre un cofre vanilla (`ChestMenu.sixRows`). Pasa a tener su propio menú
(`CampMenu`, misma grilla de 54 + inventario, para que el cliente sepa que es un Campamento) y arriba
del cofre aparecen **pestañas**, como las del inventario creativo:

| Pestaña | Qué tiene |
| --- | --- |
| **Cofre** | El cofre de siempre (54 espacios). Es la que abre por defecto. |
| **Doctrinas** | Las 4 doctrinas como tarjetas con su efecto; la elegida resaltada; botón "Adoptar" con el costo (gratis la 1ª vez, después 20 esmeraldas) y cuánto falta si estás en espera. Abajo, el nombre/color/estandarte de tu facción de Recruits con un botón "Editar en Recruits". |
| **Kits** | A la izquierda, la lista de clases. Al elegir una: campo para renombrarla, botones de nivel (Básico / Medio / Élite), el editor de equipo de ese nivel, y abajo el costo total y si el cofre alcanza. Botones "Guardar" y "Volver al original". |

Solo el dueño del Campamento ve Doctrinas y Kits (los demás, si pueden abrirlo, ven solo el Cofre).
Botones de texto lisos, como en H5; el arte llega en H7.

## 0. Cofres vinculados (hecho)

- Agachado + click derecho con el Bastón a un cofre, barril o caja de shulker: queda vinculado al
  Campamento tuyo más cercano (hasta 16 bloques, máximo 16 cofres; un cofre doble cuenta las 2
  mitades). Repetirlo lo desvincula. Agachado + Bastón al Campamento: dice cuántos tiene y hace
  brillar los vinculados.
- Todo lo que usa el almacén (armar soldados, botín, impuestos, equipo viejo devuelto) recorre el
  cofre propio y después los vinculados. Lo que entra llena primero el Campamento.
- Más adelante, en la pestaña Cofre, se muestra la lista de vinculados.

## 1. Identidad: tus colores en tus soldados (hecho)

Hoy `Faction.style()` tiñe el cuero y cambia los estandartes de las facciones NPC. Se separa ese
"estilo" (estandarte + color de cuero) en `FactionStyle`, que también puede salir de **tu** facción
de Recruits:

- Estandarte = `RecruitsFaction.getBanner()`.
- Color del cuero = el color de unidad de Recruits (`unitColor`).

`ClassAssigner`, después de equipar, le pasa cada pieza por tu estilo: el Abanderado lleva **tu**
estandarte y el cuero sale de tu color. El hierro no se tiñe (Minecraft no deja); el tabardo de
verdad llega en H7.

Si cambiás el estandarte en Recruits: **`/warband uniformar`** vuelve a teñir y cambia el estandarte
de tus soldados a 32 bloques (gratis, no toca nada más).

## 2. Nombres de tropas

- Se cambian en la pestaña Kits. El nombre propio pisa al del JSON en el Bastón, en los mensajes y
  en la GUI.
- Máximo 24 letras; vacío = vuelve al nombre original.
- El nombre de cada soldado (el que le pone Recruits) no se toca.

## 3. Doctrinas

Una sola a la vez. Se aplican como **modificadores de atributos** a tus soldados cuando aparecen en
el mundo (`EntityJoinLevelEvent`) y cuando los armás. Cada modificador tiene un UUID fijo, así no se
apila y se saca si cambiás de doctrina. Sin mixins.

| Doctrina | A quién | Efecto propuesto |
| --- | --- | --- |
| Caballería | Caballos de tus soldados | +25% velocidad, +6 vida |
| Escudos | Infantes, Lanceros, Abanderado | +3 armadura, +40% resistencia al empuje |
| Arquería | Arqueros, Ballesteros | +25% daño de flecha |
| Ingeniería | Zapadores | Explosiones más fuertes, mecha más corta, el cañón dispara más seguido (sección 5) |

- **Arquería**: el documento decía "más alcance", pero el alcance lo decide la IA de Recruits por
  dentro (pide un mixin). Subir el daño de la flecha se hace con un evento.
- **Costo de cambio**: la primera vez gratis. Después, 20 esmeraldas que salen del cofre de ese
  Campamento y 1 día de juego desde el último cambio.
- Los números quedan como constantes en `Doctrine` para ajustarlos después de probar.

## 4. Kits propios

Cada clase y nivel puede tener un equipo propio que reemplaza al del JSON **solo para vos**.

**Editor** (pestaña Kits): 6 espacios de equipo (cabeza, pecho, piernas, pies, mano secundaria, mano
principal) + 9 de mochila, y tu inventario abajo. Son espacios "fantasma": ponés un ítem y queda una
copia como plantilla, pero el tuyo no se gasta. Click derecho vacía el espacio; en la mochila, la
rueda sube/baja la cantidad.

**Qué se guarda**: solo tipo de ítem y cantidad, sin encantamientos ni nombres (si no, ponés una
espada encantada en la plantilla y el Campamento te la da encantada cobrando una común).

**Lo que no cambia**: el tipo de entidad de Recruits, el nivel de experiencia pedido, el costo extra y
el caballo siguen siendo los del JSON.

**Reglas para que la IA no se rompa** (se marcan en rojo y no deja guardar):
- Arquero: mano principal = arco. Ballestero: ballesta. Las demás clases: ni arco ni ballesta.
- Arquero y Ballestero: al menos 1 flecha en la mochila.
- Zapador: puede llevar TNT en la mochila (la usa en la sección 5).

**Por dentro**: `ClassAssigner` hoy pide la clase a `KitManager.INSTANCE.get(id)`. Pasa a pedirla a
`PlayerKits.resolve(level, jugador, id)`, que devuelve la misma `ClassKit` con tus cambios mezclados.
Costo, cobro, conversión y caballo siguen igual.

### Re-armado automático

Cada soldado armado guarda en su persistent data la versión del kit con que lo armaron
(`warbandcraft:kit_version`). Cuando guardás un kit nuevo:

1. Al toque, los soldados de esa clase a menos de 24 bloques de alguno de tus Campamentos se
   re-arman: devuelven el equipo viejo al cofre y toman el nuevo.
2. Los que están lejos se re-arman la próxima vez que pasen cerca de un Campamento tuyo (se revisa
   cada 10 segundos).
3. Si no alcanzan los materiales, el soldado **se queda como está** (no baja de nivel) y se vuelve a
   intentar cuando entren cosas al cofre.
4. El re-armado **no cambia sus órdenes** (no lo pone a seguirte como el Bastón) y no lo cambia de
   nivel: solo cambia el equipo del mismo nivel.
5. Un mensaje resumido: "3 Caballeros re-armados. 2 esperan materiales: faltan 4 hachas de hierro."

## 5. Zapador: TNT y cañón

Respuesta a "¿puede poner TNT o armar cañones simples?": **sí, las dos cosas**, con IA nuestra
agregada al Zapador de Recruits (un `Goal` extra que se le suma al aparecer, sin mixins). Lo que **no**
conviene es que arme un cañón de redstone de verdad con bloques: es frágil (depende del terreno, del
agua y de la redstone) y deja basura en el mundo. En su lugar, un cañón "de mentira" que hace lo
mismo.

### 5a. Petardo (poner TNT)

- **Orden**: en la vista aérea, con zapadores seleccionados, botón nuevo **"Volar"** y click a un
  bloque (puerta, muro). Probar también si funciona la orden de "fuego estratégico" de Recruits
  (`setStrategicFirePos`), que el Zapador ya tiene: así se podría dar sin entrar a la vista aérea.
- **Qué hace**: camina hasta el bloque, pone un TNT encendido (entidad, no bloque: no se puede
  romper ni sacar), grita "¡Fuego en el agujero!", y se aleja corriendo unos 8 bloques.
- Gasta 1 TNT de su mochila. Sin TNT, no hace nada y avisa.
- Mecha: 3 s (con Ingeniería, 2 s). Explosión de TNT normal (con Ingeniería, un poco más fuerte).
  Abre puertas de madera y muros de piedra del castillo.

### 5b. Cañón de campaña

- **Orden**: en la vista aérea, botón **"Cañón"** con un zapador seleccionado, y click a un lugar
  del piso: el zapador va y arma ahí el cañón. Después, click a un objetivo para apuntar.
- **Qué es**: una entidad nueva que se ve como un bloque simple (placeholder hasta H7). Tira TNT
  encendido en arco al objetivo, hasta 40 bloques. Un disparo cada 8 s (con Ingeniería, 5 s), siempre
  que el zapador esté al lado y tenga TNT.
- **Costo de armarlo**: 3 lingotes de hierro + 1 bloque de hierro del zapador o del Campamento. Se
  desarma con click derecho (devuelve los materiales).
- Es la parte más grande de H6: va al final, después de que funcione el petardo.

### Cuidados

- Solo se puede usar contra aldeas o castillos a los que estés atacando (que haya un ataque en curso
  de `RaidManager`), para no volarte tu propia aldea por un mal click.
- El TNT rompe bloques de verdad: un castillo conquistado queda con el agujero. Si no gusta, en H12
  (mejoras visibles) el constructor lo repara.

## Archivos

Nuevos:
- `faction/PlayerFactionData.java` — el guardado.
- `faction/Doctrine.java` — las 4 doctrinas, sus números y cómo se aplican/sacan.
- `faction/FactionStyle.java` — estandarte + color de cuero (de una facción NPC o de la tuya).
- `kit/PlayerKits.java` — mezcla el JSON con tus cambios, valida y re-arma.
- `camp/CampMenu.java` + `init/ModMenus.java` — el menú propio del Campamento.
- `client/camp/CampScreen.java`, `DoctrineTab.java`, `KitsTab.java` + `camp/KitTemplateMenu.java`
  (el menú con espacios fantasma).
- `sapper/PlaceTntGoal.java`, `sapper/FieldCannonEntity.java`, `sapper/CannonGoal.java`.
- `network/`: elegir doctrina, renombrar clase, guardar/borrar kit, cambiar de pestaña, orden de
  volar y de cañón.
- `event/FactionEvents.java` — aplicar doctrina al aparecer soldados/caballos/flechas, re-armado
  periódico, sumar los `Goal` al Zapador.
- `gametest/H6GameTests.java`.

Cambian:
- `camp/CampBlockEntity.java` — abre `CampMenu` en vez de `ChestMenu`.
- `kit/ClassAssigner.java` — usa `PlayerKits.resolve`, aplica estilo y doctrina, guarda la versión
  del kit, y tiene un modo "re-armar" (mismo nivel, sin tocar órdenes, sin bajar de nivel).
- `item/MarshalBatonItem.java` — muestra los nombres propios.
- `faction/Faction.java` — `style()` pasa a usar `FactionStyle`.
- `client/aerial/AerialScreen.java` + `network/AerialOrderMessage.java` — botones "Volar" y "Cañón".
- `command/WarbandCommand.java` — `uniformar`.
- Archivos de idioma, `CLAUDE.md` y el documento de diseño.

## Orden de trabajo (cada paso se puede probar solo)

1. ✔ **Identidad + `/warband uniformar` + cofres vinculados**. Probar: estandarte en Recruits → armar un Abanderado → lleva
   el tuyo.
2. **Campamento con pestañas** (Cofre funcionando igual que antes, Doctrinas y Kits vacías).
3. **Doctrinas** (Caballería, Escudos, Arquería). Probar: elegir Caballería y comparar el caballo;
   cambiar de nuevo y ver que cobra 20 esmeraldas.
4. **Kits + nombres + re-armado**. Probar: Caballero con hacha en vez de espada; los caballeros
   cerca del Campamento cambian solos y el cofre recibe las espadas viejas.
5. **Petardo del Zapador** + Ingeniería. Probar: volar la puerta de un castillo.
6. **Cañón de campaña**. Probar: armarlo frente a un castillo y tirarle a una torre.

## GameTests (H6GameTests)

- Un kit propio pisa el equipo del JSON y el costo cobra lo nuevo.
- Un Arquero sin arco no se puede guardar.
- Guardar un kit re-arma a un soldado cerca del Campamento y devuelve el equipo viejo; sin
  materiales queda igual.
- La doctrina pone el modificador una sola vez aunque el soldado vuelva a cargar, y lo saca al cambiar.
- El 2º cambio de doctrina cobra 20 esmeraldas y respeta el día de espera.
- Un Abanderado armado lleva el estandarte de la facción de Recruits del dueño.
- Un Zapador con orden de volar gasta 1 TNT y aparece un TNT encendido junto al bloque.

## Fuera de H6

- Tabardos de verdad, arte de las pestañas y del cañón → H7.
- Facciones compartidas entre varios jugadores.
- Que la doctrina cambie la IA (alcance, formaciones) → H9.
- Que el Zapador elija solo dónde poner TNT, sin orden → H9 (IA táctica).
