# H11 — Panel de control (plan)

> Estado (2026-10-13): hecho, 4 GameTests nuevos (99/99 con el resto). Falta que Ale lo pruebe. Ale se fue a dormir y pidió seguir solo. Las decisiones de abajo las tomó Claude
> siguiendo el documento de diseño; están marcadas para que Ale las revise al probar.

## Qué pide el documento de diseño

"Es una pantalla propia que se abre con una tecla. Muestra todo el reino y desde ahí se dan las órdenes de
logística." Pestañas: Aldeas y castillos (nivel, mejoras, producción, guarnición, impuestos — encargar
mejoras), Campamentos (ubicación, tropas, almacén, días de comida — desplegar o guardar tropas, reclutar
prisioneros), Rutas (qué conectan, terminadas o en obra — construir camino), Envíos (en camino, con hora de
llegada — mandar tropas y recursos).

Niveles y mejoras son H12 y construir caminos es H14: en este hito esas columnas/botones no están.

## Decisiones (a revisar por Ale)

1. Tecla **K** (se puede cambiar en Controles) y `/warband panel`. Se abre desde cualquier lado, en la
   dimensión donde estás.
2. Arriba, un resumen: cuántos Campamentos, aldeas y castillos, soldados guardados, prisioneros, producción
   por día (esmeraldas · comida · armamento) y envíos en camino.
3. **Campamentos**: una fila por Campamento: nombre, distancia, soldados x/40 (guardias), presos, días de comida,
   esmeraldas y armamento. El almacén solo se puede contar con el Campamento cargado: se guarda la última cuenta
   (`CampRegistry`, se actualiza cada 10 s si está cargado y al abrir el panel). Botón **Ver** → detalle:
   - Por clase y nivel: **Sacar 1 / Sacar todos** (solo si el Campamento está cargado y estás a ≤96 bloques,
     la misma distancia del guardado automático; si no, se guardarían solos de nuevo) y **Mandar 1 / todos**
     al destino elegido con ◀ ▶ (envío de H13, desde cualquier lado: solo soldados, los ítems se mandan
     parado en el Campamento).
   - **Guardar a los de cerca** (mismas condiciones que Sacar).
   - Prisioneros: **Reclutar** y **Rescate** desde cualquier lado (no mueven a nadie en el mundo).
4. **Aldeas y castillos**: nombre, distancia, guardias tuyos ahí (fichas de guardia del barracón con puesto a
   ≤48 bloques + soldados cargados de guardia), producción por día, almacén / tope. Botón **Mandar** = todo el
   almacén al Campamento conectado más cercano (en tiempo de viaje). Sin aldeanos cargados la producción cuenta
   lo básico (es lo que de verdad produce si nadie anda cerca).
5. **Rutas**: nombre, qué une, largo, tiempo, activa o no, "sin camino" (H14). Registrar/borrar sigue en J.
6. **Envíos**: de dónde a dónde, qué lleva y cuánto falta (cuenta regresiva en la pantalla).

## Código

- `panel/Panel`: arma los datos y valida/ejecuta las órdenes (lo prueban los GameTests).
- `network/OpenPanelMessage` (servidor → cliente) y `network/PanelActionMessage` (cliente → servidor).
- `client/panel/PanelScreen`, `PanelClientHandler` (tecla K).
- `OpenBarracksMessage.rows/prisonerRows` se reusan para el detalle.

## Pruebas (H11GameTests)

- El panel lista Campamentos, aldeas, rutas y envíos del dueño (y no los de otro).
- Reclutar un prisionero desde lejos funciona; Sacar desde lejos no.
- Mandar soldados desde el panel arma el envío; Mandar el almacén de una aldea elige el Campamento conectado.
- Guardias de una aldea se cuentan.
