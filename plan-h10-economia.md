# H10 — Economía (plan)

> Estado (2026-10-13): todo hecho (pasos 1 a 5) con GameTests (93/93 con las de H1-H13). Falta que Ale lo
> pruebe jugando. Jar instalado con `compilar.bat` (copió Villager Workers 2.0.3 a la carpeta de mods).
> Respuestas de Ale (ronda 1), todas las recomendadas:
> 1. Esmeraldas y comida siguen siendo ítems en los cofres del Campamento. Armamento es un ítem nuevo. ✔
> 2. Herreros = aldeanos de vanilla (herrero, armero, fabricante de herramientas) cerca del Campamento. ✔
> 3. Aldeas y castillos conquistados guardan lo suyo en un almacén propio (números); se trae por ruta o
>    pasando a buscarlo. ✔
> 4. Sin sueldo por ahora: el pago en el campo sigue como lo tenga configurado Recruits. ✔

## Qué pide el documento de diseño

"Cada aldea, castillo y Campamento tiene un almacén con tres recursos (esmeraldas, comida, armamento).
El cofre del Campamento sirve para poner y sacar cosas a mano; el resto se lleva como números."
Trabajadores de Villager Workers (granjero, pescador, pastor, minero, leñador) y herreros (oficio
nuestro, usa hierro y carbón).

Cómo queda con lo decidido: en el **Campamento** todo sigue siendo ítems de verdad (lo que ya anda:
armar, comida, botín, envíos, doctrinas, rescates). En las **aldeas y castillos**, que no tienen cofres,
el almacén son tres números.

## Fuera de este hito

- Sueldo de las tropas.
- Granero/herrería/mercado como mejoras visibles: H12. Acá las capacidades son constantes.
- Que el enemigo te robe el almacén de una aldea, o que las facciones de la IA junten recursos: H15.
- Envío automático diario de una aldea a un Campamento: queda para el panel (H11).

## 0. Villager Workers

- Dependencia obligatoria nueva: **Villager Workers 2.0.3** (`workers-1.20.1-2.0.3.jar`, Modrinth). Pide
  Recruits ≥1.15.0, anda con nuestro 1.15.2. Se baja a `libs/` en el build como Recruits, va en el
  classpath (lo usamos para crear zonas de almacén) y `compilar.bat` lo copia a la carpeta de mods.
  `mods.toml`: dependencia obligatoria `workers`.
- **Los trabajadores son soldados de Recruits por dentro** (`AbstractWorkerEntity` hereda de
  `BowmanEntity`). Sin filtro, nuestro código los trataría como soldados. `ai/Soldiers.isWorker(entity)`
  (namespace `workers` de su tipo, sin tocar clases de Workers) y se excluyen de:
  barracón (guardar a mano y automático, atar a un Campamento), Batalla (general, botón y comando),
  vista aérea (selección), destrabador, Bastón (clases), re-armado de kits, doctrinas, uniformar y
  rendirse. Viajar con vos sí pueden (te siguen como cualquiera: sirve para mudarlos a otra aldea).
- Cómo se conecta lo que producen: Workers deja lo que junta en los cofres que estén dentro de una
  **zona de almacén** (StorageArea, la marcás con sus herramientas). Cualquier cofre con aire arriba
  cuenta, **incluido el Campamento**. Así que si el Campamento y sus cofres vinculados están dentro de
  una zona de almacén, la producción entra sola al almacén.
- Botón **Zona de trabajadores** en la pestaña Cofre (solo el dueño): crea una zona de almacén de
  Workers que cubre el Campamento y sus cofres vinculados (caja que los encierra +1 bloque, mínimo
  5×3×5), tuya, para todos los oficios. Si ya hay una zona que se superpone, avisa y no crea otra.

## 1. Armamento

- Ítem nuevo `warbandcraft:armament` ("Armamento": un lote de armas y placas). Se apila de a 64.
- Cada pieza de metal tiene un valor en armamento, en `data/warbandcraft/warband_armament/*.json`
  (se recarga con /reload, como los kits):

  | Pieza | Armamento |
  | --- | --- |
  | Lingote de hierro (como material de un kit) | 1 |
  | Espada / hacha de hierro | 2 / 3 |
  | Casco / peto / grebas / botas de hierro | 5 / 8 / 7 / 4 |
  | Cota de malla (cada pieza) | igual que la de hierro |
  | Escudo, ballesta | 1 |
  | Pico de hierro, pala de hierro | 3 / 1 |
  | Bloque de hierro | 9 |

  Diamante, oro, cuero, madera, arcos, flechas, TNT, comida y caballos **no** se reemplazan.
- **Al armar** (`ClassAssigner`): si falta una pieza de la tabla, se paga con armamento. Primero se
  usan las piezas de verdad que haya; el armamento cubre solo lo que falta. El mensaje de "faltan" y el
  costo en el editor de kits lo muestran: "Peto de hierro (o 8 de armamento)".
- El re-armado de H6 y los zapadores (cañón: 3 lingotes + 1 bloque) usan la misma regla.

## 2. Herreros

- Un aldeano de vanilla con oficio **herrero, armero o fabricante de herramientas**, adulto, dentro de
  la jurisdicción del Campamento (24 bloques), cuenta como herrero de ese Campamento. Máx. 4 por
  Campamento. Un aldeano cuenta para un solo Campamento (el más cercano).
- Cada día de juego, cada herrero convierte **1 lingote de hierro + 1 carbón (o carbón vegetal) en 2 de
  armamento**, hasta 8 lingotes por herrero por día. O sea: con herreros el hierro rinde el doble.
- Igual que la comida del barracón: cuenta perezosa. Si el Campamento estuvo descargado, al cargarse
  cobra los días atrasados (máx. 7) con los herreros que haya en ese momento. Último día en el NBT del
  Campamento.
- Constantes en `camp/Forge.java`.

## 3. Lo que se ve en el Campamento

- En la pestaña Cofre, arriba de la grilla, una línea con los totales de todo el almacén (cofre +
  vinculados): "Esmeraldas 34 · Comida 120 (6 días) · Armamento 20 · Herreros 2 · Trabajadores 3".
  Comida en puntos (pan = 5); los días son para los soldados del barracón (4 por soldado y día).
  Se mandan con `ContainerData` del menú (se actualiza solo).
- Botón **Zona de trabajadores** (sección 0).
- `/warband almacen`: lo mismo por chat, del Campamento más cercano.

## 4. Almacén de aldeas y castillos

- Cada aldea/castillo **tuyo** guarda tres números: esmeraldas, comida (en panes) y armamento
  (`VillageData.Village` suma un `Stock`).
- Cada día:

  | | Esmeraldas | Comida (panes) | Armamento |
  | --- | --- | --- | --- |
  | Aldea | impuesto de hoy (3 + 1 por cada 2 aldeanos, máx. +5) | 4 + 1 por cada 2 aldeanos (máx. +6) | 0 |
  | Castillo | igual que la aldea | 4 | 4 |

  Los impuestos **ya no van solos al Campamento**: quedan en la aldea. Aviso diario: "Tus aldeas
  juntaron 12 esmeraldas, 20 panes y 4 de armamento".
- Tope: cada recurso guarda hasta 7 días de producción (lo que sobra se pierde; el granero de H12 lo
  sube).
- Cómo se trae:
  1. **Pasando a buscarlo**: a ≤48 bloques del centro, `/warband retirar` o el botón [Retirar] del aviso
     al entrar a tu aldea. Te lo da en mano (lo que no entra cae al piso).
  2. **Por ruta**: en la pantalla de Rutas (J o poste) parado en tu aldea/castillo aparece su almacén y,
     junto a cada Campamento alcanzable, un botón **Mandar almacén**. Sale como un envío de H13 (ítems:
     esmeraldas, pan, armamento) y llega pasado el tiempo del viaje.
- `/warband aldea` muestra el almacén de la aldea más cercana si es tuya.
- Al saquear o conquistar una aldea de la IA no cambia nada (todavía no tienen almacén: H15).

## Pruebas (H10GameTests)

1. Armamento cubre lo que falta: kit con peto de hierro, almacén sin peto y con 8 de armamento → se
   arma y el armamento queda en 0. Con 7 → no alcanza y el mensaje lo dice.
2. Prefiere piezas de verdad: con un peto y 8 de armamento, usa el peto y el armamento queda.
3. Lo que no está en la tabla (arco, cuero) no se reemplaza.
4. Herrero: aldeano armero a 10 bloques, 5 lingotes + 3 carbones → un día = 6 de armamento, quedan 2
   lingotes. Un aldeano granjero no cuenta; uno a 30 bloques tampoco.
5. Días atrasados del herrero: 3 días → cobra 3 (hasta lo que alcance).
6. Almacén de aldea: un día suma impuesto y comida; castillo suma armamento; el tope se respeta.
7. Retirar: los ítems pasan al jugador y el almacén queda en 0. Lejos (>48) no deja.
8. Mandar almacén por ruta: sale un envío con los ítems y el almacén queda en 0; al llegar entran al
   Campamento.
9. Trabajadores: un `workers:farmer` tuyo no se guarda en el barracón, no entra en la Batalla y no lo
   arma el Bastón.
10. Zona de trabajadores: crea una StorageArea que encierra el Campamento y sus vinculados; una segunda
    vez no crea otra.

## Orden de trabajo

1. Workers en el build + `Soldiers.isWorker` y filtros (prueba 9).
2. Armamento: ítem, tabla, sustitución al armar y en el editor (pruebas 1-3).
3. Herreros (pruebas 4-5).
4. Totales en la pestaña Cofre + `/warband almacen` + botón Zona de trabajadores (prueba 10).
5. Almacén de aldeas: datos, producción diaria, retirar, mandar por ruta (pruebas 6-8).
6. Actualizar CLAUDE.md y el documento de diseño; compilar, correr las pruebas y que Ale lo pruebe.
