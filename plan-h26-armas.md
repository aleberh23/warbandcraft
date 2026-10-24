# H26 — Armas y armaduras medievales (plan)

Pedido de Ale: armas y armaduras más realistas, y que las facciones las usen.

## Opciones (Forge 1.20.1)

| Mod | Qué trae | A favor | En contra |
|---|---|---|---|
| **Epic Knights** (`magistuarmory`, 10.15, sept. 2026) | ~16 armaduras históricas (gambesón, cota, brigantina, lamelar, de caballero, gótica, Maximiliano, cruzado, oxidadas...), ~10 cascos (armet, bacinete, capelina, barbuta, normando, shishak, yelmo...), 20 armas (pica, lanza de caballería, alabarda, hacha Lochaber, guja, ranseur, maza pesada, martillo, lucerna, mangual, estoque, bastarda, montante, mandoble, messer, espada corta...), 9 escudos (heater, cometa, rodela, redondo, pavés...), arco largo, ballesta pesada, bardas de caballo, cimeras y figuras de estandarte | El más completo y el más histórico. Lo usan con Recruits: el autor de Recruits lo nombra como compatible. Ya estaba previsto en CLAUDE.md | Pide Architectury + Cloth Config. No anda con OptiFine (no lo usamos). ARR: va por los instaladores, como Recruits |
| Spartan Weaponry | Muchas armas (lanzas, alabardas, jabalinas, arrojadizas) | Bien hecho | **Sin armaduras**; más "de juego" que histórico |
| Medieval Munitions | Pocas armas y una armadura de acero | Liviano | Muy chico, pide Pehkui |
| Knight's Armory | Armaduras estilo vanilla | Simple | Solo armaduras, menos variedad |
| Agregados de Epic Knights (Slavic Armory, Dark Ages, Antique Legacy) | Más variedad regional | Sirven para dar identidad a cada facción | Para después: primero la base |

**Recomendación: Epic Knights.** Es el único que cubre armas + armaduras + escudos + caballos con nivel histórico, y
lo revisé por dentro (jar 10.15):
- Las armas heredan de `SwordItem`, los escudos de `ShieldItem`, el arco largo de `BowItem` y la ballesta pesada de
  `CrossbowItem`. Para la IA de Recruits son armas normales: los soldados las usan sin tocar nada.
- Lo especial que hace Epic Knights para el **jugador** (alcance largo de la pica, carga de la lanza) va por paquetes
  del cliente y `Player`: a los soldados no les sirve. Eso lo hacemos nosotros (Fase 3).
- Perforar armadura y la penalidad a dos manos están en el ítem (`dealArmorPiercingDamage`, `hasTwoHandedPenalty`):
  hay que probar si corren para soldados también.

No recomiendo Better Combat ni Epic Fight: son animaciones y combate del jugador; los soldados de Recruits no los usan
y Epic Fight es pesado y choca con mucho.

## Fase 1 — Meter el mod

- `pack/mods.json`: Epic Knights 1.20.1-forge-10.15 (sha1 `8f6019735436fb5a0056916e25f62b363baddafa`),
  Architectury y Cloth Config, `side: both`.
- `gradle.properties` → lista como `dungeon_mods` para que `compilar.bat` los copie. Al Minecraft de desarrollo con
  `runtimeOnly fg.deobf` si arranca (Architectury trae mixins: si rompe como Xaero, queda solo en el paquete).
  No hace falta en el classpath de compilación: usamos sus ítems por id, en los JSON.
- **Que ande sin el mod**: hoy un ítem que no existe apaga el nivel entero. Cambio: una pieza puede ser una lista
  `["magistuarmory:iron_pike", "minecraft:iron_axe"]` y se usa la primera que exista (`KitManager` y facciones).
  Así el servidor de pruebas (`runGameTestServer`) y quien no lo tenga siguen andando.

## Fase 2 — Las facciones se visten

Cada facción con identidad propia y tres escalones (recluta → veterano → élite). Borrador (a ajustar con Ale):

| Facción | Infantería | A distancia | Caballería / élite |
|---|---|---|---|
| **Valdren** (reino de caballeros) | Gambesón + capelina + pica / alabarda; hombres de armas con cota, bacinete, escudo heater y espada bastarda | Arqueros con arco largo y gambesón | Caballeros con armadura de caballero, armet, escudo de cometa, **lanza** y barda; capitán con gótica |
| **Clanes del Norte** | Cota + casco normando + escudo redondo + hacha / messer; élite con hacha Lochaber | Cazadores con gambesón y arco | Guerreros con lamelar; capitán con montante |
| **Sultanato de Arena** | Lamelar + shishak + rodela + espada corta / messer | Arqueros a caballo con shishak y lamelar | Jinetes pesados con casco de cara y lamelar, lanza |
| **Liga de los Pantanos** | Brigantina + capelina + guja / ranseur | **Ballesteros con pavés** (escudo grande a la espalda) y ballesta pesada en la élite | Zapadores con gambesón |
| **Bandidos** | Todo oxidado: barbuta oxidada, cota oxidada, garrote, garrote con púas, horquilla, maza oxidada | Cazador furtivo con arco | Jefe de la banda con espada bastarda oxidada y media armadura oxidada |

- Tus clases (`warband_kits`): **Lancero con pica de verdad** (se termina la nota del hacha), Caballero con lanza,
  y una clase nueva **Alabardero** (o Piquero). Niveles: Básico de hierro/cuero de Epic Knights, Élite con placas.
- El tinte de cuero por facción sigue (gambesón y algunas piezas de EK se pueden teñir: confirmar cuáles).
- Estandartes: usar las figuras de EK (águila, león, dragón, cruz...) en las facciones.

## Fase 3 — Lo "más realista" que hacemos nosotros (para soldados)

Todo con números en `config/warbandcraft-common.toml` (Tuning). Va en `kit/WeaponRules` + eventos que ya tenemos
(`ArmyEvents.onHurt`, donde están los golpes de costado/detrás).

1. **Picas y armas de asta**: atacan desde más lejos (~3,5 bloques en vez de ~2,5) y **×2 de daño contra montados**.
   En una formación quieta (HOLD de H24), los piqueros adelante frenan la carga: el caballo que choca se para.
2. **Carga de lanza**: jinete con lanza y caballo rápido → daño según la velocidad (como la carga de EK, pero en
   el servidor y para soldados). Después de la carga, pasa a la espada (cambio de mano automático si la tiene).
3. **Contundentes contra placas**: maza, martillo, lucerna y mangual ignoran parte de la armadura (si EK ya lo hace
   para soldados, se usa lo suyo; si no, lo nuestro).
4. **Escudos**: el pavés y los escudos grandes paran más flechas; los ballesteros con pavés se cubren al recargar.
   Armas a dos manos = sin escudo (lo controla `PlayerKits.validate`).
5. **Peso**: armadura de placas = un poco más lento a pie y menos aguante del caballo; gambesón/cuero = más rápido.
   Moderado: no queremos que la élite sea inútil.
6. **Batallas (H24)**: `Formation.kindOf` reconoce piqueros como una formación más (van adelante, contra caballería);
   `TeamAI` pone a los piqueros enfrente de la caballería enemiga.

## Fase 4 — Economía

- `warband_armament/epic_knights.json`: valor en armamento de cada pieza de EK, generado con un script (por tipo de
  arma/armadura × material: madera/piedra 0, cobre/estaño/bronce bajo, hierro como vanilla, acero más, etc.).
  Así se pueden pagar con armamento al armar, como hoy con lo de hierro.
- Botín de batalla (`BattleLoot`) y saqueos ya juntan armas y armaduras: con esto, ganar contra Valdren te da placas.
- Herrería / herreros: pueden fabricar piezas de EK además de armamento (a decidir con Ale: ¿solo armamento o también
  piezas?).

## Pruebas

- GameTests (`H26GameTests`): con EK presente, un soldado de cada facción aparece con sus piezas; sin EK, cae a lo
  de vanilla sin apagar niveles; pica pega más a un montado; lanza + caballo rápido pega más que quieto; la IA de
  arquero/ballestero acepta arco largo y ballesta pesada.
- A mano (Ale): ver los modelos con Embeddium/Oculus, que los soldados no se vean raros con armas largas, una
  batalla piqueros contra caballería.

## Preguntas para Ale

1. ¿Epic Knights obligatorio (siempre en el paquete) o opcional? Recomiendo **obligatorio** con la lista de respaldo
   para que no se rompa nada.
2. ¿Querés también los agregados regionales (Slavic Armory, etc.) o primero solo la base?
3. ¿Los herreros fabrican piezas de EK o solo armamento?
4. ¿Peso de la armadura sí o no?
