# Warband Craft — guía para Claude

Mod de Minecraft que convierte el juego en un Mount & Blade. Ale (el dueño) **orquesta**: define ideas,
prueba y da feedback. Claude escribe todo el código, compila y entrega el `.jar`. Hablarle en castellano
rioplatense, simple, sin jerga innecesaria: lo hace por diversión.

Documento de diseño (fuente de verdad de las mecánicas): "Warband Craft — Documento de diseño" en Claude Docs.
Si una decisión de diseño cambia, actualizar ese documento.

## Stack

- Minecraft **1.20.1**, Forge **47.4.10**, ForgeGradle 6, mappings oficiales de Mojang, Java 17.
- Dependencia: **Villager Recruits 1.15.2** de talhanation (`maven.modrinth:villager-recruits`).
  - Código fuente de referencia: https://github.com/talhanation/recruits (rama `main` = 1.20.1).
  - Licencia **All Rights Reserved**: NO copiar su código a este repo ni redistribuir un Recruits modificado.
    Se usa como dependencia; para cambiar su comportamiento: eventos de Forge primero, mixins si no alcanza.
- Dependencia (H10): **Villager Workers 2.0.3** de talhanation (`workers-1.20.1-2.0.3.jar`, Modrinth; pide
  Recruits ≥1.15.0). Misma licencia que Recruits: se usa, no se copia ni se redistribuye. Se baja a `libs/` como
  Recruits y va en el classpath (`fg.deobf`): lo usamos para crear zonas de almacén (`camp/WorkerZone`).
  **Sus trabajadores heredan de `BowmanEntity` de Recruits**: todo lo que recorre "tus soldados" tiene que
  filtrarlos con `ai/Soldiers.isWorker/isSoldier` (namespace `workers`).
- Armas y armaduras (H26): **Epic Knights** 10.15 (`magistuarmory`) + Addon (`magistuarmoryaddon`), Antique Legacy (`antiquelegacy`),
  Slavic Armory (`slavicarmory`), que piden Architectury + Cloth Config. Van en el paquete y en `dungeon_mods` (archivos
  renombrados `epicknights*-` para que el flatDir no los junte). No hay código suyo en el classpath: se usan por id en los JSON.
- Mazmorras (H25): **YUNG's Better Dungeons** (+ YUNG's API), **L_Ender's Cataclysm** (+ Lionfish API + Curios) y
  **Dungeon Now Loading**. Como Xaero: mods aparte, no les llamamos código, se bajan a `libs/` (lista `dungeon_mods` en
  `gradle.properties`) para que `compilar.bat` los copie, y van al Minecraft de desarrollo (`runtimeOnly fg.deobf`; sus mixins
  andan, a diferencia de Xaero) pero NO a `runGameTestServer` (mundo plano a y=-60: sus mazmorras caen en las áreas de
  prueba). Generan casi todo bajo
  tierra; lo de superficie de Cataclysm (desierto, nieve) va más espaciado (`compat/cataclysm`, paquete obligatorio que
  `dungeon/CompatPacks` agrega solo si Cataclysm está) y lejos de aldeas (tags `*_avoid`).
- Estructuras chicas (pedido de Ale): **Dungeons and Taverns** (`nova_structures`), **Structory** y **Structory: Towers**. Igual que
  los de mazmorras (`dungeon_mods`, en libs/ como `dungeonstaverns-`, `structory-`, `structory_towers-`). Su frecuencia va por
  bioma (`dungeon/StructureDensity`, ver H25).
- Tala (pedido de Ale): **FallingTree** 4.3.4 (`fallingtree`, LGPL): romper un tronco con hacha tira el árbol entero (agachado no).
  Como los de mazmorras (`dungeon_mods`, `pack/mods.json` both). `compat/FallingTreeSetup` (setup común, por reflexión) le pone
  `tools.speedMultiplicand` = `SPEED` (0,25: tarda × 0,25 × troncos) y `forceToolUsage` si sigue en 0; el servidor manda su config.
- Minimapa de la vista aérea (H5): **Xaero's Minimap** (`xaerominimap-forge-1.20.1-26.6.0`, Modrinth).
  Mod aparte que corre solo — no le llamamos nada de su código. NO es una dependencia de Gradle: se
  baja a `libs/` en el primer build (igual que Recruits) pero solo para que `compilar.bat` lo copie
  a la carpeta de mods final. No lo pongas en el classpath de `runClient`/`jugar-dev.bat`: trae sus
  propios mixins y el remapeo SRG→MCP del entorno de desarrollo (pensado solo para Recruits, ver
  `build.gradle`) no sabe traducirlos — rompe el arranque. En el juego real no pasa nada de esto.

## Estructura

```
src/main/java/com/ale/warbandcraft/
  WarbandCraft.java          entrada del mod, registros
  init/                      DeferredRegisters (bloques, ítems, block entities)
  camp/                      Campamento: bloque, cofre de 54 espacios + cofres vinculados (H6), búsqueda por radio,
                             nombre (H8), Barracks + BarracksData (H8: barracón), Prisoners (H9: presos, reclutar, rescate),
                             Forge (H10: herreros), CampStats (H10: totales), WorkerZone (H10: puente con Villager Workers),
                             Organizer (ordena los cofres vinculados)
  kit/                       Clases de soldado: carga de JSON, asignación + conversión de entidad; Armament (H10)
  item/MarshalBatonItem      Bastón de Mariscal (elige clase / arma soldados)
  faction/                   Facciones NPC (H2): Faction, Troop, FactionManager (carga JSON)
  village/                   Aldeas (H2): VillageData (guardado), VillageManager (detección + guarnición),
                             GarrisonDefense (objetivos: illagers, enemigos en guerra), VillageEconomy (H3: impuestos, refuerzos),
                             CastleManager (H4: un castillo por facción, capitán fijo, prisioneros),
                             VillageStock (H10: almacén de aldeas/castillos tuyos), WorkerSupply (H18: trabajadores automáticos)
  market/                    Mercado: Market (vender a otros jugadores, mínimos, almacenes), MarketData, MarketView, Prices
                             (warband_prices/*.json), Schedules (envíos programados), Exchange (Lonja); worker/MerchantEntity
  raid/RaidManager           H3: ataques en curso, victoria, saquear / conquistar, barra de asalto; H9: prisioneros/capturados
  raid/RaidData              H9: guardado de los ataques en curso (overworld)
  battle/                    H9: BattleGeneral (IA táctica: plan, formación, orden Batalla), Surrender (rendirse);
                             H23: BattleChoices (tecla B: meterse / asediar), BattleSize (tamaño de batalla, oleadas);
                             H17: SiegeLanes (bando atacante de un asedio), SiegeDefenders (bando defensor)
  event/BattleEvents         H9: golpe mortal → rendirse, nadie ataca a un rendido, cargar ataques, rendidos sueltos
  faction/Relations          H3: opinión de cada facción por jugador (-100..100, guerra en -40), se guarda en el overworld
  camp/CampRegistry          H3: Campamentos por jugador + botín/impuestos "en camino" si no están cargados
  command/WarbandCommand     /warband aldea | aldeas | facciones | relaciones | saquear | conquistar | uniformar | barracon | rutas | panel | batalla
                             | almacen | retirar | fundar | mercado
  event/CommonEvents         listeners del bus de Forge (H1)
  event/VillageEvents        listeners de H2: buscar campanas, aviso al entrar, no contratar guarnición
  event/RaidEvents           listeners de H3: golpe a guarnición = ataque, botín de caídos, ticks de ataques/economía
  event/BarracksEvents       H8: llegadas al barracón (1 s); comida, guardado automático y guardias (10 s)
  route/                     H13: RouteData (rutas + envíos), Routes (puntos, registrar, grafo, envíos), Travel (viaje)
  event/RouteEvents          H13: viajes (cada tick), envíos que llegan (1 s), sin daño viajando
  client/route/              H13: RoutesScreen (Viajar / Rutas), TravelScreen (barra), tecla J, MapMarkers (puntos en el mapa M)
  panel/Panel                H11: datos y órdenes del Panel de control; client/panel/ PanelScreen (tecla K)
  upgrade/                   H12: Upgrade (lista, costos, requisitos), TownData (hechas, terrenos, obra), Blueprints (anillo,
                             torres, plazas, terrenos), Upgrades (encargar, obras, constructor, efectos);
                             H22: BuildCost (materiales), Placement (lugar elegido); Buildings (planos de los edificios),
                             Style (materiales por facción/bioma)
  event/UpgradeEvents        H12: obras cada tick, el constructor no comercia, constructores huérfanos no se cargan
  route/RoadPlanner, Roads   H14: trazado del camino (grava, cortes, túneles, puentes, muelles) y su obra (RouteData.RoadJob)
  invasion/                  H15: InvasionData (invasiones, marchas, guerras, leva, guarnición pendiente), Invasions (tesoro, leva,
                             invasiones, autoresolución, robo), AiWars (guerras entre facciones), Ambush, Patrols;
                             event/InvasionEvents (cada 1 s, invasores/patrullas sueltos, aviso al entrar);
                             H17: Assault (invasión: aparecer, general, moral, retirada), SiegeDefense (cuartel + refuerzos),
                             Breach (muros, escaleras); H23: Hosts (la Hueste: acampa, batalla de campo, marcha)
  nav/                       H17: malla de navegación propia: NavGrid, NavPath (A*), Mover + RouteGoal (lleva al soldado)
  client/aerial/             Vista aérea (H5): cámara libre (Marker + setCameraEntity), selección, órdenes — solo cliente;
                             `/camara` (comando de cliente) la destraba: vuelve a primera persona sobre el jugador
  client/ModKeyMappings      tecla V de la vista aérea, J de rutas (H13), K del panel (H11)
  network/AerialOrderMessage H5: cliente→servidor, mueve/ataca con setHoldPos/setFollowState/setTarget de Recruits
  sapper/                    H6: SapperGoal (TNT, armar cañón), FieldCannonEntity, SapperRules
  ai/Unstuck                 destrabador: soldado con destino que no avanza → salta, saltito de 2,5 bloques, 10 bloques
                             o al lado del dueño (event/SoldierEvents cada 10 ticks; no corre en GameTestServer)
  ai/Escort                  escolta (pedido de Ale, a probar): los que te siguen (estado 1) fuera de batalla/ejército/cuartel/asedio
                             (`busy`) te defienden primero (bicho hostil a ≤16 tuyo o lo que ataca a lo tuyo; estado 0 prestado con
                             marca `warbandcraft:escort`, vuelven a 1; creepers/neutrales/jefes no; lejos de vos >24 lo dejan), a >20
                             aparecen a tu lado, nadan hacia vos, y con vos en un bote cada uno tiene el suyo (`escort_boat`, caballo
                             adentro) en dos columnas detrás; en tierra se bajan. Recruits: seguir y pelear tienen la misma prioridad (2)
                             y con el dueño a >~8 gana seguir. Cada tick desde `SoldierEvents`, no en GameTestServer.
  ai/Formation               formación Libre: destinos iguales se reparten en grilla (AerialOrderMessage)
  config/                    Tuning (todos los números en config/warbandcraft-common.toml) + TuningClasses (generado por tools/gen_tuning.py)
  ai/Soldiers                H10: isWorker/isSoldier (los trabajadores de Villager Workers no son soldados)
  army/                      H20: ArmyData (ejércitos guardados), Armies (armar, invocar, formación, órdenes, licenciar),
                             ArmyPanel (datos de la pantalla); event/ArmyEvents; client/army/ ArmyScreen, ArmyClient
  util/Ground                buscar piso bloque por bloque (servidor y cliente: el cliente no tiene el heightmap sin hojas)
  dungeon/                   H25: Compat (estructuras de otros mods, jefes), FlatLands (biomas llanos), DungeonData, DungeonBuilder,
                             Dungeons (guaridas y túmulos), Barrows (draugr, puerta de las garras, Señor del Túmulo), Bandits
                             (asaltos a envíos), Rumors (Tabernero: rumores y contratos), DungeonLoot, CompatPacks; event/DungeonEvents
  gametest/H1..H22, Path      pruebas automáticas (H20GameTests: ejércitos) (H22GameTests: construir a mano) (H21GameTests: crecimiento) (H19GameTests: taberna/entrenamiento) (H18GameTests: trabajadores) (H17GameTests: asedios) (H16GameTests: aldeas como Campamentos) (H15GameTests: invasiones) (H14GameTests: caminos) (H12GameTests: mejoras) (H11GameTests: panel) (H10GameTests: economía) (H9GameTests: batallas) (runGameTestServer / probar.bat) — H5 es solo cliente, sin GameTest
src/main/resources/data/warbandcraft/warband_kits/*.json      una clase por archivo
src/main/resources/data/warbandcraft/warband_factions/*.json  una facción por archivo
src/main/resources/data/warbandcraft/warband_armament/*.json  H10: valor en armamento de cada pieza de metal
src/main/resources/data/warbandcraft/structures/castle_default.nbt  castillo (H4), 43x28x45, solo bloques vanilla
```

## Formato de una clase (`warband_kits/<id>.json`)

```json
{
  "name": "Arquero",                 // lo que ve el jugador
  "entity": "recruits:bowman",       // entidad de Recruits con la IA correcta
  "order": 30,                       // orden en el bastón
  "tiers": [                          // de básico a élite
    {
      "name": "Básico",
      "min_level": 0,                // nivel de experiencia del soldado (Recruits)
      "equipment": { "mainhand": "minecraft:bow", "chest": "minecraft:leather_chestplate" },
      "inventory": [ { "item": "minecraft:arrow", "count": 32 } ],
      "extra_cost": [],              // se cobra pero no se entrega (p. ej. heno del caballo)
      "horse": false                 // true = recibe caballo ensillado
    }
  ]
}
```
Costo de un nivel = equipo + inventario + extra_cost. Un ítem inexistente desactiva ese nivel (no rompe nada).

## Formato de una facción (`warband_factions/<id>.json`)

```json
{
  "name": "Clanes del Norte", "of_name": "de los Clanes del Norte", "soldier_suffix": "del Norte",
  "order": 10,                          // si un bioma coincide con varias, gana el menor
  "color": "dark_aqua",                 // ChatFormatting: nombre y equipo del marcador (wb_<id>)
  "armor_color": "#5B5E63",             // tinte del cuero
  "banner": { "base": "gray", "patterns": [ { "pattern": "minecraft:circle", "color": "cyan" } ] },
  "biomes": ["#minecraft:is_taiga", "minecraft:snowy_plains"],
  "garrison": { "min": 5, "max": 8 },
  "troops": [
    { "name": "Hachero", "kit": "warbandcraft:infante", "tier": 0, "weight": 5,
      "entity": "recruits:...",          // opcional: otra entidad que la del kit (p. ej. recruits:nomad)
      "equipment": { "mainhand": "minecraft:iron_axe", "offhand": "none" },  // pisa piezas del kit; "none" la saca
      "inventory": [ ... ], "horse": true }                                    // opcionales: pisan los del kit
  ]
}
```
Aldea = campana (POI `meeting`). Se buscan cada 5 s a 96 bloques de cada jugador; campanas a <48 bloques son la misma
aldea. Guarnición: sin dueño, `despawnTimer = -1`, persistente, `restrictTo(campana, 32)` (se reaplica al cargar),
marcas en persistent data `warbandcraft:faction` y `warbandcraft:village` (campana como long). No se contrata
(EntityInteract cancelado). Vanilla no genera aldeas en pantano/jungla: la Liga también toma sabana.
Soldados armados con el Bastón quedan en `setFollowState(1)` (seguir al dueño); Recruits los contrata en 2 (quieto).
Estados de aggro de Recruits: 3 = pasivo (no ataca). `SiegeEngineerEntity.finalizeSpawn` tira ClassCastException
(bug de Recruits): se atrapa al crear guarniciones.

## Ataques (H3)

Golpe de un jugador o de su tropa a un soldado de guarnición → `RaidManager.onGarrisonAttacked` (empieza el ataque,
-10 de opinión, toda la guarnición contra el atacante). Equipo de los caídos → `raid.fallenGear` (se saca en
`LivingDeathEvent`). Sin guarnición viva y atacante a <128 bloques → `WON` y botones de chat (`/warband saquear|conquistar`).
Saquear: botín al Campamento vía `CampRegistry.deliver`, aldea `lootedUntil` = +3 días. Conquistar: `owner` = jugador,
impuestos diarios (`VillageEconomy.checkNewDay`). Opiniones y umbrales: constantes en `Relations`.
Desde H9 los ataques en curso se guardan (`RaidData`) y siguen al volver a abrir el mundo.

## Tu facción (H6, a probar — plan en `plan-h6-tu-faccion.md`)

- Nombre, colores y estandarte se editan en la pantalla de facción de **Recruits**; `faction/FactionStyle` los lee
  (`RecruitsFaction.getBanner()`, `unitColor` → tabla RGB copiada porque su lista vive en una clase de pantalla) y
  `ClassAssigner` los aplica al armar. La facción se busca por el equipo del marcador del soldado. `/warband uniformar`
  repinta a tus soldados a 32 bloques.
- Cofres vinculados: agachado + Bastón a un cofre/barril (`RandomizableContainerBlockEntity`) a ≤16 bloques del
  Campamento, máx. 16 (un cofre doble = 2). `CampBlockEntity.count/take/insert/forEachStack` recorren cofre propio +
  vinculados cargados, así armado, botín e impuestos los usan solos. Un vinculado roto se olvida al usarse.
- Guardado por jugador: `faction/PlayerFactionData` (doctrina, nombres de clase, kits propios + versión por clase).
- Pestañas del Campamento: `CampMenu` (ChestMenu con tipo propio) · `DoctrineScreen` (Screen lisa, datos por
  `OpenDoctrineMessage`) · `KitEditorMenu` (espacios fantasma; botones por `clickMenuButton`). Cambiar de pestaña =
  `CampTabMessage` → `CampTabs.open` en el servidor. Solo el dueño ve Doctrinas/Kits.
- Kits: todo lo que arma pasa por `PlayerKits.resolve` (JSON + nombre/kit propio). `PlayerKits.validate` protege la IA
  (bowman/nomad = arco, crossbowman = ballesta, resto sin arco; flechas en la mochila). Guardar sube la versión y
  `PlayerKits.rearmNear` re-arma (`ClassAssigner.rearm`: mismo nivel, sin tocar órdenes, sin materiales no hace nada);
  `MyFactionEvents` reintenta cada 10 s cerca de los Campamentos. Marca en el soldado: `warbandcraft:kit_version`.
- Doctrinas (`faction/Doctrine`, números como constantes): modificadores transitorios con UUID fijo, se ponen en
  `EntityJoinLevelEvent` y al armar. Caballería = caballos con `warbandcraft:owner`; Arquería = daño de flecha al aparecer;
  Ingeniería = mecha/explosión del TNT (`ExplosionEvent.Start` reemplaza la de vanilla) y cadencia del cañón.
  1ª gratis, después 20 esmeraldas del almacén + 1 día (`ChooseDoctrineMessage.choose`).
- Zapador (`sapper/`): `SapperGoal` se suma a todo `SiegeEngineerEntity`/clase zapador al aparecer; órdenes por marcas
  `warbandcraft:blast` / `warbandcraft:deploy` (vista aérea: botones Volar / Cañón → `SapperOrderMessage`). Camina con
  setHoldPos + estado 3. Solo contra aldeas/castillos que no son tuyos (`SapperRules.enemyTarget`, 48 bloques); usarlo
  arranca el ataque. `FieldCannonEntity`: tira `PrimedTnt` en arco (`launchVelocity`, física exacta del TNT), 40 bloques,
  necesita a su Zapador a ≤5 bloques con TNT; cuesta 3 lingotes + 1 bloque de hierro; agachado + click lo desarma.

## Barracones (H8, a probar — plan en `plan-h8-barracones.md`)

- Nombre del Campamento: `CustomName` del `BaseContainerBlockEntity` ("Campamento N" al ponerlo, o el del yunque;
  la loot table lo copia al romperlo). `CampRegistry` guarda nombres por posición para cuando no está cargado.
  Se renombra en la pestaña Barracón (`RenameCampMessage`).
- Soldado atado a un Campamento: marca `warbandcraft:camp` (long) + `setUpkeepPos` de Recruits (acepta cualquier
  `Container`). Se pone al armar (`ClassAssigner.outfit`) y al guardar; `Barracks.campOf` ata al más cercano si falta.
- Ficha (`BarracksData.Soldier`, SavedData por dimensión): NBT completo con `id` y **con su UUID** (los grupos de la
  vista aérea lo siguen reconociendo), caballo aparte sin `Passengers`, si era guardia y su puesto.
  Guardia = estado 2/3/4 de Recruits y a ≤3 bloques de su `HoldPos`. Al sacar: `setFollowState` antes que
  `setHoldPos` (el 2 pisa el puesto). Los demás salen siguiéndote (estado 1).
- Comida: 4 puntos por soldado y día, del almacén entero, la más barata, nada dañino; días atrasados (máx. 7) al
  cargarse el Campamento; sin nadie de la facción del dueño conectado no comen (`Barracks.tick` corre `lastFedDay`, pedido de Ale).
  2º día sin comer: se va el 10% (mín. 1), menor nivel primero. Constantes en `BarracksData`.
- "Al barracón" trabado (pedido de Ale, a probar): `Barracks.checkArrivals` (1 s) anota lo más cerca que llegó cada uno
  (`GOING`, en memoria); si en `STUCK_SECONDS` (10) no se acercó `STUCK_PROGRESS` (1) bloque, se guarda directo desde donde
  está. Otra orden en el camino (estado ≠ 2/3 o puesto ≠ el Campamento) le saca `TAG_TO_BARRACKS`.
- Automático: dueño a >96 bloques de un soldado que no lo sigue ni pelea → se guarda. Guardias vuelven a su puesto
  con el dueño a <80 del puesto. `BarracksEvents` no corre en `GameTestServer`: las pruebas llaman a `Barracks` a mano.
- **Lo de la facción es de todos** (pedido de Ale, a probar): ver "Compañeros de facción" más abajo.
- Jurisdicción = `CampBlockEntity.WORK_RADIUS` (24): armar, vincular cofres y "Guardar a los de cerca" usan ese radio.
- Vista aérea: `AerialCampsMessage` (lista de tus Campamentos, se pide al entrar y al abrir la lista) dibuja nombre +
  barracón sobre cada uno; click izquierdo en uno = seleccionado, se dibuja su jurisdicción. Click derecho en uno con
  soldados elegidos, o botón Barracón → lista, = `ToBarracksMessage` (null = cada uno al suyo; si no, los ata a ese).
- En la estructura `empty` de los GameTests el piso de piedra está en y=1 relativo: un puesto en y=1 queda adentro.

## Compañeros de facción (pedido de Ale, a probar: "todos los campamentos, aldeas y soldados de una facción tienen que ser manejables por todos los miembros por igual")

- `faction/Allies`: misma facción de Recruits = mismo equipo del marcador (por nombre; desconectado: `ProfileCache`). `same(a, b)`
  (mismo jugador o misma facción; null → false), `members(p)` (él + los del equipo), `factionOf` (cache 5 s; el servidor se anota
  en `ServerAboutToStart`). Cliente: `sameTeam(player, owner, entity)` (equipo del soldado = el tuyo).
- **Todo "¿es tuyo?" pasa por ahí**: `CampBlockEntity.canBeUsedBy` (todas las pestañas), `CampRegistry.campsOf/isCampOf/hasCamp/
  nearestCamp` (lo de todos los miembros), aldeas (`Allies.same(player, v.owner())`: Gestión, mejoras, Panel, mercado, taberna,
  almacén, rutas, mapa, vista aérea), rutas/envíos (`RouteData`), ejércitos (`ArmyData.of`, `Armies.get`), órdenes a soldados
  (vista aérea, Batalla, zapadores, al barracón; el cliente elige también a los de tu equipo), quién es amigo en peleas
  (`Quarters`, `Morale`, `Escort`). Al **sacar** del barracón, **armar** con el Bastón u ordenar **seguir/proteger** (estados
  1/4/5) a un soldado de un compañero, pasa a ser del que lo pidió (`Barracks.handTo`: Recruits sigue siempre a su dueño).
- **Zonas de Villager Workers**: Workers no deja tocar contenedores/bloques a ≤8 de una zona suya (`VillagerEvents.disableInteractionInPermissionArea`,
  también en el cliente: ahí el click ni llega al servidor) salvo dueño o `teamAccess` + mismo equipo. `WorkerZone.share` abre al equipo
  del dueño toda zona que creamos; `CommonEvents` lo hace con las de un compañero antes de cada click (HIGHEST) y cada 5 s a ≤48 de cada
  jugador (zonas viejas, o de alguien que entró después a la facción). Era el "mi compañero no puede ni abrir el Campamento".
- No compartido: kits y doctrinas (cada jugador tiene los suyos), datos internos del Mercado (`MarketData.owner`), qué aldea elige
  la IA para invadir, la Hueste (es contra un dueño, aunque el aviso y el mapa los ven todos), `VillageData` al cambiar de dueño
  (cambiar entre compañeros también vacía el almacén de números).

## Rutas y viaje (H13, a probar — plan en `plan-h13-rutas.md`)

- Las rutas se dibujan en el mapa de **Recruits** (M), que las guarda en la compu del jugador
  (`ClientManager.getRoutesList()`, `RecruitsRoute`/`Waypoint`). La pantalla de Rutas las lista y "Registrar" manda
  los puntos al servidor (`RouteActionMessage`); `Routes.register` valida y guarda en `RouteData` (por dimensión, id
  = el de Recruits).
- Puntos tuyos = Campamentos (`CampRegistry`) + aldeas/castillos con `owner` = vos. Extremo a ≤32 bloques
  (`ENDPOINT_RADIUS`). Explorado = chunk generado completo (cargado, o en disco con estado full vía
  `chunkMap.read` + `ChunkSerializer.getChunkTypeFromTag`), revisado cada 8 bloques del trazado.
- Ruta activa = sus dos extremos siguen siendo tuyos. Destinos = Dijkstra sobre rutas activas (encadenadas).
  Tiempo = largo / 4,3 b/s × 0,2 sin camino (× 0,1 con `road`, que pone H14 al terminar el camino), mínimo 2 s por tramo.
- Viaje (`Travel`, en memoria): quieto (>2 bloques = se corta), sin daño (`LivingAttackEvent`), Esc = cancelar
  (`TravelScreen.onClose` → CANCEL). Al llegar: jugador + caballo + soldados tuyos que lo siguen (estado 1/5) a ≤32.
- Envíos (pestaña Envíos, `ShipmentMenu`: caja de 18 + `SendShipmentMessage`): fichas del barracón + ítems, con
  llegada por `getGameTime`. A un Campamento: entran a su barracón sin puesto; ítems cuando el Campamento está
  cargado. A una aldea/castillo (solo soldados): fichas de guardia (estado 3) con puestos en anillo alrededor del
  centro, en el barracón del Campamento más cercano → salen solos al pasar cerca (lo de H8). Capacidad: barracón
  de destino + lo que ya va en camino. `RouteEvents` no entrega envíos en `GameTestServer`: las pruebas lo hacen a mano.
- `Barracks.create` crea un soldado desde una ficha en cualquier lugar (lo usa `spawn` y los envíos).
- Poste de caminos (`route/RoutePostBlock`, receta: cartel + brújula): click derecho abre Rutas como si estuvieras
  en el punto tuyo más cercano al poste (≤32). `OpenRoutesMessage.post` / `RouteActionMessage.post` lo llevan ida y
  vuelta; el servidor solo lo acepta con el jugador a ≤8 bloques (`RoutePostBlock.usable`) y viaja desde ahí
  (`Travel.start(player, to, from)`).
- Mapa de Recruits (M): `client/route/MapMarkers` dibuja tus Campamentos/aldeas/castillos con nombre
  (`MapPointsMessage`, se pide al abrir el mapa). Lee `offsetX/offsetZ` de `WorldMapScreen` por reflexión
  (pantalla = offset + bloque × `WorldMapScreen.scale`); si cambian en otra versión de Recruits, no dibuja nada.

## Panel de control (H11, a probar — plan en `plan-h11-panel.md`; decisiones tomadas sin Ale, a revisar)

- Tecla K / `/warband panel`. `panel/Panel` arma los datos (solo la dimensión del jugador) y valida las órdenes;
  `OpenPanelMessage` (servidor → cliente, tab + detalle opcional) y `PanelActionMessage` (cliente → servidor,
  después de cada orden manda el panel de nuevo). `client/panel/PanelScreen` + `PanelClientHandler`.
- Pestañas: Aldeas y castillos · Campamentos · Rutas · Envíos; resumen arriba (`Panel.totals`).
- Almacén de un Campamento sin cargar: última cuenta en `CampRegistry.stats` (índices de `CampStats`), se refresca
  al abrir el panel y cada 10 s en `BarracksEvents` (`Panel.refreshStats`). Días de comida = esa comida / soldados de hoy.
- Detalle de un Campamento (Ver): filas de `OpenBarracksMessage.rows/prisonerRows`. Sacar / Guardar a los de cerca
  solo con el Campamento cargado y el jugador a ≤ `NEAR_DISTANCE` (96, la del guardado automático). Reclutar,
  Rescate y Mandar soldados (envío de H13 a un destino elegido con ◀ ▶) desde cualquier lado.
- Aldeas: guardias = fichas de guardia con puesto a ≤48 del centro + soldados cargados de guardia ahí
  (`Panel.guardsAt`). Mandar = almacén al Campamento conectado más cercano en tiempo (`Panel.nearestCamp`).
- GameTests: `relativePos` de vanilla da la posición espejada; usar `pos.subtract(h.absolutePos(BlockPos.ZERO))`.

## IA viva (H15a, a probar — plan en `plan-h15-ia-viva.md`; decisiones tomadas sin Ale, a revisar)

- Día nuevo (`VillageEconomy.checkNewDay` → `Invasions.onNewDay`): cada aldea/castillo de facción (sin dueño, no de
  prueba, no saqueado) junta su almacén en `VillageData.stocks` (mismo `daily`/`cap` que las tuyas, sin barril); cada
  facción suma leva (2 × asentamientos, tope 40, `InvasionData`); las facciones en guerra con un dueño de aldeas tiran
  35% de mandar un ejército (no en `GameTestServer`).
- Robo: `RaidManager.sack/conquer` se llevan ese almacén (`Invasions.stealStock`, antes de que `VillageData.add` lo vacíe)
  al Campamento más cercano, con una línea más en el mensaje.
- Invasión (`InvasionData.Invasion`, una por aldea): MARCHING con cuenta de 5 min (`warn` + al entrar + Panel en rojo).
  Al llegar: dueño a ≤100 y aldea cargada → `startFight` (tropas de la facción a 30 bloques del lado de `from`, marca
  `warbandcraft:invader` + `TAG_VILLAGE` = la aldea, `restrictTo` 16; pelean por la guerra de `GarrisonDefense`);
  si no → `autoresolve` (ataque = tamaño × 1,2 × azar; defensa = milicia + fichas de guardia a ≤48 por defensas de H12
  × azar). Pedido de Ale (a probar, números sin Ale): cada soldado vale según equipo y clase (`battle/AutoPower`: armadura,
  daño del arma, escudo, arquero ×1,2 defendiendo / ×0,7 atacando; soldado "normal" = 1; a pie, el caballo no cuenta).
  Fichas: equipo del NBT de Recruits (`Items`, espacios 0-5); invasores y guarniciones de la IA: promedio de las tropas de
  su facción (`AutoPower.faction`). También en `AiWars.resolve/defense`. Pelea: sin invasores = ganaste; sin defensores (vos o tus soldados a ≤64) los que entraron van al almacén
  (`SiegeLanes.Host.objective` → `Invasions.storehouse`) y uno a ≤3 de él = `lose`: la aldea vuelve a
  la facción (sin dueño) y los invasores quedan de guarnición. Aldea descargada en medio = se autoresuelve lo que quede.
- Invasor que se carga sin su invasión peleando: si la aldea es de su facción queda de guarnición; si no, no entra.
- `/warband prueba invasion [cantidad]`: guerra con la facción más cercana y ejército contra tu aldea más cercana (llega en el acto, ver H17).
- **Parte de guerra** (pedido de Ale: "me fui con una aldea y al volver la perdí"): `invasion/WarReports` (SavedData del overworld,
  últimos 60). Cada final de invasión (perdida / resistida autoresuelta / rechazada / se retiraron) anota hora real (Argentina),
  día del juego, lugar, facción, `Invasion.attackers` (total, con lo que suma la Hueste) contra `peakDefenders` (o las fichas de
  guardia) y caídos; va al log ("Parte de guerra: ...") y a los de la facción del dueño (`Allies.members`): conectado en el acto,
  si no al entrar (`InvasionEvents.onLogin`). `/warband partes` = los últimos 10.
- H15b (a probar): `InvasionData.March` = campaña de una facción contra otra (guerras en `wars`, "a|b" → día de fin);
  `AiWars.onNewDay` declara/termina guerras y lanza marchas, `AiWars.tick` las resuelve al llegar (números, o batalla real
  si estás cerca); `take` cambia la facción de la aldea y deja `pendingGarrison` (aparece al cargarse). Posición de un
  ejército = `AiWars.position` (lerp por tiempo); `MapPointsMessage.armies` → `MapMarkers` (rombo + línea de puntos).
  Emboscada: `Travel.tick` cada 1 s compara tu posición de viaje (por el trazado de la ruta) con los ejércitos enemigos
  (`Ambush.find`, 48 bloques) → `arrive` ahí + `Ambush.spring` (marca `warbandcraft:field_army`; sin jugadores a 128,
  `giveUp` los devuelve a la leva). Patrullas (`Patrols`, marca `warbandcraft:patrol`): `setHoldPos` + estado 3 por 4
  puntos a 56 bloques y vuelta; no se guardan; pegarles = -5 de opinión, no ataque (`RaidEvents.onHurt`).
  Esmeraldas (pedido de Ale, para reclutar al comienzo): un soldado de patrulla (cualquiera con `TAG_PATROL`) que cae a
  manos tuyas o de tus soldados suelta `DROP_EMERALDS_MIN..MAX` (1-2, bandidos +1) en el piso (`Patrols.dropEmeralds`,
  desde `RaidEvents.onDeath`), además de la bolsa de `BattleLoot`.
  `/warband prueba guerra`: 10 soldados de otra facción contra la aldea de facción más cercana (1 min).
  **Patrullas errantes** (pedido de Ale, a probar; números sin Ale): en `Patrols.spawnNearPlayers` (cada 1 min), por jugador
  `ROAM_CHANCE` 35% si tiene <`ROAM_MAX_NEAR` (2) cerca: 4-8 soldados a 72-120 bloques (cargado, tierra, a >64 de una aldea);
  20% bandidos, si no la facción del bioma (60%) o cualquiera. Van a un asentamiento suyo a 120-500 o de largo pasando cerca
  tuyo (220-400). Marca `warbandcraft:patrol_roam` (destino) + `patrol` = donde aparecieron (grupo); sin `restrictTo`.
  Tramos de 20 bloques en filas de 3 (`roamStep`, `LEGS` en memoria; no llegan en 60 s → tramo girado, esquivan agua); si uno
  pelea, esperan. Se pelean con bandidos y facciones en guerra (`AiWars.enemies` → `Patrols.factionsAtWar`). Aviso en la
  barra al aparecer. No se guardan, como las otras. `/warband prueba patrulla [facción|bandidos]` (a ~50 bloques).
- Decidido con Ale (a probar): **batalla real entre facciones** si hay un jugador a ≤100 al llegar (`AiWars.startBattle`:
  atacantes con `TAG_VILLAGE` = la aldea + marca `warbandcraft:ai_attack`; `GarrisonDefense.EnemyFactionGoal` con
  `AiWars.enemies`; `battleTick` cada 1 s; descargada = `resolve` con `March.size/defenders`). Meterse:
  `RaidEvents.onHurt` → `AiWars.onHit` (−5 al bando golpeado, sin ataque tuyo; `helpedAttack/helpedDefense` → +10 del
  ganador). Al cargar: atacante sin batalla o guarnición de una aldea que ahora es de otra facción → no entra
  (`InvasionEvents`). **Reconquista**: `Invasions.reconquer` (15%/día, facción en paz con vos y aldea tuya que era suya
  → opinión −50 + invasión contra esa; `/warband prueba reconquista`). **Emboscadas por la ruta real**: `Travel.Path`
  (puntos de las rutas de `Routes.Destination.path`, tiempo de cada ruta repartido por largo).

## Aldeas como Campamentos (H16, a probar — plan en `plan-h16-aldeas-campamento.md`; pedido de Ale, detalles a revisar)

- El almacén de una aldea/castillo tuyo es un **bloque de Campamento** (`VillageStorehouse.ensure`, reemplaza el barril
  de H10 y le pasa lo que tenía) con `CampBlockEntity.village` = centro. Todas las pestañas de un Campamento.
  `village/VillageCamps`: `villageOf(pos)` (Campamento → aldea), `campOf(v)`, `canonical`, `campFor`, `capacity`
  (aldea 80 / castillo 40, +20 con el Cuartel = 100 / 60; `Barracks.capacity` lo usa).
- Producción de la aldea → ítems en ese Campamento (lo que espera descargado, como números). `VillageStorehouse.tick`
  (5 s, no en `GameTestServer`) lo pone y lo saca si la aldea ya no es tuya (`lose`: recursos → almacén de la facción,
  barracón borrado, `release()` + bloque fuera). `Invasions.lose` llama a `forget`. No se rompe (salvo creativo) ni explota.
- Nombre: el de la aldea = el de su Campamento (`VillageCamps.displayName`: si sigue con `nameFor`, el de siempre con
  coordenadas). Se renombra en la pestaña Barracón o desde el Panel (K): detalle de un Campamento / Mejoras de una
  aldea (`PanelActionMessage.RENAME_CAMP/RENAME_VILLAGE`, desde cualquier lado). Descargado: `CampBlockEntity.renameAt`
  lo deja en `CampRegistry` y el bloque lo toma en `onLoad` (el registro manda).
- Rutas: el Campamento de una aldea ES la aldea (`Routes.nodes` no lo lista; `node/nodeNear/reachable` lo traducen;
  `homeCamp(aldea)` = su Campamento; envíos a una aldea con Campamento llevan ítems). `MapPointsMessage` no lo dibuja aparte.
- Invasiones: todo el barracón de la aldea cuenta como guardias (`Invasions.guardsAt`).
- Modo Gestión (vista aérea V): botón arriba a la izquierda si tu cuerpo está a ≤64 de una aldea tuya
  (`AerialCampsMessage.villages`). `client/aerial/ManagePanel` (Resumen · Aldeanos · Mejoras · Rutas; resalta la casa
  del almacén y los aldeanos libres; click en uno lo elige). `ManageActionMessage` (OPEN/REFRESH cada 2 s/ASSIGN/BUILD/
  SEND_STOCK) → `ManageMessage` con `VillageManagement.Data`. Asignar = aldeano libre (sin oficio o tonto) → entidad de
  Villager Workers tuya (estado 0), zona de almacén sobre el Campamento de la aldea (`WorkerZone.create`) y, al Granjero,
  `CropArea` sobre la huerta más cercana sin zona (`findFarm`: tierra de cultivo + agua unidas, ≤24 de lado).
  Herrero = `worker/SmithEntity` (entidad nuestra `warbandcraft:smith`, hereda de `AbstractWorkerEntity` de Workers: no copia
  código; atributos de `CookEntity.setAttributes`, se dibuja con `WorkerVillagerRenderer`; `Soldiers.isWorker` lo cuenta).
  `SmithWorkGoal`: de día a su `workplace` (al lado de un yunque), cada 30 s 1 hierro + 1 carbón del Campamento `camp` → 2 de
  armamento, máx. 8/día. Máx. 4 herreros por almacén contando los aldeanos herreros de H10 (`VillageManagement.smithsOf`).
- Pestaña Casas del modo Gestión (`village/Housing`, va en `VillageManagement.Data.housing`): aldeanos sin cama (sin
  memoria `HOME`), sin trabajo (`isFree`), ambas cosas; camas y puestos de trabajo libres (POI `home` /
  `acquirable_job_site`, radio `RADIUS`); camas que faltan = sin cama + chicos − libres. En el mundo: rojo = sin cama,
  verde = cama libre, cuadrado punteado = terreno 9×9 sugerido (`Blueprints.findSite` 8-48, sin pisar obras de H12, 1 min).
  Casas automáticas (pedido de Ale; prendido de entrada, botón SÍ/NO en Casas → `ManageActionMessage.HOUSES_AUTO`,
  `TownData.Town.autoHouses`): `Housing.autoTick` cada 30 s (no en `GameTestServer`), aldea tuya cargada sin obra, faltan
  camas y el almacén tiene 10 esmeraldas → obra de H12 `Upgrade.HOUSE` (no `listed()`: no sale en Mejoras ni queda
  "hecha", suma `Town.houses`; terreno `sites["house_N"]`; 7×7 con 2 camas, puerta y farol). Botón "Construir una casa
  ahora" = `BUILD` con id `house` (paga almacén y después inventario). Una obra por aldea: espera a las Mejoras.
- Zonas de trabajo (`village/WorkZones`): pestaña Trabajo del modo Gestión: Zona = arrastrar un rectángulo en el piso
  (herrero: un click, pone/usa un yunque) → `mark` crea la zona de Workers del oficio (cultivo, forestal 24 alto, mina 8
  hacia abajo, pesca con agua, corral con el animal que más hay, cocina) y la engancha al trabajador (`currentCropArea`...);
  Auto = `suggest` (huerta, árbol con hojas, agua, la casa del almacén, Herrería de H12 o yunque al lado del almacén; mina y
  corral, a mano); al asignar un oficio se llama sola. Borrar = `delete`. Workers arma la caja 1 bloque más corta de lo que
  dice el tamaño: `WorkZones` y `WorkerZone` le suman 1 (y `WorkerZone.covers` exige cubrir la caja entera).
  `WorkerZone.place` prueba la entidad en x0/x1+1 y z0/z1+1 (antes solo x1/z1: los rectángulos no cuadrados daban siempre
  "No se pudo crear la zona"). Corral (pedido de Ale, a probar): sin animales adentro, el animal sale del bioma
  (`WorkZones.biomeAnimal`: montaña cabras, desierto/badlands camellos, sabana caballos, taiga ovejas, nieve conejos, selva
  gallinas, bosque/pantano cerdos, resto vacas) y aparecen `PEN_START_ANIMALS` (2). Auto del corral (y de la huerta si no hay
  una hecha) = terreno libre de 9×9 cerca del almacén (`WorkZones.freeSite`).
- Costo de una zona (`village/ZoneCosts`, sale del almacén de la aldea; si falta algo no se crea y avisa "Falta en el
  almacén: ..."): granjero nada (pedido de Ale: azada y semillas le aparecen gratis, `WorkerSupply.farmerSupply`; sin cofre de zona); leñador hacha + 4 retoños + cofre; minero pico + cofre; pescador
  caña + cofre; ganadero 8 trigo + cofre; cocinero nada; herrero 1 yunque si hay que poner uno nuevo. La herramienta va
  al trabajador; semillas/retoños/trigo, al cofre de la zona.
- Cofre de zona: al crear una zona (menos cocina) se pone un cofre pegado, del lado del almacén y adentro del territorio, con
  su `StorageArea` (`WorkerZone.storage`) y vinculado al Campamento de la aldea (máx. 48 vinculados); marcas en la zona
  `warbandcraft:zone_chest`/`zone_storage`; al borrar la zona, lo del cofre pasa al almacén y el cofre se saca. La huerta
  arranca con las semillas que pagó; el leñador replanta (`setReplant`, retoño del árbol que más hay). El herrero se muda solo a la
  Herrería de H12 (`WorkZones.smithyPlace`, cada ~10 s en `SmithWorkGoal`) salvo que le marques el lugar a mano.
- Territorio (`village/Territory`, SavedData): si la aldea ya está dentro de un claim de la facción de Recruits del dueño
  (`factionOf`: equipo del marcador, aunque esté desconectado), ESE claim es su territorio (se adopta y se sigue: lo que
  se cambie desde Recruits, el territorio lo copia cada 5 s; un claim nuestro vacío de antes se borra). Si no, al conquistar
  5×5 chunks libres alrededor del centro; +1 chunk pegado por 10
  esmeraldas (almacén y después inventario) hasta 50; se borra al perder la aldea. Si el dueño está conectado y tiene
  facción de Recruits, es un claim de Recruits (`ClaimEvents.recruitsClaimManager`, se ve en el mapa M). Es la jurisdicción
  del Campamento de la aldea: `CampBlockEntity.covers/area` (armar con `CampFinder` primero el de la aldea, vincular cofres,
  guardar soldados, herreros, trabajadores); las zonas de trabajo tienen que estar adentro. Gestión → Resumen: "Agrandar
  territorio" y click en un chunk pegado (verde/rojo); el borde se dibuja celeste.
- **Todo claim de Recruits necesita jugador** (`setPlayer`): si no, Villager Workers tira NullPointerException en
  `ClaimEvent.Updated` (les pasa ese jugador a las zonas de adentro) y Recruits no termina de guardar el cambio (desde el
  mapa M "se borran" chunks). `Territory.ensurePlayer` lo pone (aldeas, cada 5 s) y `event/ClaimFixEvents` (prioridad
  HIGHEST, antes que Workers) le pone el líder de la facción a cualquier claim sin jugador.
- **Cuartel** (pedido de Ale, a probar; números a revisar): `village/Quarters`. Además del barracón, cada aldea/castillo
  tuyo tiene un cuartel = soldados que patrullan solos adentro. Lugar aparte del barracón: aldea 10 / castillo 15, ×2 con
  la mejora Cuartel. Guardados = `BarracksData.Barracks.quarters` (comen con el barracón: `eaters()`, desertan igual,
  defienden en la autoresolución). Sueltos = marca `warbandcraft:quarters` (centro): salen con el dueño a ≤96 del centro
  (`deployIfNear`), se guardan con el dueño a >128 del centro (`Barracks.autoStore` → `store` los manda a `quarters`).
  Patrulla (`Quarters.tick`, 2 s en `BarracksEvents`): punto al azar del territorio a ≤48 del centro, `setHoldPos` +
  estado 3, espera 5-15 s, a los 60 s sin llegar cambia. Arqueros/ballesteros a pie (`BattleGeneral.roleOf` RANGED) →
  puestos de torre (`Quarters.posts`, cache 1 min): aldea con mejora Torres = `towerPosts` (escalera más alta de cada
  columna en el anillo ±4; techo = escalera.relative(FACING).above sobre ladrillos; 4 por torre); castillo =
  `BattleGeneral.wallPosts`. A ≤8 del puesto se los teletransporta arriba (no trepan escaleras). Sin puesto, patrullan.
  Prioridades (decididas con Ale): 1) enemigos adentro de la aldea (`enemiesIn`: territorio o ≤56 del centro; illagers,
  `Enemy`, invasores/`ai_attack`, facción en guerra con el dueño, quien ataque al dueño/sus soldados/aldeanos, jugador que
  les pegó hace <10 s), 2) bichos `Enemy` a ≤16 (arquero de torre: todo a ≤32, sin bajarse), 3) patrulla/torre, 4) tu
  orden nunca. Pelear = `setTarget` + estado 0 (de a pie); agresión forzada a 0 (neutral) con `setAggroState`.
  **Creepers nunca** (ni siquiera si Recruits los elige: se les saca el objetivo). `worthFighting`: nada >4 bajo el piso
  (heightmap, cuevas) ni a >12 de altura del soldado; si en 15 s no se acercó 1 bloque lo deja 1 min (`CHASES/IGNORED`).
  Antes de esto se quedaban persiguiendo bichos de cueva que Recruits elegía y no patrullaban.
  **No los maneja el jugador**: otra orden (estado ≠ 3 o puesto distinto) se deshace al tick siguiente y se les saca
  `TAG_BATTLE`; solo con una invasión FIGHTING en la aldea (`underInvasion`) no se los toca, y al terminar vuelven solos.
  Aldea perdida = deja el cuartel quieto (estado 2). Gestión → pestaña Tropas: filas por clase con →1 →T ←1 ←T
  (`ManageActionMessage.TO_QUARTERS/TO_BARRACKS`, text = fila o "*"); al barracón se les borra la marca en la ficha.

## Mazmorras y biomas llanos (H25, a probar — plan en `plan-h25-mazmorras.md`; decidido con Ale, números sin Ale)

- **Compatibilidad** (`dungeon/Compat`): `foreignStructureAt` (pieza de una estructura que no es aldea; nunca carga
  chunks) y `surfaceStructureIn` (rectángulo, cada 8). Campana adentro de una estructura ≠ aldea (`VillageManager.scan`);
  castillos (`CastleManager.findSite`), campos de batalla (`Armies.fieldProblem`, `Hosts.measure`) y obras
  (`Placement.check`) esquivan estructuras y nuestras mazmorras (`DungeonData.near` + `Dungeons.FOOTPRINT`). Jefes =
  tag `warbandcraft:no_auto_target` (`#forge:bosses` + los de Dungeon Now Loading): `Quarters.isEnemy` no los elige
  salvo que ataquen a lo tuyo.
- **Biomas llanos** (`dungeon/FlatLands`, pedido de Ale: más praderas/desiertos/sabanas, no aplastar): pisamos
  `minecraft:overworld/erosion` con `warbandcraft:flat_bias` (erosión + `EROSION_SHIFT` 0,2). Terreno y biomas salen de
  la erosión, así que quedan llanos de verdad. Solo mundos nuevos: `ServerAboutToStart` lee/escribe
  `warbandcraft_terreno.txt` en la carpeta del mundo (mundo ya abierto antes = 0, como vanilla) y no cambia nunca.
- **Estructuras por bioma** (`dungeon/StructureDensity`, pedido de Ale: muchas donde no se puede pelear una hueste, casi nada en
  lo llano): en `LevelEvent.Load` del overworld se cambian (solo en memoria) los structure sets de `nova_structures`, `structory`,
  `structory_towers` (no sus aldeas ni Nether/End): la lista `possibleStructureSets` de `ChunkGeneratorStructureState` y el mapa
  de `/locate` (campos buscados por tipo: sirve sin ofuscar y ofuscado) pasan a `StructureDensity.Weighted` (grilla ×
  `SPACING_FACTOR` 0,55 + dado por bioma del chunk, de la semilla: `KEEP_DENSE` 1 en `#warbandcraft:dense_structures`,
  `KEEP_SPARSE` 0,15 en `#warbandcraft:sparse_structures`, `KEEP_OTHER` 0,5). Nuestras mazmorras: `Dungeons.ZONE_CHANCE_*`
  (0,7 / 0,08 / 0,35 según el bioma donde estás), zonas cada `ZONE_SPACING` 160, `MIN_DUNGEON_DISTANCE` 200.
  Mundo ya empezado: lo nuevo que se genera sigue la regla nueva (en el borde con lo viejo puede quedar alguna cortada).
- **Mazmorras nuestras** (`DungeonData`, SavedData del overworld): aparecen como los castillos (`Dungeons.scan` junto a
  `CastleManager.scan`, 1 de cada 3 zonas nuevas, lejos de aldeas/castillos/otras mazmorras/estructuras). Túmulo (70% en
  taiga/nieve/montaña, 20% en el resto) o guarida. `DungeonBuilder`: arriba solo la boca (arco + piedras paradas o
  empalizada); abajo, ≥12 bajo el terreno más bajo de encima: escalera → antesala → pasillo (trampas: placa + dispensador)
  → cripta/campamento (+ cuarto del costado) → puerta → pasillo → trono → tesoro. Cáscaras primero, interiores después.
  Coordenadas locales u (adelante) / v (derecha). Entrás solo o con los soldados que quieras.
- **Guarida**: facción `warbandcraft:bandidos` (`warband_factions/bandidos.json`, `"outlaw": true`: fuera de
  `FactionManager.ordered()`, sin aldeas, `Relations.atWar` siempre). Bandidos = guarnición (`TAG_VILLAGE` = medio de la
  guarida, que no es aldea: pegarles no arranca un ataque; `Dungeons.onBanditHurt` avisa a los de la guarida) + Jefe
  de la banda + presos (`TAG_CAPTIVE`, invulnerables; click = se unen gratis, `Dungeons.free`). Limpia = todos muertos
  (`remaining`, por muertes; o `LOST_SECONDS` sin ver a nadie con todo cargado). Se vuelve a llenar a los `REFILL_DAYS`.
  **Asaltos** (`Bandits.ambush`, al llegar un envío en `Routes.tickShipments`): trazado a ≤`AMBUSH_RANGE` de una guarida
  activa → `AMBUSH_CHANCE` (25%): se llevan `ITEM_SHARE` de cada pila (al cofre del jefe: limpiándola lo recuperás) y
  cada soldado tiene `SOLDIER_LOSS` de perderse. Una tirada por envío (en memoria).
  Pedido de Ale (pegaban de más): todo bandido pega × `Bandits.DAMAGE_FACTOR` (0,7, `ArmyEvents.onHurt`); uno de guarida que
  matás vos o tus soldados suelta 1 esmeralda con `DROP_EMERALD_CHANCE` (35%; los de patrulla ya sueltan 2-3).
- **Túmulo** (`Barrows`, inspirado en Skyrim, todo vanilla): draugr = zombi/stray con cota de malla (marca
  `TAG_DUNGEON`; buscan a tus soldados), ataúdes (nicho + trampillas) que se abren a `COFFIN_WAKE`, guardián con la
  **Garra de oro** (pepita con nombre y lore = combinación), **puerta de las garras** (3 marcos fijos e invulnerables con
  hueso/pluma/bacalao; click gira, `checkDoor` abre los bloques de `door`), **Señor del Túmulo** (esqueleto wither, 160 de
  vida, barra de jefe, a la mitad llama a 2 draugr) que despierta a `LORD_WAKE` del trono. Limpio = el Señor muerto.
- **Tabernero** (`Rumors`, click sin agacharse; agachado el dueño ve la de Workers): pantalla de elegir de H23 (kinds
  `tavern` / `contract` en `BattleChoices.answer`). Rumor (5 esmeraldas, inventario y después el Campamento de la aldea):
  mazmorra nuestra no conocida a ≤1200, o una nueva a 250-700, o una de otro mod (`#warbandcraft:rumored` con
  `findNearestMapStructure`). Trabajo: la facción en paz con la aldea más cercana paga por limpiar una guarida/túmulo
  (esmeraldas + `CONTRACT_OPINION`, plazo `CONTRACT_DAYS`). Mapa M: lo que conocés va como `Muster` (círculo con nombre).
  `/warband mazmorras` lista lo que conocés y tu trabajo.
- Botín: tablas `chests/lair|lair_treasure|barrow|barrow_treasure` (con armamento y un pool fijo de esmeraldas) y el GLM
  `warbandcraft:dungeon_chests` (armamento y esmeraldas: 85%, 2-7, en los cofres de los mods de mazmorras/estructuras y de
  vanilla menos `village`; pedido de Ale: más esmeraldas al principio).
- **Tabernas del mundo** (pedido de Ale, a probar; números sin Ale): `village/WorldTaverns` (SavedData del overworld). Las de
  Dungeons and Taverns (logro "Safe Haven"; etiqueta `#warbandcraft:world_taverns` = `nova_structures:tavern_*`) se anotan en
  `scan` (scan de `VillageEvents`, comienzos de estructura en chunks cargados a `SCAN_CHUNKS`); caja = la pieza `tavern_house`.
  Con un jugador a ≤`ACTIVE_RANGE` (`tick` cada 2 s en `TavernEvents`): Tabernero sin dueño e invulnerable (rumores/trabajos,
  quieto también de noche), `GUARDS` guardias (nivel `GUARD_LEVEL`, puestos afuera de cada costado + 2 adentro; pelean con
  `Enemy` sin creepers/jefes, bandidos y quien le pegó a alguien de la taberna o a sus aldeanos hace <`GRUDGE_TICKS`, también
  a vos; pedido de Ale: los mercenarios que esperan y los reclutas sueltos de cerca también defienden, los mercenarios se pueden
  lastimar mientras pelean y después vuelven a esperar) y `mercs` mercenarios (nivel 1-4, pasivos e invulnerables, se contratan con la pantalla de Recruits: `setCost` +
  `MERC_COST_PER_LEVEL` × nivel; contratado = `hired` les saca las marcas). Equipo (pedido de Ale: "con lo caro que salen"): `outfitMerc` pisa el de Recruits: nivel ≥`MERC_ELITE_LEVEL` (3) placas + acero, si no cota de malla + hierro; arqueros/ballesteros conservan su arma, armadura más liviana. Precio = el de Recruits + `MERC_COST_PER_LEVEL` (5) × nivel + `MERC_GEAR_COST_BASIC/ELITE` (6/15; a distancia la mitad). Marcas `warbandcraft:world_tavern` (id) +
  `world_tavern_role`; no se guardan (`onLoad`). Por día: vuelven `GUARD_REFILL_PER_DAY` guardias, llegan `MERC_PER_DAY`
  (tope `MERC_MAX`). Saludo la primera vez (`visitors`). Tabernero (todos): opción nueva **Tesoro** (`Rumors.treasure`,
  `TREASURE_COST`, etiqueta `#warbandcraft:treasure_rumored`: enterrados, naufragios, templos, ruinas de nova_structures;
  busca desde vos y 4 puntos a `TREASURE_PROBE_DISTANCE`, va al mapa M como rumor). Prueba: `WorldTavernGameTests`.
- **Reclutas errantes** (pedido de Ale, a probar): `village/Wanderers`. Cada minuto por jugador 35%: 1-3 reclutas de Recruits
  sin dueño, nivel 0, a 40-90 bloques y a >80 de una aldea; caminan de a tramos (`setHoldPos` + estado 3, cada 10 s). Se
  contratan con la pantalla de Recruits. Marca `warbandcraft:wanderer`: no se guardan (`TavernEvents.onJoin`), contratados
  la pierden. Cerca de una aldea la taberna los mete adentro (`Tavern.isHireable` los acepta).
- `/warband mazmorras borrar` (operador, pedido de Ale): saca de `DungeonData` la mazmorra donde estás (adentro, o a ≤48) y sus bichos
  cargados; los bloques quedan.
- Pruebas: `/warband prueba guarida|tumulo` (12 adelante tuyo; dice la combinación). GameTests `H25GameTests` (arman lejos,
  a ±3000, y limpian al terminar).
- No hecho (a revisar): gritos (Ale: por ahora no); Draugr Invasion como mod aparte (ARR, en Forge solo con Sinytra
  Connector: sin probar); contratos sobre mazmorras de otros mods.

## Aldeas de facción vivas y asedios que no se traban (pedido de Ale, a probar; números sin Ale)

- `village/FactionTowns`: cada aldea de facción (no castillos) tiene tamaño `Town.npcTier` (1 aldea / 2 pueblo / 3 ciudad,
  por camas a ≤48: `TOWN_BEDS` 10, `CITY_BEDS` 22, +1 con `BIGGER_CHANCE`) y sus mejoras de H12 (`wanted`): aldea =
  empalizada, mercado, taberna, 1 casa; pueblo = + torres, plaza, Plaza de mercado, taberna 2, cuartel, granero, 2 casas;
  ciudad = muralla, torres, plaza de ciudad, Lonja, taberna 3, cuartel, herrería, capilla, granero, 3 casas. Pedido de Ale (el
  mercado y la taberna quedaban afuera): primero los edificios y después la empalizada/muralla, con radio `Upgrades.ringAround`
  (la esquina más lejana de los terrenos que no son casas + `Blueprints.RING_PAD`, tope `RING_MAX`); las casas al final (pueden
  quedar afuera). Sin anillo todavía, un edificio se busca hasta `RING_MAX − lado − RING_PAD` del centro, y en aldeas de facción
  con `Blueprints.findSiteRelaxed` si no hay lugar estricto (desnivel `NPC_MAX_SLOPE` 5 con piso = mediana, hasta
  `NPC_PATH_SHARE` 20% de camino). Las ya armadas antes quedan como estaban; al crecer de tamaño, lo nuevo busca adentro del
  anillo que ya tienen (la muralla de ciudad sobre una empalizada no se agranda: a revisar). Lo de su tamaño
  aparece hecho (`Upgrades.buildNow`, 1 mejora por segundo en todo el mundo, con todo cargado a 80); `Town.npcReady` = ya
  está. Después crecen (`onNewDay`, `GROW_CHANCE` 5%/día) con obra y constructor normales. Tick en `InvasionEvents` (1 s).
  Sin lugar para una mejora: se reintenta a los 10 min. Conquistada: queda todo.
- **Claim de la facción** (pedido de Ale, a probar): `village/FactionClaims`. Toda aldea/castillo de facción (sin dueño, no de
  prueba) está reclamada entera: `measure` = caja de camas/puestos/campanas suyas (POI a ≤`POI_RADIUS`, la vecina no), anillo,
  muralla dibujada (`Town.defense`), torres, portones y terrenos de mejoras + `MARGIN` (mín. `MIN_RADIUS` / castillo
  `CASTLE_RADIUS`, tope 17×17 chunks). Se mide con un jugador a ≤`SYNC_RANGE` cada `RESYNC_SECONDS` (`VillageEvents`, scan).
  Claim de Recruits a nombre de una `RecruitsFaction` armada del JSON (id = `teamName()`, color de unidad más parecido; va
  guardada en el claim, no se registra en Recruits), jugador inventado `leaderOf(f)`, **admin** (Recruits no le hace sus
  asedios), tocar y poner sí (puertas, cofres, escaleras), romper no. Protección propia aparte (`VillageEvents.onBreakFactionVillage`,
  prioridad HIGH, y pisar cultivos): un jugador no creativo no rompe nada con la mano; TNT, cañones, zapadores y bichos sí.
  Sin medir todavía: a ≤`FALLBACK_RADIUS` del centro igual protege. La toma otra facción de la IA → el claim pasa a esa;
  la conquista un jugador → `RecruitsClaimBridge.claimVillage` se queda con ese claim (le saca admin y los permisos) y
  `Territory.ensure` lo adopta entero; si no se pudo, `release` lo saca. Prueba: `ClaimGameTests`.
- `village/GarrisonLife` (cada 2 s, aldeas/castillos de facción cargados y en paz: sin raid ni batalla entre facciones):
  arqueros a las torres / muros del castillo (`Quarters.posts`, aparecen arriba a ≤6), `GATE_GUARDS` por portón del anillo
  (`gates`: huecos de la empalizada sobre caminos), `MARKET_GUARDS` (2, pedido de Ale) rondan a ≤`MARKET_RADIUS` del puesto del
  Mercader (`Market.workplace`), `PLAZA_SHARE` en la campana, el resto patrulla con paradas; con un
  objetivo cerca pelean (estado 0) y vuelven; creepers o persecuciones lejos se dejan. Puestos en memoria (`DUTIES`).
- `battle/SiegePush`, para todos los asedios: **lado más débil** (`stages`/`weakestAngles`: 8 lados, camino con hueco <
  escalera < romper, defensores cerca del muro, cuánto girar desde de donde vienen, azar) en `AiWars.startBattle` y
  `Assault.spawn`; **adentro** (`inside`, desde `SiegeLanes.tick`): enemigo a ≤16 → aldeano a ≤10 (`Host.villagers()`: IA
  sí, tus soldados no) → al objetivo (centro / almacén) → ahí, a buscar al enemigo más cercano por la malla; **destrabe**
  (`watch`: nadie pierde vida en `STALL_SECONDS` 30 → PUSH: todos `huntAll`; `GIVE_UP_SECONDS` 300 → batalla entre facciones:
  el bando más débil (`BattleGeneral.power`) se va; invasión: retirada; ataque tuyo: solo PUSH).
- **Reserva** (pedido de Ale, a probar; números sin Ale): `village/GarrisonReserve`. Aldea de facción con tamaño (`npcTier`) tiene
  soldados que no se ven: `RESERVE_VILLAGE/TOWN/CITY` 4/10/18 + `PALISADE_BONUS` 3 / `WALL_BONUS` 6 (castillos y aldeas tuyas, 0).
  Con un ataque tuyo (FIGHTING) o una batalla entre facciones salen `WAVE` (2) cada 3 s del cuartel (o la campana), hasta
  `BattleSize.MAX_PER_SIDE`, marca `warbandcraft:reserve`. Mientras quede reserva la aldea no cae (`RaidManager.tick`,
  `AiWars.battleTick`); cuenta en la barra y en la autoresolución (`AiWars.defense`). Sin pelea 60 s, los que salieron vuelven
  adentro (se borran y vuelven a la cuenta). Guardado: `Town.npcReserveUsed`; se repone `DAILY_REFILL` (3) por día. Tick en `InvasionEvents`.
- **Mercado de facción** (pedido de Ale, a probar): `market/FactionMarket`. Aldea de facción con mercado → tantos Mercaderes
  (`MerchantEntity` sin dueño, marca `warbandcraft:faction_market` = centro, cada 5 s) como el nivel, en `Market.workplace`.
  Click = tienda de vanilla: unas cosas te venden y otras te compran (cada ítem en un solo sentido; compran a `BUY_FACTOR` 0,6),
  `OFFERS_BASE + OFFERS_PER_LEVEL × nivel` ofertas del día (en memoria, se gastan), lista del bioma (desierto, frío, sabana,
  pantano/selva, montaña, templado) + general. En guerra o con pelea en la aldea no comercian. Las esmeraldas que pagás van al
  almacén de la aldea. Conquistada: se van (tus mercaderes son los de siempre).
- **Tabernero de facción** (pedido de Ale: "vi 3 tabernas y no está"): `village/FactionInn` (cada 5 s desde `InvasionEvents`):
  aldea de facción con taberna → un `InnkeeperEntity` sin dueño, invulnerable, marca `warbandcraft:faction_inn` = centro, en
  `Tavern.keeperSpot`, de día y de noche (como el de `WorldTaverns`); rumores y trabajos como el tuyo. Conquistada o sin
  taberna, se va; en una pelea no aparece uno nuevo. `serve` = una aldea (lo usan las pruebas).
- **Taberna** (pedido de Ale): los reclutas para contratar (los de la taberna y los sueltos de Recruits sin dueño ni marcas
  nuestras, `Tavern.isHireable`) a ≤64 de una aldea con taberna (tuya o de facción) esperan adentro: cada uno a un lugar con
  techo de la taberna (estado 3); si en 15 s no llegan o están a >24, aparecen ahí (`Tavern.keepTick` cada 2 s, todas las
  aldeas cargadas; sin taberna, en ronda a 3-6 de la campana). El Tabernero, de día, a `Tavern.keeperSpot` (el lugar libre más
  cerca del medio de la taberna): se cuida solo en `InnkeeperEntity.tick` (cada 1 s, sin buscar entidades; en su lugar velocidad 0
  con un modificador transitorio, si no llega en 15 s o está a >24 aparece ahí); `InnkeeperGoal` prioridad 1, no mira `shouldWork`.
- Batallas entre facciones: siempre con carriles (`SiegeLanes`) aunque no haya muro (antes `fieldTick` con `TeamAI`);
  sin muro, la guarnición usa `AiWars.defendOpen` (pelea con el que entró o tiene cerca; si no, medio anillo hacia el enemigo).

## Armas y armaduras medievales (H26, a probar — plan en `plan-h26-armas.md`; decidido con Ale: obligatorio, todos los agregados, herreros solo armamento, peso un poco)

- Una pieza de kit/tropa puede ser una lista `["magistuarmory:iron_pike", "minecraft:iron_axe"]`: va la primera que existe
  (`kit/ItemChoice`). Facciones vestidas con EK (Valdren caballeros/piqueros/alabarderos, Norte varegos, Sultanato lamelar y
  sarracenos, Pantanos capelina/guja/ranseur, Bandidos oxidados); Lancero con pica.
- `kit/WeaponRules` (números en la configuración): armas de asta ×2 contra montados, lanza de un soldado montado según la
  velocidad del caballo, contundentes ×1,3 contra armadura ≥14; peso = −0,8% de velocidad por punto de armadura arriba de 12
  (máx. 12%), a todos menos caballos. EK reconoce el arma por el nombre del ítem.
- `warband_armament/epic_knights.json`: armas de hierro 3 / acero 5, armaduras de metal 5/8/7/4 (oxidadas la mitad).
- Pica de soldado: `WeaponRules.onLivingTick` (cada 5 ticks) pega a su objetivo entre 2,6 y `POLEARM_REACH` (4) con línea de
  vista, respetando la velocidad de ataque. Formaciones de H24 (`Formation.assign`): las armas de asta van a la primera fila.
  Escudos (cualquier `ShieldItem`, también los de EK) con el estandarte de la facción: `FactionStyle.apply` copia su
  `BlockEntityTag` + `Base`. Pavés en la mano secundaria (ballesteros de los Pantanos): para `PAVISE_BLOCK` (60%) de las flechas que vienen de frente.

## Salón del tutorial (pedido de Ale, a probar; diseño sin Ale)

- `tutorial/Tutorial`: en un mundo **nuevo** (`ServerAboutToStart`: `isInitialized` false; no en `GameTestServer`), en
  `ServerStarted` se arma en el spawn un salón de piedra 37×37 (paredes a ±18, piso = mediana del heightmap, cimientos hasta
  30 abajo, plaza a ±21), puerta al sur, y el spawn queda adentro (`setDefaultSpawnPos` + `spawnRadius` 0). 25 atriles:
  el del medio ("Empezá acá") y 6 por pared con cartel encerado (orden: pared oeste desde la puerta, norte, este, sur).
- Libros = `data/warbandcraft/warband_tutorial/NN_algo.txt` (`TutorialBooks`: `titulo:`, `cartel: a|b`, `## ` subtítulo,
  `---` página nueva; corta las páginas midiendo la fuente, 113 px × 14 renglones). **Cambiar una mecánica = actualizar
  su libro.** Llevarse un libro: vuelve a los `RESTOCK_TICKS`; al arrancar el server se reponen todos (textos nuevos).
- Zona protegida: chunks a ≤`PROTECT_RADIUS_CHUNKS` (2 = 5×5) del medio (`TutorialData`, overworld). `TutorialEvents`:
  romper, poner, usar ítems contra bloques (atriles sí se usan), baldes, explosiones, pistones, pisar cultivos, mob griefing.
  Creativo + operador sí puede. `Compat.foreignStructureAt/surfaceStructureIn` lo cuentan como estructura (castillos,
  mazmorras, obras, campos de batalla lo esquivan) y `RoadPlanner.usable` no pone camino ahí.
- Vanilla hace aparecer arriba de todo lo de la columna del spawn (el techo): `Tutorial.fixSpawn` (al entrar y al reaparecer)
  mueve adentro al que quedó arriba del salón.
- `/spawn` (todos, `command/SpawnCommand`): te lleva al spawn del overworld (corta el viaje, te baja del caballo; tus soldados quedan). No para zafar:
  no anda a `COMBAT_SECONDS` (15) de un golpe recibido o dado (`LivingHurtEvent`, en memoria) ni con un `Mob` a `ENEMY_RANGE`
  (32) que te tiene de objetivo. Creativo/espectador, sí.
- `GUIA-WARBAND-CRAFT.md` (pedido de Ale, para preguntarle a una IA): `tools/make_pack.py` (`guide`) junta los libros en un .md, va en
  los zips y `PonerArchivos` (`pack/comun.ps1`) lo copia a la carpeta del juego / del servidor en cada instalación y actualización.
- Mundos viejos: `/warband tutorial construir` (operador) lo arma en el spawn (pisa lo que haya); `/warband tutorial` dice dónde.
- Prueba: `TutorialGameTests` (arma con `build(..., true)` a 7000 sin tocar spawn ni protección).

## Números en el archivo de configuración (pedido de Ale)

- Todo número de juego vive en `config/warbandcraft-common.toml` (se edita con el juego cerrado o abierto: Forge lo recarga).
  `config/Tuning` lo arma por reflexión: campos `static` **sin `final`** int/long/double/float en MAYÚSCULAS de las clases de
  `config/TuningClasses` (sección = paquete.Clase, comentario = el `/** */` de arriba, en `warbandcraft_tuning.properties`).
- **Al agregar una constante nueva**: escribirla `static` sin `final` y correr `python tools/gen_tuning.py` (regenera la
  lista y los comentarios). Lo que no es de juego (códigos de estado de Recruits, colores, índices, pantallas) queda
  `final` (`FIXED_NAMES`/`EXCLUDE` en el script). Ojo: una constante calculada con otra toma el valor de entrada.

## Trabajador de un Campamento común (pedido de Ale, a probar; números sin Ale)

- `camp/CampWorkers` (SavedData `warbandcraft_camp_workers` por dimensión; por Campamento común hasta `MAX_PER_CAMP` (2): un Herrero y un
  Granjero, pedido de Ale; `Rec` worker/owner/away/bed/job). La pantalla lista a todos; el id del botón lleva su acción ("pos:S,F,d0,d1").
  Se contrata al Tabernero (`village/TavernWorkers`: opción nueva del menú de `Rumors`, índice 3; cada taberna junta
  `PER_DAY` por día hasta `MAX_1..3` = 1/2/3 según `tavernLevel`, las del mundo nivel `WORLD_LEVEL` 2; clave = centro de la aldea
  o `WorldTaverns` id; `HIRE_COST` 10 con `Rumors.pay`). Elegís el Campamento (pantalla de H23, kind `tavern_worker`).
- Aparece un aldeano sin oficio (marca `warbandcraft:camp_worker` = Campamento) al lado del Tabernero y camina (`WALK_TARGET`
  cada 1 s desde `BarracksEvents`); sin acercarse `STUCK_PROGRESS` en `STUCK_SECONDS` aparece en el Campamento; dueño a >`OWNER_RANGE`
  o Campamento lejos/descargado = `away` (aparece cuando el Campamento se carga). Al llegar, `setNoAi(true)`: queda parado.
- Pestaña Cofre → "Trabajador" (`CampWorkerMessage` → `CampWorkers.open`, kind `camp_worker`, id "pos:estado"): Herrero (yunque a ≤24,
  `WorkZones.standNextTo`, `SmithEntity.setCamp/setWorkplace`) o Granjero (`WorkZones.campFarmSite`: huerta hecha o el 9×9 de tierra más parejo (≤`CAMP_FARM_SLOPE` 3)
  a ≤`CAMP_FARM_RANGE` (40) del Campamento, aunque quede fuera de su jurisdicción (pedido de Ale), probando cada esquina bloque por bloque;
  `campFarm` no cobra nada, sin cofre). Ambos piden cama libre a ≤24 (`PoiManager.take` + `WorkerSupply.homeAt`).
  Después `serve`: `WorkerSupply.supply/feed/collect` con ese Campamento, vuelve si se aleja >`LEASH`. Despedir libera cama y zona.
  `VillageManagement.workers` no los cuenta (no los atiende una aldea). Prueba: `CampWorkerGameTests`.
- Producción sin cargar (pedido de Ale: "tiene que ser igual que en las aldeas"): `CampWorkers.paper` en cada `serve` usa
  `WorkerOutput` (rec con `village` = el Campamento): por el hueco (máx. 7 días) come `FOOD_PER_DAY` del almacén (si no alcanza,
  produce en proporción) y deja `owed` (lo medido o `WorkerSupply.estimate`). El Herrero hace sus vueltas de verdad
  (`SmithWorkGoal.workOnce`: desguace o hierro + carbón, `IRON_PER_DAY` por día). Aviso "Mientras no estabas..." si fue >2 min.
  Visible esperando en la taberna: no (Ale: "si cuesta mucho dejalo así").
- Fundar aldea (pedido de Ale): `Founding.found` → `CampWorkers.toVillage`: el que tiene oficio pierde la marca y lo atiende la aldea
  (cama, zona y `WorkerOutput` siguen); el que esperaba/venía = aldeano libre al lado del Campamento. `tickOne` lo hace también si
  un Campamento ya es aldea y alguno no estaba cargado.
- Tope de reclutas de tus tabernas: 4/7/10 (pedido de Ale: nunca más de 10).

## Organizador (pedido de Ale, a probar; números sin Ale)

- `camp/Organizer` (cálculo y movidas) + `worker/OrganizerEntity` (`warbandcraft:organizer`, como el Herrero; `Job.ORGANIZER`) +
  `worker/OrganizerGoal` (camina de verdad). Campamento común: tercer oficio de `CampWorkers` ("O", `MAX_PER_CAMP` 3); aldea:
  Gestión → Aldeanos, uno por almacén (`VillageManagement.organizersOf`), sin zona (Zona/Auto = `WorkZones.organizerCamp`).
- El Campamento es la bandeja de entrada; cofres = vinculados cargados (doble = una unidad, barriles sí) menos los de zonas de
  trabajo (`WorkZones.zoneChests`). Categorías: comida, armas/armaduras/herramientas (todo lo que se gasta), construcción
  (`BlockItem`), minerales y lingotes (tags de Forge), semillas y plantas, varios. No mueve lo con nombre ni lo de desguace
  (eso vuelve al Campamento).
- `plan` (cada `PLAN_TICKS`, sobre lo asignado antes, guardado en la entidad `WbSort`): marcos (ItemFrame pegado al cofre:
  ese ítem primero + su categoría, fijo) → suelta cofres vacíos que sobran → uno por categoría de mayor a menor (si no alcanzan,
  el último libre es Varios) → más cofres pegados a los suyos mientras ocupen > `FILL` (0,8). Lo que no entra: Varios, si no
  queda en el Campamento y `missing` → estado "Faltan cofres para: ..." (`WorkerSupply.status`, pantalla del Trabajador).
- Viaje: va al origen, abre la tapa (`lid`: blockEvent / barril OPEN), levanta hasta `STACKS_PER_TRIP` (4) pilas (la primera en
  la mano), va al destino, deja, `sort` adentro. Trabado `STUCK_TICKS` → aparece al lado. Cortado (noche, huye) o muerto/despedido
  con algo en la mano → al Campamento (`dropAtCamp`). Descargado no hace nada (nada cambia). Pruebas: `OrganizerGameTests`
  (`OrganizerGoal.runNow` = viajes en el acto).

## Taberna, entrenamiento y armar con armamento (H19, a probar — plan en `plan-h19-taberna.md`)

- Mejoras de H12 `TAVERN_1..3` (solo aldeas, terreno "tavern") y `TRAINING_1..3` (aldeas y castillos, pide Cuartel,
  terreno "training"); `Town.tavernLevel/trainingLevel`. Edificios en `Blueprints.building`.
- `village/Tavern`: cada día (`VillageEconomy.checkNewDay`, no en GameTestServer) se van los que esperaron `LEAVE_DAYS`,
  llegan los del nivel (con un **Tabernero** tuyo y 10 puntos de comida cada uno del almacén; descargada: pan de los números)
  y con Auto-reclutar (`Town.autoRecruit`, de entrada sí) pasan al cuartel y después al barracón. Los que esperan son datos
  (`Town.tavernWaiting` {nivel, día}); los que se ven son reclutas sin dueño, pasivos e invulnerables con marca
  `warbandcraft:tavern` que no se guardan (`TavernEvents`, cada 5 s se acomodan). Click derecho = se une a vos gratis.
  Gestión → Tropas: fila "Taberna" (→C cuartel, →B barracón, A: Auto). Fichas nuevas: `Tavern.sheet` (recluta sin clase,
  niveles con `addXp` + `checkLevel` de Recruits). Sin armado automático con el kit de Infante (a revisar).
- Tabernero = `worker/InnkeeperEntity` (`warbandcraft:innkeeper`, como el Herrero; `Job.INNKEEPER`), atiende en
  `Tavern.workplace` (medio de la taberna) o donde se le marque (un click).
- `camp/Training`: lugar +20/40/60, y cada día que comen (el doble) las fichas del barracón y cuartel ganan 25% de un nivel
  de Recruits hasta 3/6/10. Aldea/castillo = mejora; Campamento común = botón en la pestaña Barracón (16/32/64 esmeraldas
  del almacén, vale desde mañana; `CampRegistry.training`).
- Armamento: el nivel 0 (básico) de una clase se puede pagar entero con armamento (`Armament.plan/pay(..., basic)`); lo que
  no es de metal sale de `warband_armament/armament_only.json` (`{"value", "per", "armament_only": true}`; arco 6, cuero 3-6,
  16 flechas 2...). Niveles más altos: solo metal, como antes.

## Crecimiento de aldeas (H21, a probar — plan en `plan-h21-crecimiento.md`; números a revisar)

- `village/Popularity`: popularidad 0..100 por aldea tuya (no castillos) en `TownData.Town` (+ `rations`, `taxes` 0..4,
  `growthMinutes/leaveMinutes`, `hungry`). Cada minuto (`TavernEvents`, no en GameTestServer; solo aldea cargada: descargada
  queda quieta) suma los factores (`factors`: raciones, variedad de comida del almacén, impuestos, camas libres de
  `Housing`, guardias por 10 habitantes, empalizada/muralla, invasión en marcha, taberna con tabernero, mercado).
  ≥ 50 + cama libre + debajo del tope (12/24/48 por aldea/pueblo/ciudad) → llega uno cada 5/3/2 min al borde del territorio
  (`spawnSettler`, marca `warbandcraft:settler`, sin oficio; `keepSettlersFree` les saca el oficio de vanilla si lo toman sin
  comerciar). < 50 → se va uno cada 5 min (< 25: 2): primero recién llegados libres, después libres, después trabajadores sin zona.
- Raciones: cada día (`VillageEconomy.checkNewDay`) los habitantes comen `rationPoints` del almacén; si no alcanza, `hungry`
  (−4 ese día). Impuestos: `VillageEconomy.taxOf` × `taxFactor` (0 / 0,5 / 1 / 1,5 / 2) en aldeas tuyas.
- Gestión (V) → pestaña Pueblo: barra, habitantes, cuándo llega/se va el próximo, ◀ ▶ de raciones e impuestos
  (`ManageActionMessage.RATIONS/TAXES`) y el informe de factores (`Popularity.Info` en `VillageManagement.Data.growth`).
- Hecho después (a probar): **Asignar** también a aldeanos con oficio de vanilla (van al final de la lista "(con oficio)",
  celestes en el mundo; pierden sus tratos). **Auto-asignar** (`Town.autoAssign`, de entrada sí, botón en Gestión →
  Aldeanos, `ManageActionMessage.AUTO_ASSIGN`): cada minuto, una zona tuya sin trabajador + un libre (primero los recién
  llegados) → `WorkZones.autoAssign`. **Ataque reciente** (`Popularity.attacked` al rechazar una invasión, peleada o
  autoresuelta): −2 por 3 días (`Town.troubledUntil`). **Aldea descargada**: sigue "en papel" (`paperMinute` con
  `lastDelta`, `paperPop`, `paperBeds` de la última vez); al cargarse llegan/se van de verdad (`applyPaper`, aviso).
  **Panel K**: popularidad y "⚠ N trabajadores trabados" (estado rojo de H18, solo cargada) en la fila de cada aldea.

## Edificios con estilo (pedido de Ale, a probar; diseños hechos sin Ale)

- Los planos de los edificios de aldea están en `upgrade/Buildings` (en código, sin .nbt): un `Canvas` dibuja en el terreno
  girado (x, z desde la esquina, y desde el piso, puerta al norte sin girar) con ayudas: `walls` (zócalo, entramado con postes
  cada 4, viga arriba, ventanas), `roof` (a dos aguas con alero, o plano con pretil si el estilo es `flat`), `table`, `bed`,
  `hearth`/`chimney` (fogata arriba), `stall`, `dummy`, `banner`... Lo que se cuelga o apoya (puertas, camas, faroles,
  estandartes, alfombras) va con `late`: se pone al final del plano, cuando ya está lo que lo sostiene.
- **Estilo** (`upgrade/Style`): REINO (Valdren, y lo que no tiene otro: entramado de roble oscuro, terracota blanca, pizarra),
  NORTE (taiga/nieve/montaña: abeto, techo de roble oscuro), ARENA (Sultanato, desierto/badlands: arenisca, techo plano),
  PANTANO (pantano/selva: barro, mangle), SABANA (acacia, terracota). Aldea sin dueño = el de su facción (`ofFaction`, por el id
  del JSON); tuya = el del bioma del centro. Se elige con el primer edificio y queda en `TownData.Town.style` (si cambia de
  dueño, sigue igual). Facción nueva sin estilo en `ofFaction` → por bioma. La vista previa de Gestión dice el estilo.
- **Terreno por edificio** (`Upgrade.siteSize()`, decidido con Ale: se reserva el del nivel más grande): taberna, mercado y
  entrenamiento 13; taller, cuartel, herrería y capilla 11; casa y granero 9. `Town.siteSizes` (sin entrada = 9 = terreno de
  antes de los estilos, `legacySite`); `Town.putSite/removeSite/lots`; `Blueprints.Lot`, `findSite(..., size, lots)`,
  `overlaps(corner, size, lots)`. Terreno viejo de 9 que sube de nivel → `Blueprints.enlarge` (alrededor del mismo medio, sin
  contar lo de adentro); si no entra: "No hay lugar para agrandarlo" (aldeas de facción: se reintenta). Demoler uno viejo saca
  todo lo de arriba de sus 9×9 (`Works`).
- Niveles: Taberna 1 sala 9×7 (barra, 2 mesas, banco afuera); 2 posada 11×9 (sala, cocina con hogar y chimenea afuera, 3
  cuartos con camas, porche techado); 3 hostal 11×11 de **dos pisos** (abajo barra, cocina, hogar, 4 mesas y escalera; arriba
  dormitorio con 9 camas en fila, baúles, alfombra). Mercado: 3 puestos con toldo a rayas del color del estilo / +3 atrás y
  bancos / piso de piedra, pozo techado y faroles. Entrenamiento: muñecos + blancos / + afilar / + ring de arena y cobertizo.
  Taller: cobertizo / taller con troncos / casa de dos pisos con cuarto. Cuartel 8 camas; herrería abierta con fragua y
  chimenea; capilla con vitrales del color del estilo y campanario con farol (una campana sería otra aldea).
- Quien trabaja en el edificio se para en `Blueprints.anchor(town, key, nivel hecho)` (`Buildings.anchor`: Tabernero detrás
  de la barra, Mercader, salida del cuartel de `GarrisonReserve`); terreno viejo = el medio. `Tavern.cachedSpots` recorre todo
  el terreno; la herrería busca el yunque en todo su terreno.
- El nivel siguiente de un edificio (`isLevelUp`) pisa barriles, camas y cofres del de antes (lo de adentro al almacén; un
  Campamento nunca), como demoler. Cercos, rejas/vidrios, muros y escalones se unen con lo que ya está al ponerse
  (`Upgrades.place` → `updateFromNeighbourShapes`). Arenisca, terracota, ladrillos de barro, pizarra se cobran como piedra;
  barro compacto y arena como tierra.
- Pruebas: `StyleGameTests` (Taberna 3 con 9 camas arriba y el Tabernero bajo techo, materiales por estilo, la Taberna 1 reserva
  13). Estructura de prueba `empty_big` (17×18×17, `tools/gen_test_structure.py`) para lo que no entra en `empty` (9×9).
- No hecho (a revisar): elegir el estilo a mano en Gestión; edificios de castillo (siguen con terreno automático, mismos planos);
  .nbt hechos a mano que reemplacen un plano.

## Construir a mano (H22, a probar — plan en `plan-h22-construccion.md`; pedido de Ale, estilo Stronghold)

- Modo Gestión (V) → pestaña **Construir** (antes Mejoras): categorías Edificios / Defensa / Otros. Edificios de aldea
  (`Upgrade.handPlaced()`: los `isBuilding()` + casa) se **plantan**: el contorno (lado = `Upgrade.siteSize()`) sigue al mouse (`ManagePanel.renderPlacing`),
  R gira (0..3 horario), click planta, Esc / click derecho cancela. Vista previa = `ManageActionMessage.PREVIEW` (máx. 5/s,
  text "id,x,z" + value giro) → `BuildPreviewMessage` (ok, texto, piso). Plantar = `PLACE` → `Upgrades.order(player, c, u, corner, rot)`.
  Niveles siguientes (`isLevelUp`: Taberna 2, Taller 2...) van al terreno del primero, con botón como antes. Castillos:
  por ahora no (mejoras con lugar automático). Panel K: sin botón para lo que se planta.
- `upgrade/Placement.check`: adentro del territorio (sin territorio: `WorkZones.REACH`), sin pisar otros terrenos, casas,
  block entities ni agua; piso = mediana de la huella, desnivel ≤ `MAX_LEVEL` (8): el plano (`Blueprints.level`) rellena y corta.
- Giro: `Blueprints.building(level, u, site, rot)`; `Box.at` gira adentro del terreno como `StructureTemplate` (horario =
  (x, z) → (8−z, x)) y `set` gira el bloque. `TownData.Town.rotations` (por clave de terreno). Casas: claves "house_N".
- Costo (`upgrade/BuildCost`): materiales por bloque (`add`: madera en tablones, piedra, tierra, vidrio, lana, hierro; lo que
  ya está o tiene block entity no se cobra; adornos gratis) + `Upgrade.cost` en esmeraldas (bajados a la mitad). Todo al
  encargar: materiales del almacén de la aldea y después del inventario (troncos = 4, con vuelto en tablones); esmeraldas
  como antes (`VillageStock.takeEmeralds`). `Job.paid` / `Job.siteKey` se guardan; **Cancelar obra** (`CANCEL_JOB` →
  `Upgrades.cancel`) devuelve lo no puesto y libera el terreno si el edificio no estaba hecho.
- Lo que una obra saca del mundo (tierra, piedra, troncos...) va al almacén de la aldea (`Upgrades.place` con `Block.getDrops`).
- Capilla (`Upgrade.CHAPEL`, solo aldeas): +`Popularity.CHAPEL` (2) por minuto. Casas automáticas apagadas (`Housing.autoTick`
  ya no se llama); pestaña Casas: "Plantar una casa".
- **H22b/c** (`upgrade/Works`, obras que no son un edificio; `Upgrade.isWork()`, no quedan "hechas"): texto de
  `ManageActionMessage.PLACE/PREVIEW` con ":" → `Upgrades.orderWork/previewWork`: "wall:<1|2>:x,z;x,z..." (tramos sin cortar
  esquinas, `Works.columns`; caminos = hueco), "gate:<1|2>:x,z" (sobre la columna de muralla más cercana, 3 de abertura con
  2 puertas de cerco + postes), "tower:<1|2>:x,z" (`Blueprints.tower(..., stone)`, devuelve el piso), "earth:<0 allanar|1
  rellenar>:x0,z0,x1,z1,y", "demolish:<clave de terreno | tower:pos>" (saca también block entities: su contenido al almacén).
  Al terminar (`Works.finish`): bloques de defensa en `Town.defense` (los usa `WallRepair`), `Town.gates`, `Town.towers`
  (`Quarters.drawnTowerPosts`), flags PALISADE/WALL/TOWERS. `Town.ringWalls` = el anillo viejo de H12 (las pruebas que lo
  simulan lo prenden). En aldeas, PALISADE/WALL/TOWERS no salen en `Upgrades.status`. Portones: `Works.tickGates` cada 10
  ticks (`UpgradeEvents`). `Breach.walls` usa `Works.radius` con muralla dibujada. Cliente: `ManagePanel.Mode` (BUILDING,
  POINT, WALL, DRAG, PICK), categorías Edificios / Defensa / Terreno / Otros.
- Pruebas: `H22GameTests` (taberna girada + cancelar, sin materiales / encima de un cofre, la cuenta, muralla + portón +
  reparar, torre + demoler, allanar y rellenar, demoler un tramo de muralla).
- Después (a probar): no se planta encima de una **zona de trabajo** de Workers ("Pisa una zona de trabajo"). **Demoler
  muralla**: en Demoler, click donde no hay edificio = "demolish:wall:x,z" → saca lo dibujado a ≤`Works.WALL_CUT` (4) del
  click, sin tocar torres; deja de contar para reparar y saca los portones de ahí.

## Fundar una aldea (pedido de Ale, a probar; números decididos sin Ale, a revisar)

- Un Campamento común tuyo se convierte en aldea: botón "Fundar aldea" en la pestaña Cofre del Campamento (debajo de
  "Zona de trabajo"; `FoundVillageMessage`, `CampMenu.isVillage` lo esconde), Panel (K) → Campamentos → Ver → botón abajo a
  la derecha (el tooltip dice por qué no se puede) o `/warband fundar` (Campamento tuyo a ≤24). `village/Founding`.
- Requisitos: cargado, no es ya de una aldea, ninguna aldea/castillo a <128 (`MIN_DISTANCE`) ni campana a <64, lugar para
  la campana a 2-6 del Campamento. Cuesta 40 esmeraldas (`COST`: almacén y después inventario).
- Pone una campana (= centro), `VillageData.Village` con dueño y la facción del bioma (si la perdés, pasa a ser de ella;
  sin facción, la primera), `setStorehouse` = el Campamento, `camp.setVillage`, claim de Recruits como al conquistar
  (`RecruitsClaimBridge.claimVillage`: te crea la facción de Recruits si no tenés; `Territory.ensure` después lo adopta), 3 aldeanos sin oficio
  (`SETTLERS`). Desde ahí es una aldea de H16 (barracón 80, Gestión, mejoras, casas automáticas: las camas las hace sola).
  El nombre del Campamento queda como nombre de la aldea.
- `VillageData.founded` (guardado como `PlayerFounded`): no hay reconquista (`Invasions.takenFrom` la saltea); se borra si
  cambia de dueño. Perderla saca el Campamento como cualquier aldea (`VillageStorehouse.lose`).
- En `GameTestServer` no se mira la distancia a otras aldeas (las pruebas están pegadas). Prueba: `H16GameTests.campBecomesVillage`.

## Ejércitos de campaña (H20, a probar — plan en `plan-h20-ejercitos.md`; números decididos sin Ale, a revisar)

- `army/ArmyData` (SavedData por dimensión): por jugador, máx. `MAX_ARMIES` (3). Un ejército = pedidos **Campamento + fila
  del barracón (clase#nivel) + cantidad** (`Pick`), máx. `MAX_SOLDIERS` (100). Hasta invocarlo siguen en su barracón.
  Estados IDLE / COMING / FIELD; en el campo guarda `members` (UUID → Campamento de origen) y `mode` HOLD / CHARGE.
- Pantalla **Ejércitos** (`client/army/ArmyScreen`): pestaña nueva del Panel K, botón en la pestaña Barracón del
  Campamento y `/warband ejercitos`. Izquierda: ejércitos + crear; derecha: cada barracón por fila con −10 −1 +1 +10 T
  (`ArmyActionMessage.ASSIGN`, recorta a lo que hay); abajo Invocar aquí / Cancelar / Mantener · Cargar · Retirada · Licenciar.
  Datos: `ArmyPanel.of` → `OpenArmiesMessage`; poder estimado de las fichas (vida × nivel × caballo).
- Invocar (`Armies.summon`): por el terreno, no el bioma (H24, pedido de Ale; `biomeOk` / tag `warbandcraft:battlefield`
  quedan sin usar): campo de 60×40 mirando adonde mirás con ≥70% a ±4 del centro, sin agua en el medio y ≤15% de columnas
  con árboles (`FIELD_MAX_TREES`), a >64 de
  una aldea/castillo (`fieldProblem`). Cuenta = distancia al Campamento más lejano / 4,3 / 3 (10 s..3 min), barra de
  jefe; te alejás >64 = se cancela. `arrive`: salen las fichas (`Barracks.create`, marca `warbandcraft:army`) y se
  teletransportan **formados** (`form`): infantería adelante en bloques de 10 (2×5), arqueros 6 atrás, caballería en los
  flancos, zapadores/capitanes atrás. Cada uno en estado 3 con su puesto.
- `Armies.tick` (1 s, `ArmyEvents`, no en GameTestServer): HOLD = el que tiene un enemigo a ≤16 de su puesto pelea
  (arqueros tiran desde el puesto a ≤32) y después vuelve; CHARGE = todos contra el enemigo más cercano a ≤64, sin enemigos
  5 s → forman donde están. Enemigos = `Quarters.isEnemy` + los de la marcha. Si el jugador les da otra orden (vista
  aérea), quedan "libres" hasta la próxima orden del ejército (`overridden`).
- Cargar con una marcha/invasión enemiga a ≤48 (`Ambush.find`) → `Ambush.spring` delante del ejército (batalla de
  campo, `foes`); todos caídos = victoria (+40 XP de Recruits a cada uno). Emboscada: la primera carga desde un lugar con
  hojas (`hidden`, ≥25% de columnas) pega +30% por 10 s (`LivingHurtEvent` → `Armies.damage`).
- Licenciar (`dismiss`): cada uno al barracón de origen (o al más cercano con lugar, si no queda siguiéndote). Dueño a
  >256 o desconectado → se licencia solo. Los que se cargan después con la marca y sin ejército → `sweepOrphans` (10 s).
  `Barracks.autoStore` no toca a los del ejército. Comida: los que están afuera comen de su Campamento (`Barracks.feed`
  suma `Armies.outFrom`; ojo: un barracón que quedó vacío no cobra, a revisar).
- Vista aérea (V): con un ejército en el campo, fila arriba: ◀ nombre ▶ (click = elegir a todos) · Mantener · Avanzar
  (después click derecho en el piso, `OrderMode.ARMY_ADVANCE`) · Cargar · Retirada · Licenciar. Estado por `ArmyStatusMessage`.
- No hecho (a revisar): viajar por rutas para la cuenta (es en línea recta), moral propia del ejército, estandarte
  plantado, prisioneros/botín de la batalla de campo (los caídos sueltan lo suyo), "Invocar en su camino" desde el Panel.
- `/warband prueba marcha [cantidad]` (12 por defecto): guerra con la facción más cercana y una `March` que sale a 40 bloques
  adelante tuyo y anda ~2 bloques/min (300 bloques en 2 h; al llegar a ningún lado vuelve a la leva). Para probar Cargar.
- Pruebas: `H20GameTests` (2 barracones → 14 formados con infantería delante y vuelta al licenciar; biomas; carga
  contra una marcha).

## Meterse en batallas, asediar con un botón y la Hueste (H23, a probar — plan en `plan-h23-batallas.md`; números decididos sin Ale, a revisar)

- **Tecla B** (`ModKeyMappings.BATTLE`, `BattleKeyMessage` → `battle/BattleChoices.onKey`; también `/warband asediar`): batalla
  entre facciones cerca → pantalla para meterse; ataque tuyo cerca → suma a tus soldados; parado en una aldea/castillo de
  facción con guarnición → confirmación de asedio. Pantalla genérica: `ChoiceMessage` (kind, id, título, líneas, botones) →
  `client/ChoiceScreen` → `ChoiceAnswerMessage` → `BattleChoices.answer`. A un `FakePlayer` no se le manda (pruebas).
- **Asediar** (`BattleChoices.siege`): `RaidManager.start` + la guarnición contra vos + orden Batalla a tus soldados a ≤64.
  El cartelito de la aldea dice "[B] Asediar".
- **Meterse en batallas entre IA** (`AiWars`): a `WARN_TICKS` (2 min) de llegar, aviso a los jugadores a ≤`WARN_RANGE`
  (`March.warned`). Al empezar (y al que llega después, `ASKED` en memoria) la pantalla Ayudar a A · Ayudar a D · No
  involucrarse. `join`: `JOIN_OPINION` (−15) con el otro bando una vez; tus soldados a ≤`JOIN_RADIUS` (no ejército ni cuartel)
  marcados `warbandcraft:ai_join` (+ `ai_join_state` = orden anterior), `helpersTick` les da el enemigo más cercano;
  `AiWars.isFoe` lo usan `GarrisonDefense.isHostile` y `Armies.enemies`. Pegarle a uno sin elegir = `join` del otro lado.
  `finish` (antes de que cambie la aldea): +10 de opinión, `LOOT_BASE` esmeraldas y, si toman la aldea, `LOOT_STOCK_SHARE`
  de su almacén, a la bolsa de `BattleLoot` (se manda en el acto); `releaseHelpers`.
- **Botín de batalla** (`battle/BattleLoot`, pedido de Ale, para toda pelea contra soldados de la IA): al caer uno
  (`RaidEvents.onDeath`, después de `RaidManager.onGarrisonDeath`) se le saca lo que sirve para armar (armaduras, armas,
  arcos, ballestas, escudos, flechas) a la bolsa del beneficiario (dueño de la hueste / de la invasión / el que se metió del
  otro lado / el que lo mató), + `ARMAMENT_PER_FALLEN` y `EMERALDS_PER_FALLEN`. Sin caídos `FLUSH_TICKS` (20 s), o al ganar,
  va con `CampRegistry.deliver` al Campamento (aldea, castillo) cargado más cercano a la pelea; si no entra, queda pendiente.
  Los ataques a aldeas (raid en curso) no: tienen su Saquear / Conquistar.
- **Caballos** (`ai/HorseSpeed`, `SoldierEvents.onMount`): montado por un soldado, el caballo tiene velocidad máx.
  `MAX_SPEED` (0,17; modificador transitorio que se saca al bajar). El jinete usa la navegación del caballo.
- **Tácticas estilo Bannerlord**: H24, abajo.
- **Daño entre soldados** × `BattleSize.NPC_DAMAGE` (0,5, `ArmyEvents.onHurt`): las batallas duran el doble. A vos y a los
  bichos, normal.
- **Tamaño de batalla** (`battle/BattleSize.MAX_PER_SIDE` 60, `REINFORCE_PER_SECOND` 6, en la configuración): ejércitos de H20
  (`Army.waiting`, `Armies.reinforce`: entran por la retaguardia desde sus barracones), invasiones (`Invasion.reserve`,
  `Assault.reinforce` en el lugar de partida de un carril; `startSize` cuenta la reserva; al retirarse vuelve a la leva) y
  la Hueste.
- **Hueste** (`invasion/Hosts`, `InvasionData.Host`, guardada): pozo por facción (`hostPool`, 6/día por asentamiento, tope 150);
  cada día por facción en guerra con un dueño de aldeas con él o alguien de su facción conectado (`factionOnline`, pedido de Ale;
  sin nadie conectado el plazo de una hueste acampada queda quieto; las invasiones no cambian), sin hueste ni descanso (`hostCooldown`): chance 10% + 30% × odio;
  tamaño = tu fuerza (barracones + cuarteles + sueltos) × 1,3 ±15%, entre 30 y 100, hasta lo juntado. `Host.camp` = el
  **punto de reunión** (`findField`, por el **terreno**, no el bioma, pedido de Ale: cada 10° alrededor de la aldea a
  150-360 bloques, 8 orientaciones cada uno; el rectángulo de la batalla —60 de ancho, del punto −20 a las carpas +20— medido
  cada 4 bloques: ≤2% agua, ≤4% árboles, ≥85% a ±6 de la altura del medio, ≤5% de escalones >2; primero solo lo ya cargado,
  si no hay nada carga 6 lugares; gana el más parejo, a igualdad el más cerca de 220; `Host.axisX/Z` = hacia las carpas)
  cerca de tu aldea más cercana a sus tierras; las carpas (`Hosts.tents`) quedan `GAP` (40) más allá, lejos de la aldea; 3 días.
  Aviso al crearse, cada día y al entrar; mapa M: rombo en las carpas + círculo amarillo "Invocá tu ejército acá"
  (`MapPointsMessage.Muster`, radio `ARMY_RANGE`); Panel K y cartelito (como una invasión). Empieza **solo con un ejército
  tuyo invocado** (FIELD) con el ancla a ≤`ARMY_RANGE` (48) del punto (vos solo o con los que te siguen, no): aparece formada
  (`Armies.layout`) a `GAP` (80) hacia las carpas (lo más lejos que esté cargado), mirándote, `MAX_PER_SIDE` y el resto en
  oleadas; 6 s formada, después avanza en formación (`advance`: el centro se corre `ADVANCE_SPEED` 2 b/s, puestos nuevos con
  `Armies.layout`, los arqueros tiran a ≤40) y carga a `CHARGE_DISTANCE` (22) o si la tocan; tus soldados sueltos que te siguen pelean solos. ≤20% → se desbanda (huyen y se van). Ganar: esmeraldas × hombres
  + el equipo de los caídos (`BattleLoot`) al Campamento, XP, +20 de opinión, 7 días sin hueste. Te vas (>160 por 30 s o se descarga) o vence el plazo → `marchOn`: invasión ARMY con lo que quede (se suma a
  una en curso). Marcas: `warbandcraft:host` (+ `field_army`, `village` = campamento); `Ambush.giveUp` no las toca; al
  cargarse sin batalla, no entran. `Invasions.launch` no elige una aldea amenazada por una hueste.
- Pruebas: `/warband prueba hueste [n]` (acampa como de verdad, con `findField` desde tu aldea más cercana), `prueba rendimiento [n]` (tuyos gratis + hueste ya peleando + medidor), `prueba medir`
  (ms/tick, TPS, soldados cargados, cada 1 s), `prueba llegar` (tus ejércitos en camino llegan ya). GameTests `H23GameTests` (cada una con hueste o jugador en su tanda; jugador =
  `FakePlayerFactory`: el `makeMockServerPlayerInLevel` de vanilla se rompe con los paquetes de Forge).
- Aparte: `workerZoneCoversCamp` / `markFarmAndPen` fallan a veces (zonas de Workers de pruebas vecinas que se pisan).

## IA de batalla estilo Bannerlord (H24, a probar — plan en `plan-h24-tacticas.md`; decidido con Ale: no avisa nada, delegar sí, dificultad sí)

- Paquete `battle/tactics/`. Tres capas como Bannerlord (reimplementado en Java, sin código de TaleWorlds):
  1. `TeamAI` = el general de un bando, por clave (`host:<id>`, `army:<id>`, `march:<id>:a|d`, `field:<pos>`), en memoria.
     `TeamAI.tick(level, key, propios, enemigos, Context(home, defendPoint, canRetreat))` cada segundo: moral → formaciones
     por tipo (`Formation.kindOf`: infantería, arqueros, caballería, arqueros a caballo; el capitán/general aparte, detrás
     de la infantería) → piensa la táctica cada `Tactics.thinkTicks()` (según `DIFFICULTY`) → corre cada formación.
     Devuelve cuántos de la IA huyeron y se fueron. `forget(key)` al terminar (baja escudos).
  2. `Tactics.Tactic` con `weight` + histéresis + azar por dificultad: CHARGE (ejércitos chicos), FULL_ATTACK, DEFENSIVE_LINE
     (con `defendPoint` pesa más), RANGED_HARASS, CAVALRY_CHARGE, HOLD_HILL (`findHill`: loma ≥3 más alta a ≤40, del lado
     propio), RETREAT (solo con `canRetreat`, relación <0,35 y 30 s de pelea). Fácil: sin hostigar / carga de caballería /
     loma / rodeos. `Tactics.forced` (`/warband prueba tactica <nombre|auto>`) fuerza una a todos menos a tus ejércitos.
  3. `Formation.Behavior`: ADVANCE (avanza a `FOOT_SPEED`/`MOUNTED_SPEED` si está junta —`COHESION`—, carga a
     `CHARGE_DISTANCE`), CHARGE (con objetivo preferido), HOLD (muro de escudos `SHIELD_WALL` con escudos ≥50% y el enemigo
     con ≥25% de arqueros: `setShouldBlock`), SCREEN (arqueros detrás de su infantería), SKIRMISH (a `SKIRMISH_RANGE`,
     retroceden si se acercan), FLANK (columna afuera del costado → detrás → carga a los arqueros; si viene su caballería,
     la enfrenta; evita el agua), PROTECT_FLANK, HUNT_RANGED, RESERVE, RETREAT. `Formation.form`: puestos sin cruzarse
     (`assign`), el que tiene un enemigo a `MELEE_ENGAGE` pelea; los de cuerpo a cuerpo no apuntan (saldrían de la línea).
- **Bajo fuego** (después de probar): si el bando pierde vida `UNDER_FIRE_TICKS` (8 s) sin nadie en cuerpo a cuerpo
  (`MELEE_CONTACT`), `View.underFire`: línea defensiva y loma × 0,3 (salvo que tenga más arqueros) y ataque total +1,5;
  piensa en el acto. Una línea en HOLD sale a pelear a `HOLD_ENGAGE` (7).
- **Escudos al avanzar** (pedido de Ale): ADVANCE con ≥50% de escudos y el enemigo con ≥25% de arqueros → `Formation.shieldsUp`
  (`setShouldBlock` a los que tienen escudo y no pelean), línea cerrada y velocidad × `SHIELD_ADVANCE_SPEED` (0,6). Sin
  escudos, se abren (LOOSE). Bajo fuego mira la vida que pierden: si los escudos paran las flechas, la línea aguanta.
- `Morale` (en memoria): inicio 60 + 4/nivel (+10 IA en difícil); baja por compañero caído cerca (solo en batallas de
  campo), general caído, golpes de costado/detrás, herido, perdiendo; sube ganando o cerca del general. <15 huye: el de
  la IA se va del campo, el tuyo vuelve con 40. **Golpe por el costado ×1,25 / por detrás ×1,5** en toda pelea contra un
  soldado (`ArmyEvents.onHurt`, ángulo con el cuerpo o el caballo).
- Dónde: Hueste (después de formar 6 s; táctica RETREAT sin enemigos a `RETREAT_SAFE` = se desbandó), batallas entre
  facciones contra aldeas sin muralla (ya no: ahora van como asedio, ver "Aldeas de facción vivas"; los tuyos que se
  metieron cuentan como enemigos), ejércitos del campo (`Ambush.tick` cada 1 s: emboscadas y marchas que cargaste, contra
  el jugador más cercano a ≤96). Los ataques a aldeas (H3/H9, `BattleGeneral`) y los asedios (H17) quedan como estaban.
- **Delegar el mando** (vista aérea → fila del ejército → Delegar; `ArmyData.Mode.DELEGATE`, `Armies.delegate`): el general de
  la IA maneja tu ejército (`Armies.delegateTick`); cualquier otra orden lo saca (`TeamAI.forget`).
- Pruebas: `H24GameTests` (táctica según la situación, formas, ángulos, la infantería avanza con los arqueros detrás).

## El Mercado (a probar — plan en `plan-h24-mercado.md`; pedido de Ale, números decididos sin Ale, a revisar)

- Ojo: "H24" también lo usa la IA de batalla (otra sesión). Las pruebas del mercado son `MarketGameTests`.
- 3 niveles: `Upgrade.MARKET` (el de H12, id `market`) + `MARKET_2` (Plaza de mercado) + `MARKET_3` (Lonja), mismo terreno
  (`siteKey` "market", `isLevelUp`), aldeas y castillos; `TownData.Town.marketLevel()`; plano en `Blueprints` (`stall`).
  Por nivel (`market/Market`): mercaderes 1/2/3, ofertas 8/16/24, envíos programados 2/5/10; desde la Plaza los envíos del
  mercado tardan `FAST_FACTOR` (0,75). Nivel 2 **no** da esmeraldas extra (decidido con Ale).
- **Mercader** = `worker/MerchantEntity` (`warbandcraft:merchant`, como el Tabernero; `Job.MERCHANT`, se asigna en Gestión →
  Aldeanos, máx. = nivel; atiende en `Market.workplace` = medio del mercado o donde se le marque con un click).
  Click derecho: el dueño abre la pantalla Mercado (agachado: la de Workers); otro jugador, la tienda **de vanilla**
  (`MerchantMenu` con `Market.Trader implements Merchant`). Solo vende si está atendiendo (de día, a ≤`ATTEND_RANGE` del puesto).
- Quién compra (decidido con Ale): jugadores de **otra** facción de Recruits (`Territory.factionOf` del dueño vs equipo del
  comprador; `OWN_FACTION_BUYS` = 0), nunca en guerra (`RecruitsDiplomacyManager` ENEMY). Solo comprar, no venderle al mercado.
- Ofertas (`Market.offers`): lo del almacén de la aldea sin datos (nada encantado/gastado), con la venta prendida, de a lotes
  por encima del **mínimo** (`MarketData.Rule`: mínimo de partida `DEFAULT_MIN` 32, precio de `Prices`, vender sí); lo que más
  hay primero; esmeraldas nunca. `Market.sold`: saca del almacén, mete las esmeraldas (sobrante → `CampRegistry.deliver`),
  anota en `Market.sales` (20) y avisa al dueño.
- Precios: `data/warbandcraft/warband_prices/*.json` (`{"minecraft:bread": {"lot": 16, "price": 3}}`, esmeraldas por lote, tope 64);
  lo que no está: lote 16 (1 si no se apila) y 2 esmeraldas. El dueño cambia el precio en la pantalla.
- `market/MarketData` (SavedData por dimensión, por centro, con `owner`: si cambia de dueño arranca de cero): reglas, envíos
  programados, ventas; `snapshots` (en memoria) = última cuenta de cada almacén para la Lonja.
- **Envíos programados** (`Schedules`): destino = punto tuyo alcanzable por ruta que recibe ítems (`Market.takesItems`), cada
  1-7 días, hasta 6 líneas "fijo N" o "reponer hasta N" (cuenta lo del destino + lo que va en camino). Salen en
  `VillageEconomy.checkNewDay` → `Schedules.onNewDay` como envío de H13 (`Market.ship`: carga los chunks de los almacenes con
  `level.getChunk`, nunca baja del mínimo del origen). Pausar / Mandar ahora / Borrar.
- **Lonja** (`Exchange`, nivel 3, decidido con Ale): ve lo de cada almacén conectado (por encima de su mínimo; un Campamento
  común no tiene mínimo) y pide: envío normal hacia la aldea (`Exchange.order`, texto "item=n,item=n"). Nada instantáneo.
- Pantalla `client/market/MarketScreen` (Ventas · Envíos · Lonja; vuelve a la pantalla de antes al cerrar): hablándole al
  Mercader, Gestión (V) → Rutas → "Mercado..." o `/warband mercado`. Red: `MarketActionMessage` → `OpenMarketMessage` (`MarketView`).
- No hecho (a revisar): que los envíos programados se vean en el Panel (K) → Envíos.

## Trabajadores automáticos (H18, a probar — plan en `plan-h18-trabajadores.md`)

- Workers solo trabaja con todo lo que pide en `neededItems` (minero: pico, pala, 16 antorchas, 16 de relleno; granjero:
  azada + 1 semilla por lugar), lo va a buscar caminando y deja lo que junta recién con 128 (`farmedItems`). Por eso
  "no trabajaban / no generaban".
- `village/WorkerSupply` (cada 5 s desde `RaidEvents`, aldea tuya cargada, no en `GameTestServer`; las pruebas llaman a
  `serve`): equipo por oficio (`kit`) + lo que pidió Workers, del almacén de la aldea **directo a la mochila**
  (`NeededItem.applyToNeededItems` para que no lo vaya a buscar); comida con hambre (`Barracks.takeFood`); cada 30 s lo
  que juntó pasa al almacén (se queda con su kit, herramientas y 4 de comida; `farmedItems = 0`); cama: zona Hogar de
  Workers sobre una cama libre (POI `home` reservado con `PoiManager.take`, marca `warbandcraft:bed`; al morir se libera).
  Al convertir un aldeano, su cama de aldeano pasa a ser la del trabajador. `Housing` cuenta a los trabajadores sin cama.
- Agua de la huerta (pedido de Ale: "nunca pone agua, ara infinitamente"): Workers solo pone agua con un balde de agua en un punto
  fijo para 9×9 (`CropArea.getWaterPosCenter`); sin agua lo arado se seca y lo vuelve a arar. `WorkerSupply.water` (en `serve` y en
  `CampWorkers.serve`) la pone gratis ahí y en una grilla cada `WATER_SPACING` (9) que cubre la huerta, salvo con agua a ≤4; solo con
  piso y costados firmes. Saca el balde de `neededItems`. **La causa de fondo**: Workers ara/riega el bloque de debajo de la entidad
  (`getOnPos`) y `WorkerZone.place` la deja metida en el piso (araba la tierra de abajo del pasto, imposible, para siempre):
  `WorkZones.fixCropLevel` la sube 1 al engancharla (`bind`) y cada vez que se atiende al granjero (arregla las viejas).
- Minero: `MiningArea.setKeepOn(true)` (no queda "terminada"); cada 60 s trabajando saca de la veta (`vein`: piedra 60,
  carbón 20, hierro crudo 14, oro crudo 5, diamante 1; bajo y=0 mejor) al almacén; mina vacía → baja 8 bloques (4 veces).
- Cartelito: `WorkerSupply.status` (Falta en el almacén / Almacén lleno / Con hambre / Buscando / Durmiendo / sin cama /
  Trabajando) va en `WorkerRow` y `AreaRow` (zona del trabajador = `WorkZones.areaFor`) y se dibuja sobre cada zona.
  Lo que falla queda anotado en memoria 30 s (`NOTE_TICKS`). Las zonas Hogar no se listan.
- Marcar zona: el territorio se pinta de verde (rojo = zonas existentes) y el rectángulo sale rojo con el motivo
  (`ManagePanel.rectProblem`, mismas reglas que `WorkZones.mark`).
- **Producción por día y aunque no esté cargado** (pedido de Ale, a probar; números sin Ale): `village/WorkerOutput` (SavedData por
  dimensión, por trabajador): cada `serve` anota lo que deja en el almacén (`collect`, veta, cofre del puesto) y el tiempo atendido
  → ítems/día (`rate`: lo medido con ≥ `MIN_MEASURE_TICKS`, si no lo esperado del oficio `WorkerSupply.estimate`/`EST_*`). Tiempo
  sin atenderlo (hueco > `GAP_TICKS`): al volver a atenderlo, o cada 5 s si el almacén de la aldea está cargado y él no
  (`WorkerSupply.tick`), se paga rate × tiempo (máx. 7 días, fracciones en `carry`) al almacén; si fue >2 min, aviso por chat.
  Sin su entidad con su lugar cargado `MISSING_CHECKS` veces → se olvida. Gestión: `AreaRow.production` (cartelito + lista) y
  `Data.production` (Resumen, total de la aldea). El trigo del granjero se hace pan al juntarlo (`bake`, 3 → 1).
- **Mina en escalera** (pedido de Ale): la zona del minero es `MiningMode.STAIRS_DOWN` de Workers (`WorkZones.placeStairs`:
  rectángulo largo ≥ `MIN_MINE_LENGTH` y angosto ≤ `MAX_MINE_WIDTH`, baja desde la punta más cerca del almacén; Workers mide los
  escalones desde la posición de la zona, parada sobre el piso). Marcas `warbandcraft:mine_stage/mine_dir/mine_end`. Cuando
  `stairsDone`, `WorkerSupply.advance` la pasa a CUSTOM: pasillos 3×3 de `CORRIDOR_LENGTH` desde abajo, derecho y si se sale del
  territorio dobla (hasta `CORRIDORS`). Minas viejas (pozo, sin marca) siguen bajando como antes.
- **Puesto de leñador** (pedido de Ale): `WorkZones.mark` acepta una zona de tala fuera del territorio a ≤ `OUTPOST_RANGE` (200)
  del centro, cargada y lejos de otras aldeas (`outpostProblem`). Cofre sin vincular + cama al lado (`outpostBed`), deja la cama de
  la aldea (`WorkerSupply.leaveHome`), aparece ahí; `Rec.outpost` + marca `warbandcraft:outpost` (centro) en la zona. Cada 5 s
  (`WorkerSupply.outpost`) el cofre pasa al almacén (deja retoños si le faltan) y a > `OUTPOST_LEASH` vuelve a su cofre.
  `VillageManagement.workers` y `WorkZones.areas` incluyen los puestos cargados. Borrar la zona lo trae al almacén.

## Asedios (H17, a probar — plan en `plan-h17-asedios.md`; pedido de Ale, estilo Bannerlord, detalles a revisar)

- **Malla de navegación propia** (`nav/`, se arma como la navmesh de Bannerlord, sin copiar su código): `NavGrid` mide cada
  columna en franjas de 32 de alto (sirve bajo tierra / castillos) y guarda los lugares donde se puede estar (piso, arriba
  de muros, peldaños de escalera, superficie del agua) con banderas WATER/LADDER/DOOR; `NeighborNotifyEvent` y las
  explosiones borran lo medido (`event/NavEvents`). Conexiones: caminar (8 lados, sin cortar esquinas), escalón, bajar
  hasta `maxDrop`, puerta (solo `openDoors`), escalera (vertical), agua (× `NavCaps.AVOID_WATER` = 30: se evita lo más
  posible, si no queda otra se cruza en bote) y dos "apagadas" para planear: `CLIMB` (escalera nueva, 2-12 de alto, la
  almena se tira) y `BREAK` (TNT/cañón, hasta 4 de grueso). `NavPath.find` = A* (20000 lugares, parcial opcional).
- `Mover` + `RouteGoal` (objetivo prioridad 0, MOVE+LOOK, se suma a todo recluta en `NavEvents`): lleva al soldado por el
  camino tick a tick (MoveControl), sube/baja escaleras guionado (si la tumban, se cae), sale de arriba deslizándose, abre
  puertas, cruza agua en bote (lo rema: `Boat` con marca `warbandcraft:nav_boat`, se borra al bajar). Trabado: salto,
  saltito, y a los 8 s aparece en el próximo lugar (3 veces y se rinde). `Unstuck` no toca a los que van por la malla.
- **Asedio = dos piezas reusables** (`battle/`): `SiegeLanes` (atacante: carriles replaneados cada 8 s con
  `NavCaps.siege` desde su frente; sin pasos apagados = hueco abierto, siempre primero; `CLIMB` → 2 escaleras, 12 s
  para volver a poner una tumbada; `BREAK` → cañón de la IA si `Host.cannons()` y hay lugar a 12-26 del muro, si no el
  zapador con TNT; grupos de 3-5 detrás de un guía con `NavCaps.INVADER`; arqueros afuera con orden) y `SiegeDefenders`
  (defensor: reparto por carril según atacantes, arqueros al mejor lugar (`spots`: alto, con vista al cruce, ~14
  bloques), 2 por escalera a tumbarla desde arriba o desde el pie de adentro (`PUSH_REACH` 2,6), el resto espera
  adentro del cruce). Cada pelea les pasa un `Host` (centro, dónde se guardan las escaleras, avisos). En un asedio
  todos pelean de a pie (`SiegeLanes.dismount`; tus caballos quedan ahí, los de la IA se van).
- Usos: **invasiones** (`invasion/Assault`: aparecer en lados a los que se llega por tierra, general, formar 15 s,
  moral por soldado: −7 por compañero caído a 12, −25 el general, poca vida; <20 huye; retirada general como antes;
  escaleras en `Invasion.ladders`) + `invasion/SiegeDefense` (tu cuartel; refuerzos cada 3 s: `quarters` guardados y
  después el barracón de la aldea, marca `warbandcraft:reinforcement`, vuelven al barracón). **Tu ataque a un castillo
  o aldea con muralla** (`BattleGeneral.siegeTick`, si hay soldados tuyos en Batalla: 1 carril, 2 con ≥10, escaleras en
  `Raid.ladders`; la guarnición defiende con `SiegeDefenders`, el capitán en el centro; sin nadie en Batalla, el
  general de siempre). **Batallas entre facciones** (`AiWars`: aparecen afuera con `SiegeLanes.stagePos`, marca de
  carril `Assault.TAG_GROUP`; contra muros, asedio con cañones; escaleras en `March.ladders`; sin muros, sueltos).
- Con zapador, el carril abre paso así (`SiegeLanes.breakWith`): **cañón** (uno por asedio, con TNT en la mochila y lugar a
  12-26 del muro; tus soldados lo pagan con `SapperRules.payCannon`, la IA no), si no **túnel** (`Breach.tunnel`: arranca
  2 afuera del pie del muro, baja 3, pasa por abajo hasta estar adentro y sube; nada de agua al lado ni bloques con
  dureza > 20 o block entities; el zapador cava 1 tramo por segundo metido adentro; al terminar el carril se replanea y
  los demás entran por el túnel; los túneles quedan), si no **TNT**; sin zapador, escaleras. `Entry.TUNNEL`.
- Cañones de asedio (`FieldCannonEntity.setAi`, marca `warbandcraft:ai_cannon`): no se guardan (se borran al cargar).
- El carril se planea desde el más adelantado que no es zapador (el zapador puede estar en su túnel o en el pozo del TNT
  y ver un "hueco" al que el resto no llega); si el guía de un grupo no encuentra el camino que el carril cree abierto,
  se replanea desde él (`Lane.planFrom`) → escaleras/romper.
- TNT de asedio (zapador del carril y cañón de la IA) lleva `SapperRules.TAG_HOLE` = el paso del `BREAK` (`SiegeLanes.holeCells`:
  1 de ancho × 2 de alto atravesando todo el muro): sin cráter, abre solo eso si explota a ≤4,5 (`MyFactionEvents.onSiegeBlast`,
  pedido de Ale). El carril no cambia de punto hasta abrirlo (`committed`). Túnel: sube hasta 16 (`Breach.TUNNEL_RISE`).
  Guía sin camino directo a un hueco lejano: primero va por afuera hasta `lane.outside` y de ahí adentro.
- Tus soldados a ≤64 de la aldea invadida que no son del cuartel, no te siguen (estado 1/5) ni están en Batalla se suman
  a `SiegeDefenders` (`SiegeDefense.helpers`, marca `warbandcraft:defense_helper` con su orden) y al terminar la recuperan.
- **Barra de quién va ganando** (`battle/BattleBar`, estilo Total War): una barra que se llena con la parte de soldados
  que le quedan al bando de la izquierda; verde/amarillo/rojo para tu bando (va ganando con ≥60%), blanca en peleas de
  otros. Tus ataques (`RaidManager.updateBar`, reemplaza a la barra vieja de defensores), invasiones a tu aldea
  (`Invasions.bar`, si estás a ≤132) y batallas entre facciones (a los que están a `BATTLE_NEWS_RANGE`).
- Campo abierto (H9): `BattleEscape` ya no teletransporta: encerrado → el zapador pica, el resto busca el camino por
  la malla (`Action.NAV`, el general no lo toca mientras va); sin camino → `HOLD` y vuelve a probar. A caballo: saltito.
  Los arqueros de torre del cuartel suben por la escalera (`Quarters.guardTower`; sin camino, aparecen arriba).
- Reparar murallas (`upgrade/WallRepair`): se vuelve a medir el anillo (`Blueprints.ring`) y faltan los bloques que no
  están; las columnas de torres se saltean. Fila "Reparar murallas" arriba de las mejoras (Panel K y Gestión → Mejoras,
  `Panel.repairRow`), obra `Upgrade.REPAIR` (no `listed()`), 1 esmeralda cada 8 bloques (mín. 2). Al terminar una pelea
  con el muro roto, aviso en el chat.
- `/warband prueba invasion [incursion|ejercito] [cantidad]`: llega en el acto (también `prueba reconquista`).
  Cada decisión de carril va al log ("Asedio a ...: carril N").
- Pruebas: `H17GameTests` (asedio) y `NavGameTests` (hueco, muro cerrado → escalera/romper, escalera puesta es camino,
  agua, puertas, un recluta que trepa la escalera de verdad); H9: `h9AsedioConMuralla`, `h9EncerradoNoSeTeletransporta`,
  `h9EncerradoSalePorLaPuerta`. `Breach.probe/openAngle` (mediciones en línea recta) quedan
  solo para pruebas viejas.
- A revisar por Ale: la caballería en campo abierto sigue con el movimiento de Recruits (la malla es solo de a pie);
  no hay botón "Escaleras acá" en la vista aérea (las ponen solos donde dice el camino).

## Caminos (H14, a probar — plan en `plan-h14-caminos.md`; decisiones tomadas sin Ale, a revisar)

- Se encarga desde donde sale la ruta (pedido de Ale): Campamento común → pestaña Rutas, modo Caminos
  (`RouteActionMessage.BUILD_ROAD`, `RouteInfo.here`); aldea/castillo → Mejoras (Panel K y modo Gestión), filas
  `road:<id>` de `Panel.roadRows` que `Panel.build` manda a `Roads.order(player, id, from)` (`Roads.touches`: un extremo
  es ese punto). Cuesta 1 esmeralda cada 10 bloques (mín. 5): almacén de ese punto, inventario y después Campamentos cargados.
- `RoadPlanner.plan` carga los chunks de la ruta (una vez) y mide: altura suavizada (mediana de 5 + nunca más de 1
  entre pasos), grava 3 de ancho con piedra abajo si hay aire, terraplén de tierra, corte/túnel (≥3 = túnel 3×3 con
  antorcha cada 8), agua ≤24 = puente de roble con barandas y pilotes cada 6, más = muelle de 3×7 en cada costa.
  No toca dirt_path, casas (bloques no naturales) ni block entities.
- Obra (`RouteData.RoadJob`): 5 bloques de ruta/s, 60 s a 5 min, por tiempo; cada bloque se pone cuando le toca y su
  chunk está cargado (BitSet `placed`). Al vencer el tiempo `road = true` (aunque falten tramos sin cargar). Constructor
  (`upgrade/BuilderNpc`, marca `road:<id>`) acompaña el frente si está cargado. `Roads.tick` corre cada tick
  desde `RouteEvents` (también en GameTestServer).
- Re-registrar la ruta con el mismo trazado conserva el camino; con otro, lo saca y corta la obra (`Roads.cancel`).
  Borrar la ruta también corta la obra.

## Mejoras visibles (H12, a probar — plan en `plan-h12-mejoras.md`; decisiones tomadas sin Ale, a revisar)

- Se encargan desde el Panel (K) → Aldeas → Mejoras (`PanelActionMessage` VILLAGE / BUILD). Una obra por aldea.
  Pagan esmeraldas: almacén de la aldea primero (`VillageStock.takeEmeralds`), el resto del inventario.
- `Upgrade` (enum): taller del constructor 1-3 (regla de MineColonies: `builderLevel` mínimo), empalizada, muralla,
  torres, pueblo, ciudad (solo aldeas), cuartel, herrería, mercado, granero (aldeas y castillos). Costos en el enum,
  efectos como constantes en `Upgrades` (`apply` en `VillageStock.daily/cap`; cuartel → `Barracks.capacity`, que
  reemplaza a `BarracksData.CAPACITY` en todo lo que mide lugar).
- `TownData` (SavedData por dimensión, aparte de `VillageData`: si cambia de dueño, los muros quedan): hechas,
  terrenos de edificios (esquina mínima, y = piso; los talleres comparten "builder"), radio del anillo y la obra
  (bloques en orden + paleta). Encargar necesita la aldea cargada (para medir el terreno).
- Obra: 8 bloques/s, entre 60 y 120 s, avanza por tiempo (`Job.due`): descargada, lo que falta aparece de golpe al
  volver. Nunca pisa un bloque con block entity. Constructor = aldeano albañil sin IA, invulnerable, marca
  `warbandcraft:builder` = `town:<centro>` (`upgrade/BuilderNpc`; no comercia; si se carga sin obra, no entra al mundo).
- `Blueprints.groundY(level, x, z, hintY)`: baja desde la altura de la campana +16 y el piso es el primer bloque firme
  con aire encima (no usar el mapa de alturas: en las pruebas hay terreno real encima del área).
  Anillo: radio = 90% de los POIs + 6 (16..56). **Cerrado** (pedido de Ale, a probar): sobre cada camino (dirt_path) un
  portón derecho (puertas de cerco de 2 + dintel, ≥3 de ancho, postes en las puntas unidos al muro); agua ≤`RING_WATER_DEPTH`
  = pilotes desde el fondo hasta 3/4 arriba de la superficie; casa o block entity = el muro se corre hasta `RING_DETOUR_OUT`
  afuera (o `RING_DETOUR_IN` adentro) y se une con líneas de muro (`Blueprints.connect`). Solo queda hueco con agua muy honda.
  Al terminar la obra (PALISADE/WALL/REPAIR) las puertas de abajo van a `Town.gates` (`Upgrades.ringGates`). Empalizadas de
  antes: aldeas de facción se completan solas una vez (`FactionTowns`, `Town.ringVersion` < `RING_VERSION`); tuyas, con
  Reparar murallas. Portones (`Works.tickGates`, cada 10 ticks): tuyas = vos, compañeros, sus soldados y aldeanos (cerrados con enemigos cerca:
  invasores, IA en guerra con vos, bandidos, jugadores de facciones de Recruits en guerra y sus soldados, `Works.enemyOfOwner`); de facción =
  su guarnición, aldeanos y jugadores en paz (y sus soldados); con un enemigo a `GATE_ENEMY_RANGE` cerrados. Un enemigo no lo
  abre a mano (`UpgradeEvents.onUseGate` → `Works.gateLocked`). Soldado enemigo pegado a un portón cerrado lo golpea:
  `GATE_BASH_TICKS` (600, ×2 si es de muralla; hasta `GATE_MAX_BASHERS` suman) y se rompe. Asedios: `NavCaps.bashGates`
  (INVADER y `siege`) deja pasar por un portón cerrado con costo `GATE_BASH_COST` (12, menos que escalera o TNT) y `Mover` no
  lo abre: se queda golpeándolo (sin teletransporte). Pruebas: `H12GameTests.ringClosesWithGates`,
  `H22GameTests.factionGateOpensAndGetsBashed`.
  Edificios: terreno 9×9 con desnivel ≤2 sin caminos ni casas, adentro del anillo o si no afuera; castillos a 27-52.
- GameTests: el área viene con techo de barreras; la prueba del anillo lo saca.

## Economía (H10, a probar — plan en `plan-h10-economia.md`)

- Decidido con Ale: en el Campamento todo sigue siendo ítems (esmeraldas, comida). Armamento = ítem nuevo
  `warbandcraft:armament`. Aldeas/castillos tuyos guardan tres números. Sin sueldo de tropas.
- Armamento (`kit/Armament`, reload listener de `warband_armament/`): al armar, lo que falta de una pieza con valor
  se paga con armamento (`plan` / `pay`, primero piezas de verdad). Lo usan `ClassAssigner` (armar y re-armar),
  `KitEditorMenu` (DataSlot `armamentUsed`) y el cañón del Zapador. Arcos, cuero, diamante, flechas: no.
- Herreros (`camp/Forge`): aldeanos de vanilla armero/herrero/fabricante de herramientas adultos a ≤24 bloques
  (máx. 4). Por día: 1 lingote + 1 carbón (o vegetal) → 2 de armamento, hasta 8 lingotes por herrero. Perezoso
  (`lastForgeDay` en el NBT del Campamento, `NEVER` = Long.MIN_VALUE; máx. 7 días atrasados). Corre en
  `BarracksEvents` (10 s, no en `GameTestServer`).
- **Desguace** (pedido de Ale, a probar; números sin Ale): `camp/Scrap`. Pestaña Cofre → botón "Desguace: SÍ/NO"; con el modo
  prendido, click en una pieza (almacén o inventario) = `ScrapMessage` marca/desmarca ese ítem en `CampBlockEntity.scrap` (NBT
  `Scrap`; vuelve `ScrapListMessage`, lo marcado se pinta de rojo). Solo armas/armaduras (`canBeDepleted`) con valor en la tabla
  de armamento. Los herreros sacan del almacén lo marcado (sin nombre puesto a mano) → valor × `YIELD` (0,5) × vida que le queda
  (mín. 1) + lingotes de hierro las de metal (valor × `INGOTS_PER_VALUE` 0,25 × vida, mín. 1, valor ≥ `INGOT_MIN_VALUE` 2; pedido de Ale):
  aldeanos de H10 `PIECES_PER_SMITH` (16)/día en `Forge.workDay`; el Herrero de H16 `PIECES_PER_JOB` (4) por vuelta en
  vez del lingote (pedido de Ale: ya **no** cuenta para su tope del día de `IRON_PER_DAY`; con el tope gastado sigue si `Scrap.any`). Cada
  desguace va al log ("Desguace en el Campamento ..."). Pruebas: `H10GameTests.smithScrapsMarkedPieces`, `scrapFromLinkedChest`.
- Pestaña Cofre: línea de totales arriba (`CampStats`, ContainerData, se recalcula 1 vez por segundo; los valores
  viajan como short) y botón "Zona de trabajo" (`WorkerZoneMessage` → `WorkerZone.create`: StorageArea de Workers
  que encierra Campamento + vinculados, +1 de margen, mínimo 5; prueba orientaciones hasta que la caja empiece
  en la esquina pedida; no crea si ya hay una zona que se superpone). Workers mete en esa zona cualquier
  contenedor con aire arriba, incluido el Campamento.
- Almacén de aldeas (`village/VillageStock`, guardado en `VillageData.stocks`; cambiar de dueño lo vacía): por día
  impuesto (`taxOf`) + comida (aldea 4 + 1 cada 2 aldeanos, máx. +6; castillo 4) + armamento (castillo 4). Tope =
  7 días de la mejor producción. Los impuestos ya **no** van solos al Campamento. Se retira en mano a ≤48 bloques
  (`/warband retirar`, botón en el chat al entrar) o se manda por ruta (pantalla de Rutas parado en la aldea,
  botón Mandar junto a cada Campamento → `RouteActionMessage.SEND_STOCK` → envío de H13 con ítems).

## Batallas (H9, a probar — plan en `plan-h9-batallas.md`)

- Alcance decidido con Ale: batallas reales (IA táctica, prisioneros, guardado). Autoresolución, invasiones y que la
  IA te conquiste quedan para H15.
- `BattleGeneral.tick` corre desde `RaidManager.tick` (1 s) por ataque en curso con la aldea cargada. Defensores =
  guarnición (siempre). Atacantes = tus soldados con marca `warbandcraft:battle` a ≤96 bloques (`REINFORCE_RANGE`).
  Plan por relación de fuerzas (`power`: vida × armadura × nivel, ×1,5 a caballo, jugador ×2): defensor Cargar /
  Línea / Replegarse; atacante Cargar / Avanzar / Aguantar. Constantes arriba de la clase.
- Órdenes: formarse = `setHoldPos` + estado 3; pelear = estado 0 + `setTarget`. Cada orden se anota en memoria: si
  el soldado ya no está en eso (otra orden de Recruits), sale de la Batalla. La vista aérea (mover/seguir/mantener)
  también la saca. Rol por `roleOf`: capitán, caballería (montado), a distancia (arco/ballesta), infantería.
- Castillo: arqueros defensores a los puntos más altos del castillo (`wallPosts`, ≥4 sobre el piso, separados 3).
- Botón Batalla en la vista aérea (`BattleOrderMessage`, alterna) y `/warband batalla [parar]`. Al terminar el
  ataque `BattleGeneral.end` los devuelve a seguirte.
- Rendirse (`Surrender`): `LivingDamageEvent` mortal en un ataque → chance 45% − 7%/nivel (mín. 10%), nunca el
  capitán. Rendido = marca `warbandcraft:surrendered` (centro), invulnerable, velocidad ×0, acostado (`Pose.SLEEPING` sin cama), pasivo,
  sangra (partículas de polvo de redstone cada 1 s en `BattleEvents`); no
  cuenta en `VillageManager.garrison`. En `GameTestServer` solo se rinde quien tiene `warbandcraft:surrender_test`.
- Ganar: rendidos enemigos + defensores en pie (capitán caído) → `Prisoners.imprison` al Campamento más cercano
  (lista `prisoners` del barracón, máx. 40, no comen); si no hay, `release` (como H4). Perder (`abandon`): tus
  rendidos → `VillageManager.joinGarrison` (sin dueño, marcas y colores de la facción, mismo nivel).
- Destrabador de batalla (`BattleEscape`, desde el general cada 1 s, ambos bandos + tus soldados peleando cerca):
  3 s sin acercarse 1 bloque → yendo a su puesto: deja la formación (`free`, solo busca enemigos); yendo al enemigo
  con camino: saltito (`Unstuck.stepToward`); sin camino: zapador pica (pies/cabeza hacia el enemigo, no cofres ni
  bloques duros, respeta mobGriefing), el resto va por la malla de navegación de H17 (puertas, escaleras, rodeos); sin
  teletransportes desde H17. Las pruebas encierran cada test en barreras: sin cielo.
  Defensores de castillo (`stayInside`): no salen nunca; trabados yendo al puesto, saltito; enemigo inalcanzable
  (afuera) → lo sueltan y quedan quietos `HOLD_TICKS` (5 s), los arqueros siguen tirando. Si el enemigo entra, pelean.
- Rendido cuyo ataque terminó sin estar cargado: `BattleEvents` (cada 5 s) lo levanta o lo pasa a la guarnición.
- Prisioneros en la pestaña Barracón: Reclutar (ficha de soldado tuyo; recupera clase por `warbandcraft:troop_kit`/
  `troop_tier`, que la guarnición anota al aparecer) y Rescate (3 + 2/nivel esmeraldas, capitán 30; +3 opinión).
- Un ataque ganado sin decidir vuelve a abrir la pantalla Saquear/Conquistar al entrar (`choiceShown`).
- Ejércitos de prueba (`command/TestArmyCommand`, nivel 2): `/warband prueba enemigos [tipo] [cantidad] [facción]`
  (aldea sin campana a 28 bloques adelante, marcada en `VillageData.tests`, el ataque empieza solo; sin cambio de
  opinión, sin refuerzos, sin botín; al ganar se borra en vez de Saquear/Conquistar), `mios [tipo] [cantidad]`
  (gratis con `ClassAssigner.equipFree`, ya en Batalla) y `limpiar`. Tipos: mixto, infanteria, arqueros, caballeria,
  zapadores. Marca `warbandcraft:test_army` en soldados y caballos. H26: después de la cantidad, nivel opcional
  `basico|veterano|elite|mixto` (índice del nivel del kit + `setXpLevel`; mixto = al azar), p. ej. `enemigos mixto 12 elite valdren`.

## Castillos (H4)

Aparecen como una mazmorra más: no dependen de ninguna aldea ni están limitados a uno por facción.
`CastleManager.scan` corre en `VillageEvents` al mismo tiempo que `VillageManager.scan` (independiente, no
encadenado): cada 5s por jugador, si la zona no se exploró ya (`CastleData`, memoria de intentos a
`ATTEMPT_SPACING` bloques), tira un dado (`SPAWN_CHANCE_1_IN`) y si sale, elige un punto al azar cerca
(48-200 bloques) y, si no hay otro castillo a `CASTLE_SPACING` bloques (`hasCastleNear`), le asigna facción
por bioma y lo planta (`CastleManager.place`). Por eso algunas aldeas van a tener un castillo cerca de
pura coincidencia y la mayoría no, y pueden convivir varios castillos de la misma facción.
Se busca un lugar razonablemente plano alrededor de ese punto (forzando que el chunk exista con
`level.getChunk`), se allana la huella y se planta el castillo.
- **Castillos por facción** (pedido de Ale, a probar; diseños y números sin Ale): `castle/`. `CLASSIC_CHANCE_PERCENT` (35)
  = el plano de siempre (`castle_default.nbt`, 43x28x45, adoquín + abeto) girado alrededor de su medio y con los materiales de la
  facción (`CastleRestyle`, un `StructureProcessor` que cambia bloque por bloque copiando propiedades; Norte queda igual).
  Si no, el dibujado de la facción (`CastleKind.drawnFor`, `CastleDesigns`): Valdren **Fortaleza** (ladrillo con musgo, torres
  redondas, homenaje de 2-3 pisos, rastrillo, foso y techos al azar), Norte **troncos** (empalizada sobre piedra, torres de vigía,
  gran salón con fogatas), Sultanato **kasbah** (arenisca, almenas escalonadas, franja naranja, arco, fuentes y palmeras),
  Pantanos **pantano** (barro y mangle, foso con puente, casa fuerte sobre pilotes). Cuadrado de 37/41/45, giro al azar
  (`CastleCanvas` gira como `Buildings.Canvas`). Siempre: muros ≤ `Breach.MAX_LADDER`, portón abierto (sin puertas: los
  invasores no abren), camino de ronda con escaleras de mano, barracas con barriles (armería) y el cofre del tesoro en el homenaje
  (entrada sin puerta). Local: portón al sur, homenaje al norte, patio libre en el medio (capitán).
- **Forma** (`CastleShape`, guardada en `CastleData` por centro): caja de adentro, radios del asedio, claim, caja del almacén.
  `CastleManager.shape(level, v)` la da (castillo de antes = `legacyShape`, el plano sin girar) y la usan `insideWalls`,
  `Breach.walls`, `FactionClaims.measure` y `VillageStorehouse.areaFor`. `BattleGeneral.wallPosts` sigue midiendo ±21/22.
- Prueba: `/warband prueba castillo [facción] [clasico|fortaleza|troncos|kasbah|pantano] [giro]` (castillo de verdad, 45
  adelante). GameTests `CastleGameTests` (a 4000+ bloques). Un castillo es una
`VillageData.Village` más (`castle()=true`, mismo guardado/impuestos/saqueo/conquista que una aldea), con:
- Guarnición más grande (15-30, `CastleManager.GARRISON_MIN/MAX`) y radio de patrulla mayor
  (`CastleManager.PATROL_RADIUS=40` en vez de los 32 de una aldea); los soldados llevan la marca
  `warbandcraft:castle` para que `VillageEvents` les reaplique el radio correcto al cargar el chunk.
- Un `recruits:captain` fijo (marca `warbandcraft:captain`), equipo de hierro + estandarte de la facción en la
  mano secundaria. Si cae, `RaidManager.tick` termina el asedio aunque sigan vivos otros defensores.
- (Reemplazado en H9: ahora los sobrevivientes van presos a tu barracón; solo si no hay Campamento o lugar quedan
  sueltos así, con `Prisoners.release`.) Prisioneros: al caer el capitán con defensores vivos, `CastleManager.freeDefenders` les saca las marcas de
  guarnición y la restricción de movimiento — dejan de ser hostiles y se pueden reclutar como cualquier
  recluta de Recruits (sin pantalla nueva).
- El Zapador (`recruits:siege_engineer`) queda como clase de soldado jugable más: Recruits solo abre brechas con
  un mod de catapultas/ballestas que no es dependencia nuestra, así que puertas y muros son resistentes y se
  abren a mano.
- Botín (pedido de Ale): el plano traía tablas `eugene:chests/...` que no existen (cofres vacíos). Al pegarlo,
  `CastleManager.fillLoot` pone `chests/castle` (barriles = armería) y `chests/castle_treasure` (el cofre del torreón).
  Castillos de antes: `fixOldLoot` cambia la tabla rota al abrir o romper el contenedor (`VillageEvents`).
- Entrar sin permiso (pedido de Ale, a probar): adentro de los muros (`insideWalls`: caja `INSIDE_*` en coordenadas del
  plano, que no se gira) de un castillo de facción con guarnición → aviso en la barra cada 1 s y a los
  `TRESPASS_GRACE_SECONDS` (5) `RaidManager.start` + la guarnición contra vos (`trespassTick`, desde `VillageEvents.onPlayerTick`).
  No en creativo/espectador ni viajando.
- Ofensas en aldeas (pedido de Ale, a probar; números sin Ale): en una aldea/castillo de facción (sin dueño, sin ataque),
  romper heno/cultivos (`CropBlock`, `StemBlock`) o abrir un cofre/barril (`RandomizableContainerBlockEntity`) con un testigo
  (guarnición o aldeano despierto a ≤`WITNESS_RANGE` 16 con vista) = te echan (`village/Offenses`, −3 de opinión): adentro
  de la aldea (`ENTER_RADIUS`) tenés `LEAVE_SECONDS` (15) para irte; si no, `RaidManager.start` + la guarnición contra vos.
  La expulsión dura `BAN_SECONDS` (180) desde la última ofensa: si volvés, la cuenta sigue donde quedó. En memoria.
- Fuera de este hito: que una aldea atacada pida refuerzos al castillo más cercano.

Entidades de Recruits útiles: `recruit`, `recruit_shieldman`, `bowman`, `crossbowman`, `horseman`, `nomad`,
`scout`, `siege_engineer`, `captain`, `patrol_leader`.

## Detalles de Recruits a tener en cuenta

- `AbstractInventoryEntity.getInventory()`: 15 espacios; 0-5 son el equipo puesto (cabeza, pecho, piernas,
  pies, mano secundaria, mano principal). La mochila empieza en 6.
- `isOwnedBy(Player)` compara UUID con `==` (bug): usar `Objects.equals(getOwnerUUID(), player.getUUID())`.
- Cambiar de clase = crear la entidad nueva copiando NBT sin `UUID`, `discard()` de la vieja, después
  `updateTeam()` y `RecruitEvents.recruitsPlayerUnitManager.recountRecruits(...)`.
- **Sin tope de soldados** (pedido de Ale): `mixin/NoRecruitLimitMixin` (único mixin propio, `warbandcraft.mixins.json`,
  `remap = false`; jar = `MixinConfigs` del manifiesto, dev = `-mixin.config` en `build.gradle`, solo client/server: en gameTestServer no arranca) hace que
  `RecruitsPlayerUnitManager.canPlayerRecruit` dé siempre true y `getRemainingRecruitSlots` 1.000.000.000. Por ahí pasan
  todas las contrataciones (recluta, noble, cambio de dueño). Ojo: `RecruitsFaction.npcs` sube con cada `updateTeam` y
  solo baja al morir (lo que se borra con `discard()` queda contado); ya no importa porque no se chequea.
- Patrullas NPC: `world/RecruitsPatrolSpawn` (base para las facciones NPC de H2).
- Facciones y diplomacia: `world/RecruitsFactionManager`, `RecruitsDiplomacyManager`.

## Versión en el nombre del jar (pedido de Ale, 24/10, vale para todas las sesiones)
El jar se llama `warbandcraft-<mod_version>-<commit corto>.jar` (ej. `warbandcraft-1.0.0-60d2304.jar`) y esa misma versión
sale en la lista de mods del juego: así se ve enseguida qué versión tiene el server y cada jugador. `build.gradle` la arma con
`GITHUB_SHA` en Actions o `git rev-parse --short=7 HEAD` en la PC (sin git queda `1.0.0`) y borra los jars viejos de
`build/libs` antes de armar el nuevo. Nada tiene que asumir el nombre fijo `warbandcraft-1.0.0.jar`: buscar
`warbandcraft-*.jar` (sin `-sources`/`-slim`). Ya lo hacen `make_pack.py`, `compilar.bat`, `comun.ps1` (mueve las versiones
viejas a `mods-viejos/`) y los workflows `build.yml` y `deploy.yml` (el `mirror --delete` borra del server el jar viejo).
`diagnostico.yml` también (`mget /mods/warbandcraft-*.jar`).
`mod_version` en `gradle.properties` sigue siendo la versión "humana" (se sube a mano) y la de los nombres de los zips.

## Compilar

`./gradlew build` → `build/libs/warbandcraft-<versión>.jar`. Ese jar va a la carpeta `mods` junto con Recruits.
`./gradlew runClient` abre Minecraft de desarrollo con Recruits cargado.

## Hitos

H0 base ✔ · H1 Campamento ✔ · H2 facciones NPC ✔ · H3 raids ✔ · H4 castillos y asedios (a probar) ·
H5 vista aérea RTS (a probar) · H6 tu facción (a probar) · H7 arte (Blockbench MCP, salteado por ahora) ·
H8 barracones (a probar) · H9 batallas (a probar) · H10 economía (a probar) · H11 panel de control (a probar) · H12 mejoras visibles (a probar) ·
H13 rutas y viaje (a probar) · H14 caminos (a probar) · H15a IA viva: tesoro, leva, robo, invasiones (a probar) ·
H15b patrullas, guerras entre IA, ejércitos en el mapa, emboscadas (a probar) · H16 aldeas como Campamentos y modo Gestión (a probar) ·
H17 asedios: incursiones y ejércitos con general contra tus aldeas, escaleras, zapadores, reparar murallas (a probar).
H18 trabajadores automáticos (a probar). H19 taberna, entrenamiento y armar con armamento (a probar).
H21 crecimiento de aldeas estilo Stronghold (a probar). H22 construir a mano desde Gestión: edificios, terreno, murallas
dibujadas por niveles, portones y torres (a probar). H20 ejércitos de campaña (a probar). H23 meterse en batallas, asediar con
la tecla B, la Hueste y el tamaño de batalla (a probar). H24 IA de batalla estilo Bannerlord: general, tácticas, formaciones,
moral, flancos, delegar el mando, dificultad (a probar). Mercado: vender a otros jugadores, envíos programados, Lonja (a probar).
H25 mazmorras: 3 mods de mazmorras compatibles, biomas llanos, guaridas de bandidos (asaltan envíos), túmulos nórdicos,
rumores y contratos del Tabernero (a probar). H26 armas y armaduras de Epic Knights para facciones (a probar). Aldeas de facción vivas (mejoras por tamaño, guarniciones con puestos) y
asedios que no se traban (lado más débil, aldeanos, destrabe) (a probar).
Cada hito termina en algo jugable que Ale prueba antes de seguir.

## Probar

`probar.bat` corre los GameTests sin pantalla y deja todo en `test-log.txt` (busca "required tests passed"). Usan la
carpeta `run-tests/` (no `run/`), así corren aunque el Minecraft de desarrollo esté abierto. El mundo de pruebas tiene
semilla al azar: `h4CastilloSueltoConCapitanYGuarnicion` (busca terreno plano) falla de vez en cuando; volver a correr.
El mundo `run-tests/world` junta datos de corridas anteriores y hace fallar pruebas (rutas, herreros, panel): si fallan
varias que no tienen que ver, moverlo/borrarlo y volver a correr (25/10/2026: limpio, pasan las 206).
Desde una sesión de Claude en la nube: el entorno no llega a Maven de Forge, así que se compila con `javac` contra las
librerías copiadas de `C:\Users\alech\.gradle` (lista en `build/classpath/runGameTestServer_minecraftClasspath.txt`),
se copian los fuentes a `Descargas\warbandcraft` y Ale corre `probar.bat`; el log se lee desde su carpeta.

## Paquete para repartir (1.0.0, pedido de Ale: él + un amigo, launcher común, con servidor)

- `empaquetar.bat` (o `python tools/make_pack.py` después de compilar) → `dist/WarbandCraft-<versión>-juego.zip` y
  `-servidor.zip`. Adentro solo lo nuestro: instaladores (`pack/cliente`, `pack/servidor`, `pack/comun.ps1`), `pack/mods.json`
  y el jar. Los demás mods los bajan los instaladores de Modrinth con su sha1 (Recruits/Workers/Xaero son ARR: no van en el
  zip). Los `.ps1` se meten con BOM + CRLF (PowerShell 5.1). **Mod nuevo o versión nueva = tocar `pack/mods.json`**
  (`side`: both / client / server; `folder`: mods / shaderpacks).
- Juego: `Instalar Warband Craft.bat` → Java 17 propio, Forge con `--installClient` en `.minecraft` (si falta), todo en
  `%APPDATA%\.warbandcraft` y perfil "Warband Craft" en `launcher_profiles.json` (copia `.antes-de-warbandcraft`; el
  launcher tiene que estar cerrado). Versiones viejas de los mods del paquete → `mods-viejos/` (los que agregue uno quedan).
  Una vez (`.teclas-warbandcraft-1`): teclas que chocaban (Oculus K/R, Xaero B→nada, U→I, Cataclysm V→H, Y→N, barras de
  creativo C/X). `options.txt` nuevo lleva `version:3465` (sin eso Minecraft lo descarta). Shaders prendidos (`oculus.properties`).
- Rendimiento (en vez de OptiFine, que no se puede repartir y choca con Forge): Embeddium, Oculus + Complementary
  Reimagined, ModernFix, FerriteCore, Entity Culling, ImmediatelyFast.
- **Mac** (pedido de Ale, sin probar en una Mac): `pack/mac/` (`instalar.sh`, `actualizar.sh`, `Instalar Warband Craft.command`) hace
  lo mismo que `instalar.ps1` con lo que trae macOS (bash 3.2, curl, shasum, JSON con `osascript -l JavaScript`): Java 17 de
  Adoptium (arm64/x64), Forge en `~/Library/Application Support/minecraft`, juego en `.../warbandcraft`, perfil, teclas, John
  Smith, `Jugar Warband Craft.command` en el escritorio. `make_pack.py` → `-mac.zip` (LF + permiso de ejecución en el zip);
  `build.yml` lo sube como `WarbandCraft-Mac.zip`. Cambiar algo de `instalar.ps1` = cambiarlo también en `instalar.sh`.
- Servidor: `Instalar servidor.bat` (Forge `--installServer` en la misma carpeta, mods sin los de solo cliente, EULA con S/N,
  `server.properties` de partida, memoria en `user_jvm_args.txt`) + `Iniciar servidor.bat`.
- Probado (16/10/2026): servidor dedicado arranca y anda; el juego entra a ese servidor con shaders (portablemc sin cuenta).
  Falta probar a mano con Embeddium/Oculus: vista aérea, modo Gestión, mapa M.

## Compilar en Windows sin consola

`compilar.bat` (doble click): baja una JDK 17 propia a `.jdk17/`, corre `gradlew build`, copia el jar a
`%APPDATA%\.minecraft\mods` (o a la carpeta que se le pase como argumento) y copia Recruits 1.15.2,
Xaero's Minimap y Villager Workers 2.0.3 si faltan (ya están en `libs/` porque Gradle los baja en el build).
Los `.bat` tienen que quedar con finales de línea CRLF y llaman a `"%~dp0gradlew.bat"` con ruta completa
(en la terminal de Claude está `NoDefaultCurrentDirectoryInExePath=1`). Con `NOPAUSE=1` no esperan una tecla.
