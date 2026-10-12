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
- Armas medievales para facciones (futuro): Epic Knights (`magistuarmory`), opcional.

## Estructura

```
src/main/java/com/ale/warbandcraft/
  WarbandCraft.java          entrada del mod, registros
  init/                      DeferredRegisters (bloques, ítems, block entities)
  camp/                      Campamento: bloque, cofre de 54 espacios, búsqueda por radio
  kit/                       Clases de soldado: carga de JSON, asignación + conversión de entidad
  item/MarshalBatonItem      Bastón de Mariscal (elige clase / arma soldados)
  event/CommonEvents         listeners del bus de Forge
src/main/resources/data/warbandcraft/warband_kits/*.json   una clase por archivo
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

Entidades de Recruits útiles: `recruit`, `recruit_shieldman`, `bowman`, `crossbowman`, `horseman`, `nomad`,
`scout`, `siege_engineer`, `captain`, `patrol_leader`.

## Detalles de Recruits a tener en cuenta

- `AbstractInventoryEntity.getInventory()`: 15 espacios; 0-5 son el equipo puesto (cabeza, pecho, piernas,
  pies, mano secundaria, mano principal). La mochila empieza en 6.
- `isOwnedBy(Player)` compara UUID con `==` (bug): usar `Objects.equals(getOwnerUUID(), player.getUUID())`.
- Cambiar de clase = crear la entidad nueva copiando NBT sin `UUID`, `discard()` de la vieja, después
  `updateTeam()` y `RecruitEvents.recruitsPlayerUnitManager.recountRecruits(...)`.
- Patrullas NPC: `world/RecruitsPatrolSpawn` (base para las facciones NPC de H2).
- Facciones y diplomacia: `world/RecruitsFactionManager`, `RecruitsDiplomacyManager`.

## Compilar

`./gradlew build` → `build/libs/warbandcraft-<versión>.jar`. Ese jar va a la carpeta `mods` junto con Recruits.
`./gradlew runClient` abre Minecraft de desarrollo con Recruits cargado.

## Hitos

H0 base ✔ · H1 Campamento ✔ (a probar) · H2 facciones NPC · H3 raids · H4 castillos y asedios ·
H5 vista aérea RTS · H6 personalización de tu facción · H7 arte (Blockbench MCP).
Cada hito termina en algo jugable que Ale prueba antes de seguir.

## Compilar en Windows sin consola

`compilar.bat` (doble click): baja una JDK 17 propia a `.jdk17/`, corre `gradlew build`, copia el jar a
`%APPDATA%\.minecraft\mods` (o a la carpeta que se le pase como argumento) y baja Recruits 1.15.2 si falta.
