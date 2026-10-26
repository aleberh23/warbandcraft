#!/bin/bash
# Warband Craft: instala el juego para el launcher común de Minecraft en Mac (lo mismo que instalar.ps1 en Windows).
# Lo llama "Instalar Warband Craft.command". Todo va a una carpeta aparte (~/Library/Application Support/warbandcraft)
# con su propio perfil en el launcher: no toca tus otros mods ni tus mundos de siempre. Volver a correrlo = actualizar.
# Solo usa lo que trae macOS (bash 3.2, curl, shasum, tar, osascript para leer y escribir JSON).
AQUI="$(cd "$(dirname "$0")" && pwd)"
SOPORTE="$HOME/Library/Application Support"
JUEGO="${JUEGO:-$SOPORTE/warbandcraft}"
MC="$SOPORTE/minecraft"

paso() { printf '\n\033[36m== %s\033[0m\n' "$1"; }
ok() { printf '   \033[32m%s\033[0m\n' "$1"; }
info() { printf '   %s\n' "$1"; }
fin() {
    if [ "$NOPAUSE" != "1" ]; then echo; read -r -p "Apretá Enter para cerrar" _; fi
    exit "$1"
}
error() {
    printf '\n\033[31mERROR: %s\033[0m\n' "$1"
    echo "Sacale una foto a esta ventana y pasásela a Ale."
    fin 1
}
sha1() { shasum -a 1 "$1" | cut -d' ' -f1; }

# Baja un archivo y revisa que sea el correcto (sha1). Si ya está y coincide, devuelve 1 (no hizo nada).
bajar() {
    local url="$1" destino="$2" sum="$3" intento
    if [ -f "$destino" ] && [ -n "$sum" ] && [ "$(sha1 "$destino")" = "$sum" ]; then return 1; fi
    for intento in 1 2 3; do
        if curl -fsSL -A 'warbandcraft-instalador/1.0' -o "$destino.part" "$url" &&
            { [ -z "$sum" ] || [ "$(sha1 "$destino.part")" = "$sum" ]; }; then
            mv -f "$destino.part" "$destino"
            return 0
        fi
        rm -f "$destino.part"
        sleep 2
    done
    error "No se pudo bajar $url"
}

# JSON con JavaScript de macOS (osascript): no hace falta instalar nada.
lista() {   # una línea por archivo: lado|carpeta|archivo|url|sha1|nombre
    osascript -l JavaScript - "$AQUI/mods.json" <<'JS'
function run(argv) {
    const j = JSON.parse($.NSString.stringWithContentsOfFileEncodingError(argv[0], $.NSUTF8StringEncoding, null).js);
    if (argv[1]) return String(j[argv[1]]);
    return j.files.map(f => [f.side, f.folder, f.file, f.url, f.sha1, f.name].join('|')).join('\n');
}
JS
}
dato() {
    osascript -l JavaScript - "$AQUI/mods.json" "$1" <<'JS'
function run(argv) {
    const j = JSON.parse($.NSString.stringWithContentsOfFileEncodingError(argv[0], $.NSUTF8StringEncoding, null).js);
    const v = argv[1].split('.').reduce((o, k) => o[k], j);
    return String(v);
}
JS
}

# Cambia (o agrega) "clave:valor" en options.txt.
opcion() {
    local f="$JUEGO/options.txt"
    touch "$f"
    awk -v k="$1" -v v="$2" 'BEGIN { hecho = 0 }
        index($0, k ":") == 1 { print k ":" v; hecho = 1; next } { print }
        END { if (!hecho) print k ":" v }' "$f" > "$f.tmp" && mv -f "$f.tmp" "$f"
}

[ -f "$AQUI/mods.json" ] || error "Falta mods.json al lado del instalador: descomprimí el .zip entero."
MINECRAFT="$(dato minecraft)"
FORGE="$(dato forge)"
SHADER="$(dato shader)"
FORGE_ID="$MINECRAFT-forge-$FORGE"
[ -n "$FORGE" ] || error "No pude leer mods.json"

printf '\033[33m================================================\n  Warband Craft - instalador (Mac)\n================================================\033[0m\n'
info "Se instala en: $JUEGO"
mkdir -p "$JUEGO" || error "No pude crear $JUEGO"

paso "Revisando el launcher de Minecraft"
if [ ! -f "$MC/launcher_profiles.json" ]; then
    printf '   \033[31mNo encontré el launcher de Minecraft en %s.\033[0m\n' "$MC"
    echo "   Instalá el launcher oficial (minecraft.net), abrilo una vez con tu cuenta y volvé a correr esto."
    fin 1
fi
while pgrep -f 'Minecraft.app/Contents/MacOS' > /dev/null; do
    printf '   \033[33mEl launcher de Minecraft está abierto: cerralo con Cmd+Q (si no, borra el perfil nuevo al cerrarse).\033[0m\n'
    read -r -p "   Cuando lo cierres, apretá Enter" _
done
ok "Launcher encontrado"

paso "Java 17 (para instalar Forge)"
JAVA_DIR="$JUEGO/java"
JAVA="$JAVA_DIR/Contents/Home/bin/java"
if [ ! -x "$JAVA" ]; then
    case "$(uname -m)" in arm64) ARQ=aarch64 ;; *) ARQ=x64 ;; esac
    info "Bajando Java 17 (solo la primera vez, ~45 MB)..."
    rm -rf "$JAVA_DIR" "$JAVA_DIR.tmp"; mkdir -p "$JAVA_DIR.tmp"
    curl -fsSL -o "$JAVA_DIR.tar.gz" "https://api.adoptium.net/v3/binary/latest/17/ga/mac/$ARQ/jre/hotspot/normal/eclipse" ||
        error "No se pudo bajar Java 17"
    tar -xzf "$JAVA_DIR.tar.gz" -C "$JAVA_DIR.tmp" || error "No se pudo descomprimir Java 17"
    set -- "$JAVA_DIR.tmp"/*
    mv "$1" "$JAVA_DIR" && rm -rf "$JAVA_DIR.tmp" "$JAVA_DIR.tar.gz"
    [ -x "$JAVA" ] || error "No se pudo preparar Java 17"
fi
ok "Listo"

paso "Forge $FORGE"
if [ -f "$MC/versions/$FORGE_ID/$FORGE_ID.json" ]; then
    ok "Ya estaba instalado"
else
    INST="${TMPDIR:-/tmp}/forge-$FORGE_ID-installer.jar"
    bajar "$(dato forge_installer.url)" "$INST" "$(dato forge_installer.sha1)"
    info "Instalando (baja Minecraft $MINECRAFT si hace falta, tarda un poco)..."
    LOG="$JUEGO/forge-instalador.log"
    for intento in 1 2 3; do   # a veces se corta la descarga de Mojang
        (cd "${TMPDIR:-/tmp}" && "$JAVA" -Djava.awt.headless=true -jar "$INST" --installClient "$MC" > "$LOG" 2>&1)
        [ -f "$MC/versions/$FORGE_ID/$FORGE_ID.json" ] && break
        [ "$intento" -lt 3 ] && info "Se cortó la descarga, pruebo de nuevo ($intento/3)..."
    done
    [ -f "$MC/versions/$FORGE_ID/$FORGE_ID.json" ] || error "No se pudo instalar Forge (detalle en $LOG)"
    ok "Instalado"
fi

paso "Mods y shaders"
QUIERO=""
while IFS='|' read -r lado carpeta archivo url sum nombre; do
    [ -z "$lado" ] && continue
    [ "$lado" = both ] || [ "$lado" = client ] || continue
    mkdir -p "$JUEGO/$carpeta"
    [ "$carpeta" = mods ] && QUIERO="$QUIERO$archivo
"
    if bajar "$url" "$JUEGO/$carpeta/$archivo" "$sum"; then ok "$nombre: bajado"; else ok "$nombre: ya estaba"; fi
done <<EOF
$(lista)
EOF
# Warband Craft viene en el paquete
JAR="$(ls "$AQUI"/warbandcraft-*.jar 2>/dev/null | head -n1)"
[ -n "$JAR" ] || error "Falta warbandcraft-*.jar al lado del instalador"
mkdir -p "$JUEGO/mods"
cp -f "$JAR" "$JUEGO/mods/"
QUIERO="$QUIERO$(basename "$JAR")
"
ok "Warband Craft: $(basename "$JAR")"
if [ -f "$AQUI/GUIA-WARBAND-CRAFT.md" ]; then
    cp -f "$AQUI/GUIA-WARBAND-CRAFT.md" "$JUEGO/"
    ok "Guía: $JUEGO/GUIA-WARBAND-CRAFT.md"
fi
# Solo versiones viejas de los mods del paquete (mismo nombre antes del primer guion): los que agregues vos quedan
PREFIJOS="$(printf '%s' "$QUIERO" | sed 's/-.*//' | tr 'A-Z' 'a-z' | sort -u)"
for viejo in "$JUEGO/mods"/*.jar; do
    [ -f "$viejo" ] || continue
    n="$(basename "$viejo")"
    printf '%s' "$QUIERO" | grep -Fxq "$n" && continue
    printf '%s\n' "$PREFIJOS" | grep -Fxq "$(printf '%s' "${n%%-*}" | tr 'A-Z' 'a-z')" || continue
    mkdir -p "$JUEGO/mods-viejos"
    mv -f "$viejo" "$JUEGO/mods-viejos/"
    info "Saqué $n (quedó en mods-viejos)"
done

paso "Configuración inicial"
mkdir -p "$JUEGO/config"
if [ ! -f "$JUEGO/config/oculus.properties" ]; then
    printf 'enableShaders=true\nshaderPack=%s\n' "$SHADER" > "$JUEGO/config/oculus.properties"
    ok "Shaders prendidos (Complementary Reimagined)"
fi
# options.txt: idioma la primera vez y, una sola vez (para no pisar lo que cambies después), las teclas que chocan.
if [ ! -f "$JUEGO/options.txt" ]; then
    opcion version 3465   # 1.20.1: sin esto Minecraft lo lee como un archivo viejo y lo descarta
    opcion lang es_ar; opcion renderDistance 10; opcion simulationDistance 8
fi
if [ ! -f "$JUEGO/.teclas-warbandcraft-1" ]; then
    NADA=key.keyboard.unknown
    opcion key_iris.keybind.toggleShaders $NADA           # K = nuestro Panel (los shaders quedan en la O)
    opcion key_iris.keybind.reload $NADA                  # R = órdenes de Recruits
    opcion key_gui.xaero_new_waypoint $NADA               # B = meterse en una batalla
    opcion key_gui.xaero_waypoints_key key.keyboard.i     # U = pantalla de Recruits
    opcion key_key.cataclysm.boots_ability key.keyboard.h        # V = vista aérea
    opcion key_key.cataclysm.chestplate_ability key.keyboard.n   # Y = opciones del minimapa
    opcion key_key.saveToolbarActivator $NADA             # C y X quedan para Cataclysm
    opcion key_key.loadToolbarActivator $NADA
    echo "Teclas acomodadas por el instalador de Warband Craft" > "$JUEGO/.teclas-warbandcraft-1"
    ok "Idioma y teclas acomodadas"
fi

# Pack de texturas John Smith Legacy. Su web no deja bajarlo por script ni se puede repartir:
# se baja a mano de la página oficial y acá lo buscamos en Descargas, lo copiamos y lo dejamos activado.
paso "Pack de texturas John Smith Legacy"
JSL_SHA1=ce2726b362daec1719e4ebcda7e5b2c87e1025a9
JSL_BYTES=35786496
JSL_NOMBRE=JohnSmithLegacy-1.20.1-v4.zip
JSL_WEB=https://www.johnsmithlegacy.co.uk/
mkdir -p "$JUEGO/resourcepacks"
JSL_DESTINO="$JUEGO/resourcepacks/$JSL_NOMBRE"
buscar_jsl() {
    local z
    for z in "$HOME/Downloads"/*.zip; do
        [ -f "$z" ] || continue
        [ "$(stat -f %z "$z")" = "$JSL_BYTES" ] && [ "$(sha1 "$z")" = "$JSL_SHA1" ] && { echo "$z"; return; }
    done
}
JSL_NUEVO=0
if [ -f "$JSL_DESTINO" ] && [ "$(sha1 "$JSL_DESTINO")" = "$JSL_SHA1" ]; then
    ok "Ya estaba"
else
    ENCONTRADO="$(buscar_jsl)"
    if [ -z "$ENCONTRADO" ] && [ "$NOPAUSE" != "1" ] && [ ! -f "$JUEGO/.pack-texturas-preguntado" ]; then
        printf '   \033[33mSe baja a mano (la web no deja que un programa lo baje). Te abro la página oficial:\033[0m\n'
        echo "   descargá 'John Smith Legacy 1.20.1 v4' (sección Java, Trails & Tales) y dejalo en Descargas."
        echo "   Te espero hasta 5 minutos. Para seguir sin el pack, apretá Enter."
        open "$JSL_WEB"
        echo "Ya se ofreció bajar el pack de texturas" > "$JUEGO/.pack-texturas-preguntado"
        HASTA=$(( $(date +%s) + 300 ))
        while [ -z "$ENCONTRADO" ] && [ "$(date +%s)" -lt "$HASTA" ]; do
            read -r -t 2 _ && break
            ENCONTRADO="$(buscar_jsl)"
        done
    fi
    if [ -n "$ENCONTRADO" ]; then
        cp -f "$ENCONTRADO" "$JSL_DESTINO"
        JSL_NUEVO=1
        ok "Copiado y activado"
    else
        info "Sin el pack por ahora. Cuando lo bajes de $JSL_WEB, volvé a abrir el juego por 'Jugar Warband Craft' y se pone solo."
    fi
fi
if [ "$JSL_NUEVO" = 1 ]; then
    # Activarlo una vez (arriba de los demás); si después lo sacás en Opciones > Paquetes de recursos, no lo volvemos a poner.
    F="$JUEGO/options.txt"
    if grep -q '^resourcePacks:' "$F" 2>/dev/null; then
        grep -q "$JSL_NOMBRE" "$F" || sed -i '' "s#^\(resourcePacks:.*\)\][[:space:]]*\$#\1,\"file/$JSL_NOMBRE\"]#" "$F"
    else
        grep -q '^version:' "$F" 2>/dev/null || opcion version 3465
        opcion resourcePacks "[\"vanilla\",\"file/$JSL_NOMBRE\"]"
    fi
fi

paso "Perfil 'Warband Craft' en el launcher"
GB=$(( $(sysctl -n hw.memsize) / 1073741824 ))
if [ "$GB" -ge 16 ]; then MEM=6; elif [ "$GB" -ge 12 ]; then MEM=5; elif [ "$GB" -ge 8 ]; then MEM=4; else MEM=3; fi
JVM="-Xmx${MEM}G -Xms2G -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M"
cp -f "$MC/launcher_profiles.json" "$MC/launcher_profiles.json.antes-de-warbandcraft"
osascript -l JavaScript - "$MC/launcher_profiles.json" "$FORGE_ID" "$JUEGO" "$JVM" <<'JS' || error "No pude escribir el perfil del launcher"
function run(argv) {
    const [path, version, dir, jvm] = argv;
    const j = JSON.parse($.NSString.stringWithContentsOfFileEncodingError(path, $.NSUTF8StringEncoding, null).js);
    const ahora = new Date().toISOString();
    j.profiles = j.profiles || {};
    // El launcher le cambia la clave a los perfiles que agregamos: buscamos los nuestros por nombre o carpeta,
    // nos quedamos con uno (el mismo, así sigue elegido) y borramos los repetidos de antes.
    const sinBarra = s => String(s || '').replace(/\/+$/, '');
    const nuestros = Object.keys(j.profiles).filter(k => k === 'warbandcraft' ||
        (j.profiles[k] && (j.profiles[k].name === 'Warband Craft' || sinBarra(j.profiles[k].gameDir) === sinBarra(dir))));
    let clave = 'warbandcraft', creado = ahora;
    if (nuestros.length > 0) {
        clave = nuestros[0];
        if (j.profiles[clave].created) creado = j.profiles[clave].created;
        nuestros.forEach(k => delete j.profiles[k]);
    }
    j.profiles[clave] = { name: 'Warband Craft', type: 'custom', icon: 'Furnace', lastVersionId: version,
        gameDir: dir, javaArgs: jvm, created: creado, lastUsed: ahora };
    if (!$(JSON.stringify(j, null, 2)).writeToFileAtomicallyEncodingError(path, true, $.NSUTF8StringEncoding, null))
        throw new Error('no se pudo guardar');
    return 'ok';
}
JS
ok "Perfil listo ($MEM GB de memoria)"

paso "Acceso directo 'Jugar Warband Craft'"
mkdir -p "$JUEGO/actualizador"
cp -f "$AQUI/actualizar.sh" "$JUEGO/actualizador/actualizar.sh"
ATAJO="$HOME/Desktop/Jugar Warband Craft.command"
printf '#!/bin/bash\n# Actualiza Warband Craft y abre el launcher de Minecraft\nexec /bin/bash "%s"\n' "$JUEGO/actualizador/actualizar.sh" > "$ATAJO"
chmod +x "$ATAJO" "$JUEGO/actualizador/actualizar.sh"
ok "En el escritorio: entrá siempre por ahí y se actualiza solo"

printf '\n\033[32m================================================\n  LISTO. Abrí el launcher de Minecraft, elegí el\n  perfil '"'"'Warband Craft'"'"' (abajo a la izquierda)\n  y dale a Jugar.\n================================================\033[0m\n'
fin 0
