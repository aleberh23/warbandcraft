# H12 — Mejoras visibles (plan)

> Estado (2026-10-13): hecho, 4 GameTests nuevos (103/103 con el resto). Falta que Ale lo pruebe. Ale dormía y pidió seguir solo. Decisiones de Claude siguiendo el documento de diseño,
> a revisar al probar.

## Qué pide el documento de diseño

"Las mejoras se ven en el mundo: aparecen muros, torres y edificios. Las levanta un constructor ficticio, que
camina por la obra mientras los bloques aparecen de a poco. Es rápido: una mejora tarda uno o dos minutos."
Niveles aldea → pueblo → ciudad (cuestan recursos y dan más). Mejoras sueltas: empalizada y después muralla,
torres, cuartel (más tropas en el barracón), herrería (armamento), mercado (más esmeraldas), granero (más comida
guardada). Castillos: las mismas reglas con mejoras propias. Regla de MineColonies: el constructor solo mejora
hasta su propio nivel; para mejoras grandes, primero mejorar la casa del constructor.

## Decisiones (a revisar por Ale)

1. Se encargan desde el **Panel (K) → Aldeas y castillos → Mejoras**. Una obra a la vez por aldea.
2. Se pagan en **esmeraldas**: primero del almacén de la aldea, lo que falte de tu inventario.
3. Cada obra = lista de bloques que se ponen de a poco en 60-120 s (8 bloques/s). Si la aldea no está cargada la
   obra avanza igual en el tiempo y lo que falta aparece de golpe al volver. El constructor es un aldeano albañil
   quieto (sin IA, invulnerable, no comercia) que se va moviendo hasta cada bloque; polvo y sonido al poner.
   Nunca pisa cofres, camas, campanas ni nada con datos (bloques con block entity).
4. Mejoras (costo en esmeraldas · nivel de constructor · requisito):

| Mejora | Costo | Constr. | Requiere | Efecto | Se ve |
|---|---|---|---|---|---|
| Taller del constructor 1/2/3 | 16/32/64 | — / 1 / 2 | — | sube el nivel del constructor | casita 5×5 / 7×7 / 9×9 de 2 pisos |
| Pueblo | 48 | 2 | Empalizada | esmeraldas y comida ×1,5 | plaza de piedra y faroles alrededor de la campana |
| Ciudad | 96 | 3 | Pueblo + Muralla | esmeraldas y comida ×2 | plaza grande de piedra pulida y faroles |
| Empalizada | 24 | 1 | — | (defensa visual) | anillo de troncos de 3 de alto; huecos en caminos y casas |
| Muralla | 64 | 2 | Empalizada | (defensa visual) | el anillo pasa a ladrillo de piedra de 4 con almenas |
| Torres | 48 | 2 | Empalizada | torres de vigía (visual por ahora) | 4 torres de piedra en el anillo, con escalera |
| Cuartel | 32 | 1 | — | +20 lugares en barracones a ≤64 bloques (castillo: +40) | cuartel con camas |
| Herrería | 32 | 2 | — | +3 armamento por día | fragua abierta: alto horno, yunque, caldero de lava |
| Mercado | 32 | 2 | — | +4 esmeraldas por día | tres puestos con toldos de lana |
| Granero | 24 | 1 | — | +2 comida por día y guarda el doble de comida | granero con fardos de heno |

   Castillos: taller, cuartel ("grande", +40), herrería, mercado y granero. Muros reforzados y torres de castillo
   quedan afuera (el castillo ya trae murallas). Sin niveles pueblo/ciudad.
5. El anillo se acomoda a cada aldea: radio = donde están las camas y oficios (POIs) + 6, entre 16 y 56 bloques.
   Cada columna sigue el piso; donde hay camino (dirt_path) queda una puerta de 3, y donde hay una casa o agua,
   un hueco. Los edificios buscan un lugar casi plano (desnivel ≤2) adentro del anillo, sin caminos ni casas; si
   no hay, afuera. Se nivela el piso y se tala lo que haya encima.
6. "Más trabajadores / más guarnición" por nivel: fuera de este hito (no hay trabajadores ni tope de guarnición
   en aldeas tuyas todavía).

## Código

- `upgrade/Upgrade` (enum con costos y requisitos), `upgrade/TownData` (SavedData por dimensión: nivel,
  constructor, hechas, sitios, obra en curso con paleta), `upgrade/Blueprints` (bloques de cada obra, anillo,
  sitios), `upgrade/Upgrades` (encargar, avanzar obras, constructor, efectos), `event/UpgradeEvents`.
- Efectos: `VillageStock.daily/cap`, `Barracks.capacity(level, camp)` reemplaza a `BarracksData.CAPACITY`.
- Panel: botón Mejoras en cada aldea → detalle con estado y Construir.

## Pruebas (H12GameTests)

- El anillo deja puertas en los caminos y huecos en las casas, y sigue el piso.
- Una obra avanza con el tiempo, termina, aplica la mejora y saca al constructor.
- Requisitos y costo (sin empalizada no hay muralla; constructor bajo; paga del almacén y del inventario).
- Efectos: producción con Pueblo + Mercado; capacidad del barracón con Cuartel.
