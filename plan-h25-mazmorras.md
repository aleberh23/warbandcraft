# H25 — Mazmorras, túmulos, guaridas y más biomas llanos (decidido con Ale el 2026-10-15; hecho, a probar — detalle en CLAUDE.md)

Idea: que el mundo tenga **dos pisos**. Arriba, llanuras anchas para las batallas, los ejércitos y las aldeas
(Mount & Blade). Abajo y en los bordes, mazmorras con jefes, trampas y tesoros (Skyrim). Lo que encontrás
abajo sirve arriba: armamento, presos para reclutar, opinión con las facciones.

Decidido con Ale:
- Los **3 mods** (YUNG's, Cataclysm, Dungeon Now Loading), si no chocan con Warband Craft.
- **Más biomas llanos** (desiertos, praderas, sabanas...), no un terreno aplastado.
- **Guaridas de bandidos** = una mazmorra más: entrás solo o con los soldados que quieras.
- Los bandidos **asaltan envíos cada tanto**, solo si la guarida queda cerca del camino.
- **Sin gritos** por ahora.
- **Túmulos nórdicos propios**: ningún mod de Forge los trae (ver 4a).

---

## 1. Los mods de mazmorras

Regla para elegir: **Forge nativo** (nada que corra con Sinytra Connector: con Recruits + nuestro mixin es
buscar problemas) y **que genere bajo tierra, en el Nether/End o en el mar**, no en la superficie.

| Mod | Qué trae | Dónde genera |
|---|---|---|
| **YUNG's Better Dungeons** (+ YUNG's API) | Catacumbas, Fortalezas de los No-muertos, Cuevas de Arañas; rehace las mazmorras de vanilla | Todo bajo tierra |
| **L_Ender's Cataclysm** (+ Lionfish API) | Jefes de final de juego y armas legendarias | Casi todo en Nether, End, océano o profundo; alguna en superficie (se espacia con datapack) |
| **Dungeon Now Loading** | Mazmorras tipo Zelda: llaves, ítems para pasar, jefe que se hace más fuerte cada vez | A verificar en la fase 0 |

Descartados: When Dungeons Arise, Moog's Voyager Structures, Dungeons and Taverns completo (estructuras gigantes
arriba); Mebahel's Draugr Invasion y Nordic Tombs (en Forge solo con Sinytra Connector); Nordic Holds (Forge, pero
son casas vikingas en la superficie, sin túmulos, y casi nadie lo usa).

Todos se bajan a `libs/` y `compilar.bat` los copia a `mods`, como Recruits y Xaero. Ninguno es obligatorio.

---

## 2. Que no choque con Warband Craft

1. **Aldeas falsas.** `VillageManager` toma **cualquier campana** (POI `meeting`) como aldea. → Ignorar campanas
   adentro de una estructura que no sea aldea (`structureManager().getStructureWithPieceAt` con `#minecraft:village`,
   como `VillageStorehouse`).
2. **Jefes en la patrulla.** `Quarters.isEnemy` toma todo `Enemy`. → Lo del tag `forge:bosses` no se elige solo;
   si te pega a vos o a tus soldados, sí.
3. **Castillos encima de mazmorras.** `CastleManager.place` aplana: no poner castillo si la caja pisa una estructura.
4. **Campos de batalla.** `Armies.fieldProblem` y `Hosts.findField`: estructura en el rectángulo = no sirve.
5. **Construir.** `Placement.check` (H22): "Pisa una estructura". `RoadPlanner` ya no toca bloques no naturales.
6. **Prueba**: GameTest por punto + partida con los 3 mods. En el classpath de pruebas solo si no rompen el entorno
   dev (si traen mixins que se rompen, como Xaero, van solo a `compilar.bat`).

---

## 3. Más biomas llanos

En Minecraft el bioma sale de unos "ruidos" del mundo. El que importa es la **erosión**: con erosión alta el
terreno es llano y ahí van praderas, desiertos, sabanas, llanuras nevadas; con erosión baja, montañas y picos.

**Cómo**: correr la erosión hacia arriba (un datapack de adentro del mod que pisa
`minecraft:worldgen/density_function/overworld/erosion`: la de vanilla + un corrimiento). Con eso solo:
- Hay **más praderas, desiertos, sabanas y bosques llanos**, y menos montañas, picos y colinas ventosas.
- El terreno de esos biomas es llano de verdad (porque la forma también sale de la erosión). No es un bioma de
  pradera puesto encima de una montaña.
- Montañas siguen habiendo, menos y más separadas (para castillos lindos y mazmorras de montaña).
- Ríos, cuevas, mares y temperatura no se tocan: los desiertos siguen yendo a lo caliente, la nieve a lo frío.

Corrimiento de entrada **+0,25** (a probar con 2-3 mundos y ajustar). No hay mods de terreno en la lista
(Terralith, Tectonic...) con los que pelee.

Ojo: **solo mundos nuevos**. En un mundo ya explorado, lo nuevo se genera con otros biomas y en el borde queda un
corte (cambio de bioma brusco o un escalón). Pasa con cualquier cambio de generación.

---

## 4. Lo nuestro

### 4a. Túmulos nórdicos (nuestras mazmorras, como los castillos de H4)
Ningún mod de Forge los trae, así que los hacemos nosotros, **inspirados** en los de Skyrim (la idea de cómo es
un túmulo, no modelos, texturas ni nombres de Skyrim: eso no se puede copiar). Todo con bloques de vanilla.
- **Arriba, chiquito**: loma con piedras paradas y una puerta (7×7). No ocupa campo de batalla.
- **Abajo, grande**: 2-3 niveles armados por piezas (`StructureTemplate`, como `castle_default.nbt`):
  pasillos, criptas, sala del trono. Más en taiga, nieve y montañas.
- **Draugr**: esqueletos/zombis de vanilla con equipo viejo (cota de malla, hachas), nombre "Draugr", que se
  levantan de los ataúdes cuando pasás. Guerrero, arquero y **Señor del Túmulo** (jefe: mucha vida, invoca 2
  draugr al 50%).
- **Trampas**: placas con dispensadores de flechas, grava que cae, estalactitas, pistones.
- **Puerta de las garras**: tres columnas que giran con click (oso / águila / pez, con estandartes); la combinación
  está en la **Garra de oro** que lleva un Draugr de la sala anterior.
- Al final, cofre del jefe (armamento, esmeraldas, alguna pieza buena).

### 4b. Guaridas de bandidos (una mazmorra más)
- Facción nueva **Bandidos** (JSON como las otras), siempre en guerra con todos. Guaridas: cueva en un bosque,
  campamento en una montaña, ruina en el desierto (entrada chica, el resto abajo o metido en la ladera).
- **Entrás solo o con los soldados que quieras.** Tus soldados usan la malla de H17 para los pasillos.
- Adentro: bandidos (soldados de Recruits sin dueño, equipo pobre), un **jefe de la banda**, y **presos**:
  los liberás y se pueden reclutar (los `Prisoners` de H9). Botín con `BattleLoot`.
- **Asaltos a envíos**: un envío por ruta (H13) cuyo trazado pasa a ≤N bloques de una guarida activa tiene una
  chance chica de ser asaltado (pierde parte de los ítems o soldados; aviso en el chat con dónde está la guarida).
  Limpiar la guarida lo corta. Una guarida limpia vuelve a llenarse después de unos días (a revisar).

### 4c. Rumores y contratos (las misiones de Skyrim)
- El **Tabernero** (H19) vende rumores (5 esmeraldas): marca en el mapa M una mazmorra cercana (nuestra o de
  cualquier mod, con `findNearestMapStructure`).
- **Contratos de las facciones**: una facción en paz con vos pide limpiar una guarida o túmulo en sus tierras
  → esmeraldas + opinión (`Relations`). Vale también para mazmorras de otros mods (jefe muerto o cofre final abierto).

### 4d. Botín que sirve arriba
- Modificador de loot global: **armamento** (H10) y esmeraldas en los cofres de todas las mazmorras.

---

## 5. Fases

| Fase | Qué | Para probar |
|---|---|---|
| 0 | Bajar los 3 mods a `libs/`, mirar dónde generan y si chocan con algo | Nada, es para decidir |
| 1 | **Compatibilidad** (punto 2) + `compilar.bat` copia los mods | Partida con los 3 mods sin aldeas falsas ni soldados contra jefes |
| 2 | **Más biomas llanos** (punto 3) | Mundo nuevo, volar, comparar con uno normal |
| 3 | **Guaridas de bandidos** + asaltos a envíos | Entrar, liberar presos, ver un asalto |
| 4 | **Túmulos + Draugr + puerta de las garras** | Encontrar uno y llegar al jefe |
| 5 | **Rumores, contratos y botín** | Comprar un rumor, cumplir un contrato |
