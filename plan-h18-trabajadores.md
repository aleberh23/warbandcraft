# H18 — Trabajadores automáticos

Pedido de Ale (2026-10-13): "Algo me está faltando para los farmers, fishers y miners. No trabajan, no generan. En el
modo Gestión, pintar en verde la zona en la cual puedo poner un entorno de trabajo. Y que salga un cartelito desde la
zona que me diga qué le falta: materiales, cama, casa. Que sea más automático y que no tenga que estar tan pendiente.
El minero cada tanto va generando hierro, piedra, carbón y muy cada tanto oro y diamantes."

Orden decidido con Ale: este hito va primero (H18), después Taberna (H19) y Ejércitos (H20).

## Por qué hoy no trabajan (leído del código de Villager Workers 2.0.3)

Un trabajador de Workers trabaja solo si: es de día, está en estado 0/6, **no necesita ir al cofre** (comida, depositar
o buscar algo que le falta) y tiene zona. Lo que le falta lo anota en `neededItems` y lo va a buscar a una zona de
almacén; si no lo encuentra, se queda trabado y avisa **una vez por día**, en inglés, por el chat (fácil de no ver).

- **Minero**: pide pico **y pala**, **16 antorchas** y **16 bloques de relleno** (para tapar agujeros), todo
  obligatorio. Nosotros solo le damos el pico → se queda esperando lo demás. Además, cuando termina de vaciar la zona
  (16×16×8) la marca "hecha" y no vuelve a trabajar nunca más.
- **Granjero**: azada + **una semilla por cada lugar a plantar** (le damos 8; una huerta de 9×9 pide más) y a veces un
  balde de agua. Si falta, se traba.
- **Pescador**: caña (se la damos), pero lo que pesca lo guarda en su mochila y **solo lo deja en un cofre cuando
  junta más de 128 cosas** o se llena → "no genera" por mucho tiempo.
- Todos: lo que juntan va al cofre recién después de 128 ítems; de noche no trabajan; comen de los cofres del almacén;
  sin casa (zona Hogar de Workers) pierden moral y se quejan.

## Plan

1. **Abastecedor automático** (`village/WorkerSupply`, cada 5 s con la aldea cargada): para cada trabajador tuyo de una
   aldea, mira su `neededItems` y su herramienta, y se lo pasa **directo del almacén de la aldea a su mochila** (pico,
   pala, antorchas, relleno, semillas, balde, retoños, trigo, comida). No camina a buscarlo. Si el almacén no lo tiene,
   queda anotado como "falta".
2. **Juntar lo producido**: cada 30 s (o al volver a su cofre) lo que tiene en la mochila que no es herramienta pasa al
   cofre de su zona / al almacén. Bajar el umbral de 128 a 16 (`forcedDeposit` cuando junta 16).
3. **Minero que genera** (pedido de Ale): además de picar, cada 60 s de trabajo saca de la "veta" de su zona:
   piedra (60%), carbón (20%), hierro (14%), oro (5%), diamante (1%); más hondo = mejor tabla. Cuando la zona se vacía,
   en vez de quedar "hecha" baja 8 bloques más (hasta 4 veces) y después sigue generando sin picar.
4. **Zona "hecha" que se reinicia**: granjero/leñador/pescador que quedan en DONE vuelven a empezar al día siguiente.
5. **Cartelito sobre la zona** (vista aérea, modo Gestión): arriba de cada zona, el estado del trabajador en castellano:
   "Trabajando", "Durmiendo", "Le falta: 16 antorchas, pala", "Almacén lleno", "Sin casa", "Con hambre",
   "Zona terminada". El servidor lo calcula (`VillageManagement.Data` suma `status` por zona) y lo manda con el
   REFRESH de cada 2 s. Rojo = trabado, amarillo = aviso, verde = trabajando. También en la pestaña Trabajo, al lado de
   cada trabajador, y un aviso en el Panel (K) si una aldea tiene trabajadores trabados.
6. **Pintar en verde dónde se puede marcar**: al apretar "Zona", se pinta el territorio de la aldea en verde suave; en
   rojo las zonas que ya existen (no se pueden pisar). Mientras arrastrás, el rectángulo se pone rojo si se sale del
   territorio, se pisa con otra zona o es muy chico/grande (ya lo valida `WorkZones.mark`, ahora también en el cliente).
7. **Casa automática para trabajadores**: al asignar un oficio, si hay una cama libre (`Housing`) se le crea la zona
   Hogar de Workers sobre esa casa. Si no, se pide una casa (las casas automáticas de H16 ya lo cubren).
8. **Comida**: si el trabajador tiene hambre, el abastecedor le da comida del almacén (la más barata, como el barracón).

## Hecho (2026-10-13)

- 1, 2, 3, 5, 6, 7, 8 del plan, en `village/WorkerSupply` + modo Gestión. El 4 (reiniciar zonas): solo la mina se
  quedaba "terminada" (las demás vuelven a empezar solas en Workers); ahora no se termina nunca.
- Hecho después: aviso en el Panel (K): "⚠ N trabajadores trabados" en la fila de la aldea (solo si está cargada).
- Pruebas: `H18GameTests` (equipo del minero desde el almacén + cartelito "falta", pescado al almacén, veta y cama).
  Las 142 pruebas pasan.

## Pruebas (GameTests)

- Minero con pico solo + almacén con pala/antorchas/piedra → a los 5 s tiene todo y arranca.
- Minero en zona vacía → genera ítems de la tabla y los deja en el cofre.
- Pescador → a los ~2 min hay pescado en el cofre de la zona.
- Estado: sin pala en el almacén → "Le falta: pala".

## A revisar por Ale

- Tabla del minero y tiempos (60 s, profundidad).
- Si el abastecedor también debería comprar lo que falta con esmeraldas (hoy: solo lo que hay en el almacén).
