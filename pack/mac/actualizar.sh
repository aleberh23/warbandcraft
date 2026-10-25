#!/bin/bash
# Warband Craft: actualiza el juego a la última versión y lo abre (Mac). Lo usa el acceso directo "Jugar Warband Craft".
# Baja el paquete de la release pública "ultima" y corre el mismo instalador de siempre, que solo baja lo que cambió.
# Si no hay internet o GitHub falla, sigue con lo que ya está instalado: nunca te deja sin jugar.
URL='https://github.com/aleberh23/warbandcraft/releases/download/ultima/WarbandCraft-Mac.zip'
TMP="${TMPDIR:-/tmp}/warbandcraft-actualizar"
export NOPAUSE=1

printf '\033[36mWarband Craft: buscando la última versión...\033[0m\n'
rm -rf "$TMP"; mkdir -p "$TMP"
if curl -fsSL -A 'warbandcraft-actualizador/1.0' -o "$TMP/juego.zip" "$URL" && unzip -q -o "$TMP/juego.zip" -d "$TMP"; then
    INST="$(find "$TMP" -name instalar.sh | head -n1)"
    if [ -z "$INST" ] || ! /bin/bash "$INST"; then
        printf '\n\033[33mNo se pudo actualizar. Sigo con la versión que ya tenés instalada.\033[0m\n'
        sleep 5
    fi
else
    printf '\n\033[33mNo se pudo bajar la última versión. Sigo con la que ya tenés instalada.\033[0m\n'
    sleep 5
fi

open -a Minecraft 2>/dev/null || {
    printf '\033[33mNo encontré el launcher de Minecraft: abrilo vos y elegí el perfil '"'"'Warband Craft'"'"'.\033[0m\n'
    sleep 6
}
exit 0
