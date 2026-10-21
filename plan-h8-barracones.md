# H8 — Barracones (plan)

> Estado (2026-10-12): todo hecho junto (pasos 1 a 6) con GameTests (58/58 con las de H1-H6). Falta que
> Ale lo pruebe jugando. H7 (arte) queda salteado por ahora.
> Respuestas de Ale (ronda 1), todas las recomendadas:
> 1. Los quietos en el Campamento se guardan solos cuando te alejás. ✔
> 2. Del barracón se sacan a mano (no salen solos al volver). ✔
> 3. En el campo, el hambre y la deserción las maneja Recruits. ✔
> 4. Guarniciones de la IA como números: H15. ✔
>
> Ronda 2:
> 5. Al sacar un soldado que tenía un puesto, vuelve a ese puesto (sobre todo los guardias del
>    Campamento). Ver "Puestos". Los guardias vuelven solos al acercarte; el resto se sigue sacando a
>    mano (el punto 2 sigue valiendo para ellos).
> 7. Al alejarte se guardan todos los que no te siguen, estén donde estén (guardias, los que caminan y
>    los sueltos). Solo los guardias vuelven solos. Ver sección 4.
> 6. Los Campamentos se pueden nombrar. Ver sección 0.

## Qué pide el documento de diseño

"**Tropas atadas a un Campamento.** Cada soldado pertenece a uno: de ahí come y de ahí sale su
equipo. **Barracones.** Las tropas que no están con vos quedan guardadas como números ('12 infantes,
5 arqueros'). Cada soldado guarda su ficha (nivel, equipo, nombre), así vuelve igual al desplegarse.
**Despliegue.** Los soldados aparecen de verdad cuando hace falta [...]. Comida: sin comida se
desaniman y desertan."

La regla de fondo: **lo que está lejos es un número; solo lo que está cerca es un soldado de verdad.**
Así el mundo no se traba con cientos de soldados.

## Lo que ya resuelve Recruits (no se rehace)

- Hambre y moral de cada soldado (`getHunger/setHunger`, `getMorale/setMoral`, `applyMoralEffects`):
  en el campo comen de su mochila y se desaniman solos.
- "Upkeep": `setUpkeepPos(pos)` manda al soldado a buscar comida (y re-equiparse, `upkeepReequip`) a un
  cofre. Lo usamos apuntando al Campamento.
- Pago (`checkPayment`, `doNoPaymentAction`): queda como está, según la config de Recruits.

O sea: **en el campo** manda Recruits; lo nuevo de H8 es lo que pasa **en el barracón**, donde el
soldado no existe como entidad y alguien tiene que llevar la cuenta.

## Fuera de este hito

- Las guarniciones de la IA siguen siendo entidades (ya persisten y andan). Pasarlas a números va con
  H15 (IA viva).
- Esmeraldas/armamento como números: H10. Acá la comida sale de ítems reales del almacén.
- Capacidad que crece con un cuartel: H12. Acá es una constante.
- Despliegue automático por invasión: H15. Prisioneros al barracón: H9.

## 0. Nombre del Campamento

- El Campamento ya es un `BaseContainerBlockEntity`, que trae nombre propio (`CustomName`). Se usa eso.
- Nombre por defecto al ponerlo: "Campamento 1", "Campamento 2"... (numerados por dueño).
- Se cambia de dos formas:
  - en la pestaña **Barracón**, con el campo de texto de arriba y el botón **Renombrar** (solo el
    dueño; `RenameCampMessage`, hasta 32 letras);
  - como un cofre: si el bloque se renombró en el yunque, al ponerlo toma ese nombre
    (`CampBlock.setPlacedBy`). Al romperlo, el bloque que cae conserva el nombre.
- El nombre también se anota en `CampRegistry`, así se conoce aunque el Campamento no esté cargado.
- Dónde se ve: título de la pantalla del Campamento, pestaña Barracón, `/warband barracon` y los avisos
  del barracón ("Se fueron 3 soldados de Fuerte del Río por hambre"). Pendiente: los avisos de botín
  e impuestos todavía no lo nombran; más adelante, el panel (H11) y las rutas (H13).

## 1. Atar soldados a un Campamento + la ficha

- Marca nueva `warbandcraft:camp` (posición del Campamento como long) en el persistent data del
  soldado. Se pone al armarlo (`ClassAssigner`, el Campamento que pagó el equipo) y al guardarlo.
  Los soldados que ya existen se atan al primer Campamento donde se guarden o se re-armen.
- Al atarlo: `setUpkeepPos(campamento)`, así en el campo vuelve ahí a comer (verificar que el goal de
  upkeep de Recruits acepte nuestro `CampBlockEntity` como contenedor; solo ve los 54 espacios propios,
  no los cofres vinculados).
- **Ficha** = el NBT completo de la entidad (`saveWithoutId` + id del tipo), sin `UUID`. Igual que
  cuando cambiamos de clase: conserva nivel, XP, kills, equipo, mochila, nombre, grupo, clase, nivel
  de kit y versión del kit. Si va montado en su caballo (`warbandcraft:owner`), el caballo se guarda
  en la misma ficha y vuelve con él.
- **Puestos:** Recruits ya guarda en el NBT el puesto (`HoldPosX/Y/Z`), la orden (`FollowState`) y
  la agresividad (`AggroState`), así que la ficha los conserva. Además anotamos dónde estaba parado y
  hacia dónde miraba.
- Datos: `camp/BarracksData` (SavedData por dimensión, como `CampRegistry`): Campamento → lista de
  fichas + estado de comida (último día pagado, días sin comer).
- Guardar = escribir la ficha, `discard()` del soldado (y su caballo) y
  `recountRecruits(...)`. Sacar = crear la entidad desde la ficha, `updateTeam()`,
  `recountRecruits(...)`. Dónde aparece:
  - **con puesto** (tenía orden de quedarse en un lugar): aparece en su puesto, mirando para el mismo
    lado, con la misma orden. Si el lugar quedó tapado o sin piso, aparece en el bloque libre más cercano.
    Si el puesto quedó a más de 64 bloques del Campamento, aparece al lado del Campamento y camina
    hasta él;
  - **sin puesto**: al lado del Campamento, siguiéndote (`setFollowState(1)`).
- Capacidad: `BarracksData.CAPACITY = 40` por Campamento.
- Quién se puede guardar: soldado tuyo, vivo, a ≤16 bloques del Campamento, sin pelear (sin objetivo
  y sin daño en los últimos 10 s).

## 2. Pestaña "Barracón" en el Campamento

Cuarta pestaña: Cofre · Doctrinas · Kits · **Barracón** (solo el dueño, igual que H6).

| Parte | Qué tiene |
| --- | --- |
| Lista | Una fila por clase y nivel, con el nombre propio de la clase: "Guardia de Hierro (Élite) ×4". |
| Botones por fila | **Sacar 1**, **Sacar 5**, **Sacar todos**. Aparecen al lado del Campamento y te siguen (`setFollowState(1)`). |
| Arriba | El nombre del Campamento, "En el barracón: 23 / 40", botón **Guardar a los de cerca (N)** y botón **Guardias a sus puestos (N)** (saca a todos los que tienen puesto). |
| Detalle de fila | Cuántos de esa fila tienen puesto: "×6 (4 de guardia)". "Sacar" saca primero a los que no tienen puesto. |
| Abajo | Comida: "Alcanza para 4 días" o, en rojo, "2 días sin comer: van a empezar a irse". |

Screen lisa con datos por mensaje (como `DoctrineScreen`), botones de texto. Mensajes nuevos:
`OpenBarracksMessage` (servidor→cliente) y `BarracksActionMessage` (cliente→servidor, validado con
`CampTabs.access`). Más `/warband barracon` para ver el más cercano por chat.

## 3. Comida y deserción

Solo para los que están en el barracón (en el campo ya lo maneja Recruits).

- Cada día de Minecraft, cada soldado guardado come **4 puntos de comida** (un pan = 5, un bife
  cocido = 8). Se toma del almacén (Campamento + vinculados, con `CampBlockEntity.take`), primero la
  comida más barata. No cuentan carne podrida, ojo de araña ni nada que envenene.
- Cuenta "perezosa": un Campamento descargado no puede sacar ítems, así que se cobran los días
  atrasados cuando se carga de nuevo (revisión cada 10 s, como el re-armado de H6), hasta 7 días
  hacia atrás.
- Sin comida suficiente: 1er día, aviso. Desde el 2º día seguido sin comer, cada día **deserta el
  10% (mínimo 1)**, empezando por los de menor nivel: los veteranos aguantan más. El desertor
  desaparece. Al volver te llega un mensaje: "Se fueron 3 soldados del barracón por hambre".
- Al sacarlos: si el barracón venía comido, salen con hambre y moral llenas; si no, con la moral baja
  (`setMoral`) y Recruits hace el resto.
- Constantes juntas en `BarracksData` (como `Relations`).

## 4. Guardado automático y vista aérea

- **Automático.** Cada 10 s se revisan tus soldados cargados (`event/BarracksEvents`; no corre en el
  servidor de GameTests, donde los dueños son inventados).
  Si estás a más de 96 bloques de un soldado (o desconectado) y ese soldado no te sigue ni está
  peleando, se guarda en el barracón de su Campamento, esté donde esté. Los que te siguen nunca se
  guardan solos: van con vos. Un soldado que quedó en un chunk descargado se revisa cuando ese chunk
  se vuelve a cargar.
- **Cómo se reconoce a cada uno.** Recruits ya lo dice con la orden (`FollowState`) y el puesto
  (`HoldPos`):

  | Qué estaba haciendo | Cómo se reconoce | Al alejarte | Al volver |
  | --- | --- | --- | --- |
  | Te sigue | Orden "seguirme" | No se guarda | — |
  | **Guardia** | Orden de quedarse **y** parado en su puesto (≤3 bloques) | Se guarda | **Vuelve solo a su puesto** cuando estás a <80 bloques del puesto |
  | Caminando a un lugar | Orden de quedarse pero todavía lejos de ese lugar | Se guarda | Queda adentro; se saca a mano |
  | Suelto / deambulando | Cualquier otra orden | Se guarda | Queda adentro; se saca a mano |

  La orden "mover" de la vista aérea también usa el puesto de Recruits como destino. Por eso un
  soldado que camina se distingue de un guardia por si ya llegó: si llegó y se quedó ahí, es guardia.
- **Guardias lejos del Campamento** (por ejemplo, cuidando una aldea conquistada): misma regla. Se
  guardan en su Campamento, comen de ahí y vuelven a su puesto cuando pasás cerca del puesto, no del
  Campamento.
- Que la distancia para volver (80) sea menor que la de guardar (96) evita que entren y salgan sin
  parar si te quedás justo en el borde.
- **Casos raros.** Un soldado sin Campamento se ata al tuyo más cercano; si no tenés ninguno, queda
  como siempre en Minecraft. Si el barracón está lleno, el soldado queda en el mundo como ahora y te
  llega un aviso.
- Vista aérea: botón **Al barracón** para los seleccionados. Caminan a su Campamento (marca
  `warbandcraft:to_barracks` + `setHoldPos`) y se guardan al llegar (≤4 bloques). Usa el mismo camino
  que las órdenes de H5.

## 5. Campamento roto

Si se rompe un Campamento con gente en el barracón, salen todos (con su caballo): los guardias a su puesto y el resto ahí mismo, quietos,
y se atan al Campamento tuyo más cercano que quede (o a ninguno). Nada se pierde.

## Pruebas (H8GameTests)

1. Guardar y sacar: el soldado vuelve con el mismo nivel, XP, equipo, mochila, nombre, clase y
   versión de kit.
2. Caballero montado: vuelve con su caballo.
3. Capacidad: el 41º no entra.
4. Un día: se descuenta la comida justa del almacén (incluye un cofre vinculado).
5. Hambre: con almacén vacío, día 1 nadie se va; día 2 se va el 10% empezando por el de menor nivel.
6. Días atrasados: Campamento descargado 3 días → al cargarse cobra 3 días.
7. No se guarda un soldado peleando ni uno de otro jugador.
8. Campamento roto: salen todos.
9. Automático: dueño lejos → se guardan el guardia, el que camina y el suelto; el que lo sigue, no.
   Dueño cerca de nuevo → solo el guardia vuelve a su puesto (misma orden, mirando para el mismo
   lado); los demás quedan adentro.
12. Guardia lejos del Campamento: se guarda y vuelve al pasar cerca de su puesto, no del Campamento.
13. Barracón lleno: el soldado queda en el mundo.
10. Puesto tapado: aparece en el bloque libre más cercano.
11. Nombre: el que se pone en el yunque se conserva al poner y al romper el bloque, y `CampRegistry`
    lo conoce con el Campamento descargado.

## Orden de trabajo

1. Nombre del Campamento (prueba 11).
2. Atar + ficha + `BarracksData` + guardar/sacar por comando (pruebas 1-3, 7, 10).
3. Pestaña Barracón.
4. Comida y deserción (pruebas 4-6).
5. Guardado automático y botón de la vista aérea (pruebas 9, 12, 13).
6. Campamento roto (prueba 8).
7. Actualizar CLAUDE.md y el documento de diseño; compilar y que Ale lo pruebe.
