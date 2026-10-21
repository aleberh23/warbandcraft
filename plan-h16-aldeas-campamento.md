# H16 — Aldeas y castillos como Campamentos grandes (plan)

> Pedido de Ale (2026-10-13): "las aldeas y el castillo tienen que ser como un campamento grande. Deben tener su
> propio cofre, su propia guarnición, y la gestión de las mejoras y rutas". Estando en una aldea, la V (vista aérea)
> muestra un botón **Gestión**: aldeanos libres y trabajadores (asignarles algo), recursos, mejoras, etc., y resalta
> la casa del almacén. Guarnición: aldea hasta 100, castillo hasta 60.
> Detalles decididos por Claude, a revisar al probar.

## 1. El almacén es un Campamento

- El barril del almacén (H10) pasa a ser un **bloque de Campamento** en el mismo lugar (la casa más grande o adentro
  del castillo). Si ya había barril, lo que tenía pasa al Campamento.
- Es tuyo mientras la aldea sea tuya, se llama como la aldea ("Aldea de los Clanes del Norte") y tiene todas las
  pestañas de un Campamento: Cofre, Doctrinas, Kits, Barracón, Rutas, Envíos. Se arma con el Bastón a su alrededor,
  come de su cofre, tiene herreros, cofres vinculados, zona de trabajadores, etc.
- Lo que produce la aldea por día entra a ese cofre (si está descargada, espera como números y entra al cargarse).
- No se puede romper (salvo en creativo; si falta, se vuelve a poner) y las explosiones no lo rompen.
- Si perdés la aldea: el Campamento desaparece; lo que tenía de esmeraldas, comida y armamento queda como almacén de
  la facción (se lo podés volver a robar) y los soldados del barracón se pierden.
- En las rutas la aldea sigue siendo un solo punto: el Campamento de la aldea es la aldea (viajar, envíos, poste).
  Los envíos a una aldea ahora pueden llevar ítems (entran a su cofre) y los soldados van a su barracón.

## 2. Guarnición propia

- Barracón de la aldea: **80 soldados (castillo 40); con el Cuartel de H12, 100 (castillo 60)**.
- Los soldados guardados ahí con puesto salen de guardia cuando pasás cerca (lo de H8); los demás esperan adentro.
- Para las invasiones (H15), todo lo que está en el barracón de la aldea cuenta como defensa.

## 3. Modo Gestión (V)

- En la vista aérea, si estás en una aldea/castillo tuyo (≤ 64 bloques del centro), aparece el botón **Gestión**.
- Panel a la izquierda (el mundo se sigue viendo) con pestañas:
  - **Resumen**: almacén (esmeraldas, comida y días, armamento), producción por día, barracón (soldados/lugar,
    guardias), herreros, trabajadores, invasión en camino.
  - **Aldeanos**: aldeanos libres (sin oficio) y tus trabajadores de Villager Workers con qué hacen y si tienen zona.
    Elegís un aldeano libre (en la lista o con click en el mundo) y un oficio: Granjero, Leñador, Minero, Pescador,
    Ganadero, Cocinero (Villager Workers) o **Herrero** (trabajador nuestro sobre la base de Workers: de día martilla al
    lado de un yunque —el de la Herrería de H12 si la hay— y pasa hierro + carbón del almacén a armamento; máx. 4). Pasa a ser trabajador tuyo (gratis). Al primero se crea sola la zona de almacén alrededor del
    Campamento de la aldea, y al Granjero se le arma solo un campo de cultivo sobre la huerta más cercana de la aldea.
    Los demás oficios necesitan que marques su zona con las herramientas de Workers (la lista avisa "sin zona").
  - **Mejoras**: las de H12 (encargar desde acá).
  - **Rutas**: a dónde llegás desde la aldea y cuánto tarda, y Mandar almacén al Campamento más cercano.
- Resaltado: la casa del almacén con una caja dorada y el cartel "Almacén"; los aldeanos libres con una marca.

## 4. Zonas de trabajo (pestaña Trabajo)

- Cada trabajador tuyo de la aldea con dos botones: **Zona** (arrastrás un rectángulo en el piso en la vista aérea; el
  herrero, un click donde va el yunque; Esc cancela) y **Auto** (la zona sugerida: huerta, árboles, agua, la casa del
  almacén, el yunque; mina y corral se marcan a mano). Al darle un oficio se le busca la zona sola.
- Se crea la zona de Workers que corresponde y el trabajador queda enganchado a ella. Límites: 3 a 32 de lado (mina 16),
  dentro de la aldea, sin pisar otra zona; la de pesca tiene que tener agua.
- Abajo, la lista de zonas de la aldea con **Borrar**; en el mundo se ven con un borde del color del oficio.

## 5. Cofres de zona, territorio y herrería (pedido de Ale, ronda 2)

- Al crear una zona de minero, leñador, granjero, pescador o ganadero se pone un **cofre pegado a la zona** (del lado
  del almacén), con su zona de almacén de Workers, y queda **vinculado al almacén** de la aldea: lo que el trabajador deja
  ahí cuenta como almacén. Al borrar la zona, lo del cofre pasa al almacén. La zona se paga con lo del almacén (herramienta del trabajador, semillas/retoños/trigo para arrancar y el cofre); si
  falta algo no se crea y te dice qué falta.
- El leñador tala y **vuelve a plantar** (retoño del árbol que más hay en la zona).
- El herrero arranca con un yunque al lado del almacén y **se muda solo a la Herrería** cuando la aldea la tiene (si no
  le marcaste el lugar a mano).
- **Territorio**: al conquistar, 5×5 chunks alrededor del centro; se agranda de a un chunk pegado por 10 esmeraldas,
  hasta 50 (el máximo de un claim de Recruits). Si tenés facción de Recruits, es un claim tuyo y se ve en el mapa M. Es la
  jurisdicción del Campamento de la aldea (armar, vincular cofres, guardar soldados) y las zonas de trabajo tienen que
  estar adentro. Se dibuja con borde celeste en Gestión; "Agrandar territorio" + click en un chunk.

## Fuera de esto

- Reclutar aldeanos como soldados desde la aldea.
- Zonas automáticas para leñador/minero/pescador.
