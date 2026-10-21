# H19 — Taberna, entrenamiento y armar con armamento

Pedido de Ale (2026-10-13):
- "Taberna: sirve para autoreclutar soldados para el cuartel y barracones de esa aldea. Conforme mayor sea el nivel,
  más reclutas y de mejor nivel. También puedo ir y reclutarlos a mano. Para abastecerla, un tabernero debe tener los
  cofres llenos de comida. Que sea dinámico: reclutas que llegan y quedan ahí."
- "Mejora de barracones: permite guardar más soldados y los va entrenando y subiendo de nivel de a poco."
- "Los reclutas se deben poder equipar con solo armamento si así se requiere, aunque debe ser caro (solo el primer
  nivel de equipamiento)."

Decidido con Ale: los reclutas cuestan **solo la comida** (a mano también gratis; lo que cuesta es armarlos). La mejora de
barracón va en aldeas, castillos **y Campamentos comunes**.

## 1. Taberna (mejora de aldea, 3 niveles)

- `Upgrade.TAVERN_1..3` (edificio de H12 con su terreno: barra, mesas, barriles, 2 camas). Solo aldeas (los castillos
  ya tienen guarnición grande; a revisar). Costo 24 / 48 / 96 esmeraldas, constructor 1 / 2 / 3.
- **Tabernero**: nuevo trabajador nuestro (`worker/InnkeeperEntity`, como el Herrero: hereda de `AbstractWorkerEntity`
  de Workers, sin copiar código). Se asigna en el modo Gestión como los demás oficios; trabaja en la taberna.
  La taberna tiene su **cofre de comida** (vinculado al almacén de la aldea, como los cofres de zona). Si el tabernero
  ve el cofre vacío, se lleva comida del almacén (lo hace el abastecedor de H18).
- **Reclutas que llegan** (`village/Tavern`, por día de juego, perezoso como el Herrero): cada recluta que llega
  consume comida del cofre de la taberna (10 puntos de comida). Sin comida o sin tabernero, no llega nadie.

  | Nivel | Llegan por día | Máx. esperando | Nivel del recluta |
  |-------|----------------|----------------|-------------------|
  | 1     | 2              | 4              | 0–1               |
  | 2     | 4              | 8              | 0–3               |
  | 3     | 6              | 12             | 2–5               |

- **Dinámico**: los que llegan **se ven en la taberna** (aldeanos con ropa de recluta, sentados / tomando, sin IA de
  pelea) y se quedan ahí hasta que pasan al cuartel o los reclutás. Si nadie los usa en 3 días, se van.
- **Auto-reclutar** (botón SÍ/NO en Gestión → Tropas, prendido de entrada): cada mañana los que esperan pasan solos
  **primero al cuartel** (hasta su lugar) y después al barracón de la aldea. Entran como reclutas sin clase
  (`recruits:recruit`, sin equipo). Si el almacén tiene lo de la clase "Infante" básico, se arman solos con eso.
- **A mano**: click derecho a un recluta en la taberna = se une a vos (te sigue, como contratar en Recruits, gratis).
  En Gestión → Tropas también hay una fila "En la taberna: N" con →cuartel / →barracón / →a mí.

## 2. Mejora de barracón: Campo de entrenamiento (3 niveles)

- Aldeas/castillos: `Upgrade.TRAINING_1..3` (requiere el Cuartel de H12; edificio con muñecos de paja y blancos).
- Campamento común: botón en la pestaña Barracón, 3 niveles pagados del almacén (16 / 32 / 64 esmeraldas, 1 día de
  espera cada uno). Se guarda en `CampRegistry` por Campamento.
- Efecto por nivel: **+20 / +40 / +60 de lugar** en el barracón, y **entrenamiento**: cada día, los soldados guardados
  ganan experiencia de Recruits (nivel 1: hasta nivel 3 · nivel 2: hasta nivel 6 · nivel 3: hasta nivel 10). Unos
  10–20 de XP por día (un nivel cada ~3–5 días, a revisar). El entrenamiento come el doble de comida ese día.
- La ficha del barracón (`BarracksData.Soldier`) guarda el NBT: se sube la XP ahí sin sacarlos (`Xp`/`Level` del NBT de
  Recruits). En la pestaña Barracón se ve "Entrenando: nivel 2 → 3".

## 3. Armar solo con armamento (caro, solo el primer nivel)

- Hoy: el armamento solo reemplaza **piezas de metal** que faltan. Nuevo: el **nivel 0 (Básico)** de cualquier clase
  se puede pagar **entero con armamento** si falta algo, incluido lo que no es de metal (arco, cuero, flechas, escudo).
- Precio: lo que no es de metal vale el doble de su valor normal (arco 6, pieza de cuero 4, 16 flechas 2, escudo 4…;
  tabla en `warband_armament/`, nuevos valores con `"armament_only": true`).
- Niveles más altos: como hoy (solo metal).
- En el Bastón / editor de kits aparece "Costo en armamento: N" cuando no hay materiales.

## Pruebas (GameTests)

- Taberna nivel 2 + tabernero + cofre con comida → al día siguiente hay 4 reclutas y se descontó la comida; sin comida,
  ninguno.
- Auto-reclutar → pasan al cuartel hasta llenarlo y el resto al barracón.
- Campo de entrenamiento nivel 1: ficha con nivel 0 → después de N días, nivel ≥1; tope nivel 3.
- Armar un Arquero básico con solo armamento en el almacén → sale con arco y cuero; nivel 1 sin metal → no se arma.

## A revisar por Ale

- Números de la tabla de la taberna, la XP por día y los precios en armamento.
- ¿Castillos con taberna? (por ahora no).

## Hecho (2026-10-13, a probar)

Todo lo de arriba menos: el cofre propio de la taberna (la comida sale del almacén de la aldea, que incluye sus cofres
vinculados) y que los reclutas que pasan solos se armen con el kit de Infante (entran sin clase). Ambas cosas a revisar.
