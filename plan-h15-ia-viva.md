# H15 — IA viva (plan)

> Estado (2026-10-13): H15a hecho, 3 GameTests nuevos (110/110 con el resto). Falta que Ale lo pruebe. Ale dormía y pidió seguir solo. H15 es muy grande: Claude hizo la primera parte (**H15a**)
> y dejó el resto para **H15b**. Decisiones de Claude siguiendo el documento de diseño, a revisar al probar.

## Qué pide el documento de diseño

Facciones que juegan con las mismas reglas pero simuladas con números (cada aldea y castillo produce, junta y
engorda su guarnición solo). Robo de recursos (al saquear o conquistar, su almacén va a tu Campamento más cercano).
Patrullas con colores de la facción. Invasiones: una facción en guerra con vos junta un ejército y ataca una de tus
aldeas; aviso con cuenta regresiva; si llegás a tiempo se pelea de verdad (a menos de ~100 bloques), si no se
autoresuelve (cantidad, clase, nivel, defensas como muros y torres, algo de azar; aviso con resultado y bajas); la IA
te puede conquistar. Se guarda todo. Guerras entre facciones de la IA, ejércitos en el mapa, emboscadas en los viajes.

## H15a (hecho en esta tanda)

1. **Tesoro y leva de cada facción** (números): cada día, cada aldea/castillo de facción (sin dueño, no saqueado)
   junta en su almacén lo mismo que una tuya (impuesto, comida; castillos armamento), con el mismo tope. Cada
   facción suma **leva**: 2 soldados por día por cada aldea/castillo suyo, hasta 40.
2. **Robo de recursos**: saquear o conquistar se lleva también ese almacén al Campamento más cercano (aviso aparte).
3. **Invasiones**: cada día, cada facción **en guerra** con vos y con leva ≥ 6 tiene 35% de mandar un ejército
   contra tu aldea/castillo más cercano a sus tierras. Tamaño = leva disponible, hasta 4 + 2 por asentamiento suyo,
   máximo 16 (se descuenta de la leva). Una invasión a la vez por aldea.
   - **Aviso** en el chat con cuenta regresiva de **5 minutos**; también al entrar al juego y en el Panel (aldea en rojo).
   - **Llegada con vos a ≤100 bloques** (aldea cargada): aparece el ejército a 30 bloques, del lado de sus tierras,
     y entra a la aldea. Pelean contra vos y tus soldados (están en guerra). Si caen todos: ganaste. Si no queda
     nadie tuyo a ≤64 bloques (ni vos) durante 30 s: la aldea es de ellos y los invasores quedan de guarnición.
     Si te vas lejos en medio de la pelea, lo que queda se autoresuelve.
   - **Llegada sin vos**: autoresolución. Ataque = soldados × 1,2 × azar (0,8-1,2). Defensa = (milicia 2, castillo 4)
     + guardias tuyos ahí (fichas de guardia con puesto a ≤48: 1 + 0,15 por nivel) × defensas (castillo ×1,5,
     empalizada ×1,25, muralla ×1,5, torres +0,25) × azar. Gana el mayor. Si ganás, caen guardias en proporción
     (la mitad de ataque/defensa); si perdés, caen todos los guardias de esa aldea y la aldea pasa a la facción.
   - Al perder una aldea: vuelve a ser de la facción (su almacén se vacía; muros y edificios quedan).
4. Prueba: `/warband prueba invasion [cantidad]` pone en guerra a la facción más cercana y manda un ejército contra
   tu aldea más cercana con 20 s de aviso.

## H15b (para después)

Patrullas con la facción (Recruits), guerras entre facciones de la IA, ejércitos visibles en el mapa, emboscadas en
los viajes, que la IA reconquiste aldeas que perdiste aunque no estés en guerra.

## Código

- `invasion/InvasionData` (SavedData por dimensión: invasiones en curso + leva por facción).
- `invasion/Invasions` (día nuevo: tesoro, leva, lanzar; cada segundo: llegadas, peleas, autoresolución; robo).
- `event/InvasionEvents`, panel (aldea con invasión), comando de prueba.

## Pruebas (H15GameTests)

- La leva crece con los asentamientos y tiene tope; el almacén de una aldea de facción crece y se roba entero.
- Autoresolución: muchos guardias + muralla ganan (con bajas); sin guardias se pierde la aldea (pasa a la facción).
- Pelea real: el ejército aparece marcado y con la aldea como destino; si caen todos, se gana; sin defensores 30 s, se pierde.

## H15b (hecho después, misma noche)

> Estado (2026-10-13): hecho, 4 GameTests nuevos (114/114 con el resto). Falta que Ale lo pruebe.

1. **Ejércitos en el mapa**: las invasiones en marcha y las campañas entre facciones se dibujan en el mapa de
   Recruits (M) como un rombo que avanza (rojo si viene contra vos o es de una facción en guerra con vos, naranja si
   no) con una línea de puntos hasta su destino y "Ejército de X (n)". La posición sale del tiempo: entre la salida y
   la llegada, en línea recta.
2. **Guerras entre facciones de la IA**: cada día, dos facciones vecinas (asentamientos a ≤800 bloques) tienen 8% de
   declararse la guerra (dura 4-8 días). En guerra, 40% por día de que una mande su leva (hasta 16) contra el
   asentamiento enemigo más cercano; marchan a 2 bloques/s (2-10 min). Al llegar se autoresuelve contra la guarnición
   promedio de la aldea (castillo: 22) por sus defensas. Si gana, la aldea pasa a la facción atacante: la guarnición
   vieja se va y entran los que quedaron del ejército (ya, o cuando se cargue). Avisos a todos los jugadores de la
   dimensión ("Noticias: ..."). Si un jugador está atacando esa aldea, esperan. Sin pelea real entre facciones
   (pelean con números aunque estés cerca).
3. **Emboscadas**: mientras viajás por una ruta, cada segundo se calcula dónde vas (en línea recta) y dónde está cada
   ejército enemigo en marcha; si quedan a ≤48 bloques, el viaje se corta, aparecés ahí con tu escolta y el ejército
   aparece a 14 bloques (deja de marchar). Si nadie se le acerca (128 bloques), vuelve a su leva.
4. **Patrullas**: cada minuto, con un jugador a ≤160 bloques de una aldea/castillo de facción sin patrulla, 30% de
   que salga una de 3-4 soldados. Recorre 4 puntos a 56 bloques y vuelve a casa (y desaparece). Sin jugadores cerca,
   desaparece; no se guardan. Te atacan si su facción está en guerra con vos. Pegarles no empieza un ataque a la
   aldea, pero baja 5 de opinión (una vez por soldado).
5. Prueba: `/warband prueba guerra` (otra facción manda 10 soldados contra la aldea de facción más cercana, llegan
   en 1 minuto; se ve en el mapa).


## Decidido con Ale (después de H15b) — a probar

- **Batallas entre facciones cerca tuyo**: lejos se resuelven con números; con un jugador a ≤100 bloques cuando llega el
  ejército (y la aldea cargada) la batalla se pelea de verdad (`AiWars.startBattle`, marca `warbandcraft:ai_attack`).
  Te podés meter: pegarle a un bando = −5 de opinión con ese bando (una vez por soldado) y no empieza un ataque tuyo;
  si gana el bando que ayudaste, +10 con él. Sin atacantes = aguantó; sin defensores = la aldea cambia de facción y los
  atacantes quedan de guarnición. Si la aldea se descarga en medio, lo que quedaba se resuelve con números.
- **Reconquista**: una facción en paz con vos puede querer recuperar una aldea/castillo que le sacaste (15% por día, con
  leva suficiente): te declara la guerra (opinión a −50) y manda el ejército contra esa aldea. `/warband prueba reconquista`.
- **Emboscadas por el camino real**: el viaje sigue los puntos de las rutas que usa (`Travel.Path`), en el sentido que
  toca, con el tiempo de cada ruta repartido según el largo de cada tramo.
