# H5 — Vista aérea RTS (ronda 2: arreglos + rotación + GUI)

> Confirmado por Ale: el modo de orden por defecto queda "automático" (ataca si hay enemigo bajo
> el cursor, mueve si no).

## Contexto

Ya armé una primera versión de H5 (cámara con `V`, WASD, selección por arrastre, click derecho,
grupos 1-9) y Ale la probó. Reportó:

1. La selección no anda.
2. La cámara debería poder atravesar el piso como espectador si hay terreno debajo.
3. El click derecho tampoco mueve a las unidades.
4. Falta una GUI: botones de grupo, de órdenes, de formaciones.
5. Debería poder, apretando click derecho y moviendo el mouse, cambiar el ángulo/dirección de cámara.

También pidió mirar **Reign of Nether** (mod RTS para Minecraft, Forge 1.20.1, GPL-3.0,
github.com/SoLegendary/reignofnether) como referencia. Lo cloné y leí su cámara/input
(`OrthoviewClientEvents`, `TopdownGui`). Mismo trato que con Recruits: se lee para entender la
técnica, no se copia código al repo (aunque su licencia GPL lo permitiría, mantenemos la misma
política de "reimplementar, no pegar" para no mezclar licencias en este repo).

## Causa de los bugs 1 y 3 (la misma causa para ambos)

Mi código de selección/click usaba `Minecraft.getInstance().mouseHandler.xpos()/ypos()` como si
fueran píxeles de **GUI escalada** (los mismos que `Window.getGuiScaledWidth/Height()`, que es lo
que usa `GuiGraphics` para dibujar y lo que yo uso en mi proyección mundo↔pantalla). Pero
`MouseHandler.xpos()/ypos()` están en píxeles **físicos de ventana** (`getScreenWidth/Height()`).
Con `guiScale:2` (que `jugar-dev.bat` deja seteado en las opciones), estos dos sistemas de
coordenadas difieren por un factor 2 — toda la proyección pantalla↔mundo quedaba corrida, así que
la selección no encontraba nada y el rayo del click derecho apuntaba a cualquier lado (por eso
"tampoco anda").

Reign of Nether evita este problema de raíz de una forma mejor que "convertir a mano": abren una
`Screen` propia (no pausa el juego) mientras la vista está activa, y manejan mouse/teclado con los
métodos de `Screen` (`mouseClicked`, `mouseDragged`, `mouseReleased`, `mouseScrolled`,
`keyPressed`) en vez de los eventos de input "crudos" de Forge que usé yo. Esos métodos ya
reciben las coordenadas en escala de GUI (Minecraft hace la conversión antes de llamarlos), así
que el bug desaparece solo. De paso: una `Screen` es el lugar natural de Minecraft para poner
botones (lo que falta de GUI), y separa mucho mejor "click corto = orden" de "arrastre = otra
cosa" que lo que yo tenía.

Su cámara en sí es otra historia — ellos teletransportan al jugador real a modo espectador allá
arriba, lo cual rompe nuestro "Steve queda quieto en el piso, vulnerable". Nos quedamos con
nuestra cámara (`Marker` + `Minecraft.setCameraEntity`) y les tomamos solo la técnica de
Screen/input.

## Causa del bug 2 (traspasar pisos)

Cuando la perspectiva del jugador real (tecla F5) está en 3ª persona, `Camera.setup()` de Minecraft
aplica un "alejate de la pared" (barrido de colisión) para que la cámara en 3ª persona no se meta
dentro de bloques. Como nuestra cámara (el `Marker`) hereda ese comportamiento, al acercarse a un
piso la cámara se frenaba/empujaba en vez de atravesarlo. Fix: forzar
`Minecraft.options.setCameraType(CameraType.FIRST_PERSON)` al entrar a la vista aérea (guardando
el valor anterior) y restaurarlo al salir — en primera persona esa colisión de 3ª persona no se
aplica nunca, y el `Marker` vuela libre de verdad (estilo espectador).

## Pedido 5 (rotar cámara con click derecho + mouse)

El pitch deja de ser una constante fija (55°) y pasa a ser parte del estado, ajustable. Mientras se
mantiene apretado el botón derecho y se mueve el mouse, `yaw`/`pitch` cambian (clampeando el pitch
para no voltear la cámara). Un click derecho **corto** (sin arrastre significativo) sigue siendo la
orden de mover/atacar — se distingue por cuánta distancia recorrió el mouse entre el press y el
release, igual que ya hacía Reign of Nether con sus propios umbrales.

## Arquitectura nueva

- **`client/aerial/AerialScreen.java`** (nuevo) — `extends Screen`, `isPauseScreen()` → `false`.
  Es donde pasa todo lo que antes estaba repartido en eventos de input crudos:
  - `render(...)`: dibuja el rectángulo de selección, las marcas sobre las unidades
    seleccionadas, y los botones (ver abajo). El mundo de fondo lo sigue dibujando Minecraft como
    siempre (la Screen es transparente, no tiene fondo propio).
  - `mouseClicked`/`mouseDragged`/`mouseReleased` (botón izquierdo): arrastre = selección por
    rectángulo (igual lógica que antes, pero con coordenadas ya correctas); click corto = selecciona
    la unidad más cercana al cursor.
  - `mouseClicked`/`mouseDragged`/`mouseReleased` (botón derecho): arrastre = rotar cámara
    (ajusta `yaw`/`pitch`); click corto = orden de mover/atacar en el punto bajo el cursor (mismo
    rayo con `Camera`/`ClipContext` que antes, ahora con coordenadas correctas).
  - `mouseScrolled`: zoom (igual que antes).
  - `keyPressed`: `1`-`9` selecciona grupo, `Ctrl+1`-`Ctrl+9` asigna la selección actual (se mueve
    acá desde `AerialCameraController`); `V` o `Esc` cierran la vista.
  - Botones (vanilla `Button`, sin textura nueva por ahora — son botones de texto lisos, se van a
    ver "de placeholder" hasta que H7 les dé arte): una fila de 9 para los grupos (muestran el
    número y se resaltan si el grupo tiene miembros), `Mover`/`Atacar`/`Alto` para forzar el modo
    del próximo click derecho (por defecto sigue siendo automático: ataca si hay un enemigo bajo
    el cursor, mueve si no), y `Línea`/`Libre` para elegir cómo se reparten los destinos de la
    próxima orden de movimiento (ver formaciones abajo).
  - Se abre con `mc.setScreen(new AerialScreen(controller, state))` al entrar y se cierra
    (`mc.setScreen(null)`) al salir. Minecraft suelta/agarra el mouse solo al abrir/cerrar una
    Screen, así que se elimina el `releaseMouse()/grabMouse()` manual que tenía antes
    `AerialCameraController`.

- **`client/aerial/AerialCameraController.java`** se achica: se queda solo con lo que no depende
  de la Screen — el tick (mover `state.center` con WASD, congelar a Steve con
  `MovementInputUpdateEvent`, auto-salida si bajó la vida, ubicar el `Marker` cada tick) y el
  toggle de `V` para abrir/cerrar la `AerialScreen`. Gana forzar/restaurar `CameraType` al
  entrar/salir.

- **`client/aerial/AerialCameraState.java`**: `pitch` pasa de constante a campo mutable (con min/
  max), se agrega el modo de orden forzado (automático/mover/atacar) y la formación elegida
  (ninguna/línea).

- **Formaciones (alcance acotado)**: no se reutiliza el código de formaciones de Recruits — vive
  dentro de `CommandEvents` (clase interna de Recruits) pensado para `player.pick()`, no para un
  punto que le mandemos nosotros. Para "Línea" calculamos nosotros mismos (matemática propia, no
  de Recruits): en el cliente, al soltar el click derecho con Línea activa, se reparten puntos en
  una línea perpendicular a la dirección de marcha alrededor del punto clickeado (separados ~2
  bloques), uno por soldado seleccionado. "Libre" manda a todos al mismo punto (comportamiento
  actual).

- **`network/AerialOrderMessage`**: en vez de un solo `targetPos` para todos los soldados, lleva
  una lista de `(UUID soldado, Vec3 destino)` — así soporta tanto "todos al mismo punto" (Libre)
  como "cada uno a su lugar en la línea".

## Verificación

Sigue sin haber GameTest posible (es todo cliente/cámara/input). Probar con `jugar-dev.bat`:
arrastrar para seleccionar, click derecho corto sobre piso/enemigo (orden), click derecho sostenido
+ mover mouse (rota cámara), acercarse a un techo/piso para confirmar que la cámara lo atraviesa,
los botones de grupo/orden/formación, y `1..9`/`Ctrl+1..9` por teclado (deben seguir andando).

## Ronda 3 (probada la ronda 2, seguían rotos WASD y las órdenes + pedidos nuevos)

Ale probó: selección por arrastre anduvo bien, pero WASD no movía la cámara, se veía el brazo/ítem
de Steve, y dar una orden (click derecho) no hacía nada. Pidió además: botones de modo de unidad
(seguir / mantener posición / vagar libre) y de combate (neutral / agresivo / raid / pasivo), un
toggle de hitboxes translúcidas, selección rápida por categoría (caballería / a distancia /
infantería) y que seleccionar una sola unidad muestre su menú de Recruits (el que abre al agachado +
click en primera persona). Cierre: "quiero que sea como jugar a un RTS, lo más fiel posible".

- **WASD no movía la cámara**: lo leía desde un `TickEvent` global en `AerialCameraController`. Viendo
  cómo lo resuelve Reign of Nether (leen sus teclas de paneo desde el render/tick de su propia
  `Screen`, no de un tick genérico), moví esa lectura a `AerialScreen.tick()` — Minecraft llama a
  `tick()` de la Screen activa todos los ticks mientras está abierta, así que las teclas no quedan
  "sordas" por el foco de la pantalla. `AerialCameraController` ahora expone un método `tick()` sin
  anotar que la Screen llama.
- **Se veía el brazo/ítem de Steve**: al forzar `CameraType.FIRST_PERSON` (fix de la ronda 2 para
  atravesar pisos), Minecraft vuelve a dibujar el brazo/ítem en mano como en primera persona real,
  aunque la cámara efectiva sea el `Marker`. Se cancela con `RenderHandEvent`/`RenderArmEvent`
  mientras la vista está activa — exactamente lo que hace Reign of Nether por la misma razón.
- **Click derecho no hacía nada (la causa real)**: leyendo `AbstractRecruitEntity.setFollowState` en
  el código de Recruits, el estado 2 ("mantener tu posición") **pisa el `holdPos` con la posición
  ACTUAL del soldado** (`clearHoldPos(); setHoldPos(position());`), sin importar qué le hayamos puesto
  antes. Por eso cualquier "mover a este punto" se convertía en "quedate donde ya estás". El estado 3
  (el que usa Recruits para "engancharse" a un objetivo) no toca `holdPos`, así que ahora
  `AerialOrderMessage` usa el estado 3 para mover Y atacar (con o sin objetivo). De paso until subí la
  tolerancia de click-vs-arrastre del click derecho (6px o <300ms), por si el jitter del mouse también
  jugaba en contra.
- **Botones de modo**: nueva fila "Seguir" (estado 1) / "Mantener" (estado 3 con `holdPos` = posición
  actual de cada soldado, calculado del lado servidor) / "Vagar" (estado 0), y otra de aggro
  ("Neutral"/"Agresivo"/"Raid"/"Pasivo" = `setAggroState(0..3)`, los mismos números que usa la
  pantalla de comando de Recruits). Nuevo paquete `AerialSetStateMessage`.
- **Hitbox toggle**: botón "Hitbox: ON/OFF"; cuando está prendido, dibuja un rectángulo translúcido
  (la caja de colisión de cada soldado propio, proyectada a pantalla) para ver dónde están, sin
  depender de seleccionarlos.
- **Categorías**: "Caballería" (montado en caballo), "A Distancia" (arco/ballesta en la mano
  principal), "Infantería" (lo que no es ninguna de las dos) — heurística propia por equipo/montura,
  no por tipo de entidad de Recruits hardcodeado, para no romperse si cambian los kits.
- **Menú de una sola unidad**: shift+click sobre una unidad (cuando queda una sola seleccionada)
  manda `AerialInspectMessage`, que llama a `recruit.openGUI(player)` — el mismo menú que abre
  Recruits al agachado+click. Elegí que sea con Shift (como el agachado de Recruits) y no en
  cualquier click simple, porque abrir un menú modal en cada selección rompería el "jugar como un
  RTS" que pidió Ale; si prefiere que sea siempre, se saca esa condición.

## Ronda 4 (WASD seguía sin andar + redistribuir botones + minimapa + formación en línea)

- **WASD seguía sin mover la cámara**: el fix de la ronda 3 (leerlo desde `Screen.tick()` en vez de
  un `TickEvent` global) no alcanzó. La causa real: Minecraft deja de actualizar
  `KeyMapping.isDown()` para las teclas de movimiento mientras CUALQUIER Screen tiene el foco, sea
  donde sea que lo leas — es una limitación de Forge en 1.20.1. Reign of Nether tiene el mismo
  problema documentado en su código (`TopdownGui.java`: comentario sobre el campo `passEvents` que
  lo resolvía en versiones viejas y "lo sacaron, no sé por qué" en 1.20.1). La solución real: rastrear
  las 4 direcciones a mano con `keyPressed`/`keyReleased` de nuestra propia `AerialScreen` (esos sí
  se siguen llamando siempre, es el contrato de toda Screen), respetando el bind real del jugador
  (`Options.keyUp/keyDown/keyLeft/keyRight`, no `W`/`A`/`S`/`D` fijos).
- **Formación en línea con click derecho (como Total War)**: con soldados seleccionados, mantener
  el click derecho y arrastrar los despliega en una línea entre el punto de partida y el punto
  donde está el mouse (con vista previa en pantalla); la rueda del mouse mientras se arrastra elige
  entre 1, 2 o 3 filas en vez de hacer zoom. Sin selección, arrastrar con el click derecho sigue
  rotando la cámara como en la ronda 2 — un click corto en cualquier caso sigue siendo la orden de
  mover/atacar de siempre.
- **Minimapa**: panel cuadrado abajo a la derecha (mismo rincón que usa Reign of Nether), coloreado
  con los colores reales del mapa de vanilla (`BlockState.getMapColor`, lo mismo que usa el ítem
  Mapa — no armamos una paleta de biomas propia) recalculado cada medio segundo, con puntos verdes
  para las tropas propias y un click para recentrar la cámara ahí.
- **Botones redistribuidos**: grupos 1-9 abajo a la izquierda; mover/atacar/auto + formación abajo al
  centro; seguir/mantener/vagar + aggro una fila arriba de esa; categorías (caballería/a
  distancia/infantería) + el toggle de hitbox en una columna a la derecha, arriba del minimapa (para
  no superponerse).

## Ronda 5 (ya anda más o menos — hitbox rara, grupos que "no se guardan")

- **Hitbox raras**: dibujaba el rectángulo que encierra las 8 esquinas de la caja de colisión. Con
  la cámara mirando hacia abajo, la esquina de arriba (cabeza) proyecta más arriba en pantalla que
  la de abajo (pies), así que quedaba un rectángulo alto y flaco en vez de algo pegado al piso. Ahora
  solo se usan las 4 esquinas de la base — queda como una sombra bajo el soldado.
- **"No se guardan los grupos" (causa real)**: no es que no se guarden — `Ctrl+click`/`Ctrl+número`
  sí asigna el grupo. El problema es al recordarlo después: buscaba los soldados del grupo entre los
  que estaban a 100 bloques de la cámara (`state.center`), y como ahora WASD sí mueve la cámara (la
  ronda 4), es mucho más fácil terminar paneando lejos de donde están esos soldados — la búsqueda no
  los encontraba y parecía que el grupo se había perdido. Cambié esa búsqueda (y la de categorías,
  selección e hitboxes) para que recorra todos los soldados propios cargados del cliente
  (`Level.entitiesForRendering()`), sin importar la distancia a la cámara. Del lado del servidor
  hice el mismo cambio: los 3 paquetes (orden, modo, inspeccionar) ahora resuelven cada soldado por
  su UUID (`ServerLevel.getEntity(UUID)`) en vez de buscarlo cerca de Steve.
- Ficha de una sola unidad al seleccionarla: lo dejamos pendiente, como pidió Ale.

## Ronda 6 (estandarte por grupo)

Cada soldado que está en uno de los 9 grupos ahora muestra siempre (seleccionado o no) un
estandarte ficticio arriba de la cabeza: un palito + una tela de un color distinto por grupo (1-9),
con el número encima — dibujado a mano con `GuiGraphics.fill`/`drawString` (sin textura nueva),
igual que el resto de los indicadores de esta vista. Elegí "estandarte" (en vez del beacon) porque
es 2D, como todo lo demás que ya dibuja esta pantalla — un beacon real sería un haz de luz en el
mundo 3D, mucho más código para el mismo objetivo de "identificarlos a simple vista".

## Ronda 7 (minimapa: no se veía la tierra)

Causa: para encontrar la altura del terreno en cada celda usaba el heightmap del chunk
(`Level.getHeight(...)`), y ese dato no está garantizado del lado cliente — daba cualquier altura,
normalmente terminaba leyendo aire, y el color de aire (`MapColor.NONE`) es negro. Por eso se veía
todo negro/vacío mientras los puntos de las tropas (que no dependen de esto) sí se veían bien.
Cambié la búsqueda de altura a un rayo de arriba hacia abajo (`Level.clip`, la misma técnica que ya
usa esta vista para encontrar el punto del piso bajo el cursor), que no depende del heightmap.

## Ronda 8 (el minimapa casero no sirve — usar un mod)

Ale pidió bajar un mod de minimapa en vez de seguir afinando el nuestro. Agregué **Xaero's Minimap**
(`forge-1.20.1-26.6.0`, Modrinth, sin dependencias extra) como mod aparte — no le llamamos nada de su
código, solo tiene que estar instalado junto a Recruits y correr al lado. Mismo patrón que Recruits
en `build.gradle` (se baja a `libs/` en el primer build) pero más simple: `runtimeOnly`, no
`fg.deobf` ni mixins, porque no compilamos contra sus clases. `compilar.bat` ahora también lo copia
a la carpeta de mods real. Saqué toda la clase `AerialMinimap` (el panel casero, los puntos de
tropas, el click para recentrar) — la columna de categorías/hitbox que estaba pegada a su esquina
ahora queda fija abajo a la derecha, ya sin depender de esa clase.

Un detalle a confirmar jugando: en `jugar-dev.bat` (el Minecraft de desarrollo) nunca probé si Forge
carga un mod agregado así en el entorno de dev — en el juego real (`compilar.bat` + tu `mods` de
siempre) no hay ninguna duda, carga como cualquier mod. Si en `jugar-dev.bat` no aparece el
minimapa de Xaero, decime y lo reviso.
