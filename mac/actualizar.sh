#!/bin/bash
# Warband Craft para Mac: arma (o actualiza) una instancia de Prism Launcher con Forge, todos los mods y el mod nuestro.
# Uso:  ./mac/actualizar.sh             -> baja la última compilación de GitHub (rápido)
#       ./mac/actualizar.sh --compilar  -> compila el mod acá en la Mac (para probar cambios sin subir)
# Volver a correrlo = actualizar. Los mundos y la configuración de la instancia no se tocan.
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
cd "$RAIZ"
PRISM="${PRISM_DIR:-$HOME/Library/Application Support/PrismLauncher}"
INSTANCIA="$PRISM/instances/WarbandCraft"
JUEGO="$INSTANCIA/minecraft"
TMP="$RAIZ/.mac-tmp"
COMPILAR=0
[[ "${1:-}" == "--compilar" ]] && COMPILAR=1

paso() { printf '\n\033[36m== %s\033[0m\n' "$1"; }
ok()   { printf '   \033[32m%s\033[0m\n' "$1"; }
info() { printf '   %s\n' "$1"; }
falla() { printf '\n\033[31mERROR: %s\033[0m\nCopiá este texto y pasáselo a Claude.\n' "$1"; exit 1; }
sha1() { if command -v shasum >/dev/null; then shasum -a 1 "$1" | cut -d' ' -f1; else sha1sum "$1" | cut -d' ' -f1; fi; }

echo "================================================"
echo "  Warband Craft - instalador para Mac"
echo "================================================"

paso "Prism Launcher"
if [[ ! -d "$PRISM" ]]; then
  info "No encontré Prism Launcher. Instalalo (https://prismlauncher.org, o 'brew install --cask prismlauncher'),"
  info "abrilo una vez, entrá con tu cuenta de Microsoft y volvé a correr esto."
  exit 1
fi
if pgrep -xq prismlauncher 2>/dev/null || pgrep -fq "PrismLauncher.app" 2>/dev/null; then
  info "Prism está abierto: si no ves la instancia al terminar, cerralo y abrilo de nuevo."
fi
ok "Encontrado"

paso "Código del repo"
if git diff --quiet 2>/dev/null && git diff --cached --quiet 2>/dev/null; then
  git pull --ff-only --quiet && ok "Al día ($(git log -1 --pretty='%h %s'))" || info "No pude actualizar (sigo con lo que hay)"
else
  info "Tenés cambios sin guardar en el repo: no hago git pull."
fi

mkdir -p "$TMP"
rm -f "$TMP"/warbandcraft-*.jar
JAR=""

paso "Mod Warband Craft"
if [[ $COMPILAR -eq 0 ]] && command -v gh >/dev/null; then
  if gh release download ultima -p 'warbandcraft-*.jar' -D "$TMP" --clobber 2>/dev/null; then
    JAR="$(ls "$TMP"/warbandcraft-*.jar | head -n1)"
    ok "Bajé la última compilación de GitHub: $(basename "$JAR")"
  else
    info "No pude bajar la compilación de GitHub (¿'gh auth login'?). Compilo acá."
  fi
fi
if [[ -z "$JAR" ]]; then
  JDK="$RAIZ/.jdk17-mac"
  if [[ ! -x "$JDK/Contents/Home/bin/java" ]]; then
    info "Bajando Java 17 para compilar (solo la primera vez, ~190 MB)..."
    case "$(uname -m)" in arm64) ARQ=aarch64 ;; *) ARQ=x64 ;; esac
    curl -fL# "https://api.adoptium.net/v3/binary/latest/17/ga/mac/$ARQ/jdk/hotspot/normal/eclipse" -o "$TMP/jdk17.tar.gz" \
      || falla "no se pudo bajar Java 17"
    rm -rf "$TMP/jdk"; mkdir -p "$TMP/jdk"
    tar -xzf "$TMP/jdk17.tar.gz" -C "$TMP/jdk"
    rm -rf "$JDK"; mv "$TMP/jdk/"* "$JDK"; rm -rf "$TMP/jdk" "$TMP/jdk17.tar.gz"
  fi
  export JAVA_HOME="$JDK/Contents/Home"
  info "Compilando (la primera vez tarda varios minutos: baja Minecraft y Forge). Detalle en build-log.txt"
  ./gradlew build --no-daemon > build-log.txt 2>&1 || { tail -n 30 build-log.txt; falla "no compiló (ver build-log.txt)"; }
  JAR="$(ls build/libs/warbandcraft-*.jar | grep -v -e sources -e slim | head -n1)"
  ok "Compilado: $(basename "$JAR")"
fi

paso "Instancia 'Warband Craft' en Prism"
mkdir -p "$JUEGO/mods" "$JUEGO/config" "$JUEGO/shaderpacks"
MC=$(grep -o '"minecraft": *"[^"]*"' pack/mods.json | sed 's/.*"\([^"]*\)"$/\1/')
FORGE=$(grep -o '"forge": *"[^"]*"' pack/mods.json | sed 's/.*"\([^"]*\)"$/\1/')
cat > "$INSTANCIA/mmc-pack.json" <<JSON
{
    "components": [
        { "uid": "net.minecraft", "version": "$MC", "important": true },
        { "uid": "net.minecraftforge", "version": "$FORGE" }
    ],
    "formatVersion": 1
}
JSON
if [[ ! -f "$INSTANCIA/instance.cfg" ]]; then
  GB=$(( $(sysctl -n hw.memsize 2>/dev/null || echo 8589934592) / 1073741824 ))
  if (( GB >= 16 )); then MEM=6; elif (( GB >= 12 )); then MEM=5; elif (( GB >= 8 )); then MEM=4; else MEM=3; fi
  cat > "$INSTANCIA/instance.cfg" <<CFG
InstanceType=OneSix
name=Warband Craft
iconKey=default
OverrideMemory=true
MinMemAlloc=2048
MaxMemAlloc=$(( MEM * 1024 ))
OverrideJavaArgs=true
JvmArgs=-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M
CFG
  ok "Creada (Minecraft $MC, Forge $FORGE, $MEM GB de memoria)"
else
  ok "Ya estaba (Minecraft $MC, Forge $FORGE)"
fi

paso "Mods y shaders"
# Lista de pack/mods.json sin los de solo servidor: carpeta<TAB>archivo<TAB>url<TAB>sha1
lista() {
  if command -v python3 >/dev/null && python3 -c 'pass' 2>/dev/null; then
    python3 -c 'import json,sys
for f in json.load(open(sys.argv[1]))["files"]:
    if f["side"]!="server": print("\t".join([f["folder"],f["file"],f["url"],f["sha1"]]))' pack/mods.json
  else
    osascript -l JavaScript -e 'function run(a){var t=$.NSString.stringWithContentsOfFileEncodingError(a[0],4,null).js;
      return JSON.parse(t).files.filter(function(f){return f.side!="server"}).map(function(f){return [f.folder,f.file,f.url,f.sha1].join("\t")}).join("\n")}' \
      "$RAIZ/pack/mods.json"
  fi
}
QUIERO=$'\n'
while IFS=$'\t' read -r carpeta archivo url hash; do
  [[ -z "$archivo" ]] && continue
  destino="$JUEGO/$carpeta/$archivo"
  mkdir -p "$JUEGO/$carpeta"
  [[ "$carpeta" == "mods" ]] && QUIERO+="$archivo"$'\n'
  if [[ -f "$destino" && "$(sha1 "$destino")" == "$hash" ]]; then ok "$archivo: ya estaba"; continue; fi
  for intento in 1 2 3; do
    if curl -fsSL -A 'warbandcraft-instalador/1.0' "$url" -o "$destino.part" && [[ "$(sha1 "$destino.part")" == "$hash" ]]; then
      mv "$destino.part" "$destino"; ok "$archivo: bajado"; break
    fi
    rm -f "$destino.part"
    [[ $intento -eq 3 ]] && falla "no se pudo bajar $url"
    sleep 2
  done
done < <(lista)

rm -f "$JUEGO/mods"/warbandcraft-*.jar
cp "$JAR" "$JUEGO/mods/"
QUIERO+="$(basename "$JAR")"$'\n'
ok "Warband Craft: $(basename "$JAR")"

# Versiones viejas de los mods del paquete (mismo nombre antes del primer guion) van a mods-viejos/; los que agregues vos quedan
PREFIJOS=$(printf '%s' "$QUIERO" | sed '/^$/d' | cut -d- -f1 | tr '[:upper:]' '[:lower:]' | sort -u)
for viejo in "$JUEGO/mods"/*.jar; do
  [[ -e "$viejo" ]] || continue
  nombre="$(basename "$viejo")"
  grep -qxF "$nombre" <<< "$QUIERO" && continue
  pre="$(printf '%s' "${nombre%%-*}" | tr '[:upper:]' '[:lower:]')"
  grep -qxF "$pre" <<< "$PREFIJOS" || continue
  mkdir -p "$JUEGO/mods-viejos"; mv "$viejo" "$JUEGO/mods-viejos/"
  info "Saqué $nombre (quedó en mods-viejos)"
done

paso "Configuración inicial"
SHADER=$(grep -o '"shader": *"[^"]*"' pack/mods.json | sed 's/.*"\([^"]*\)"$/\1/')
if [[ ! -f "$JUEGO/config/oculus.properties" ]]; then
  printf 'enableShaders=true\nshaderPack=%s\n' "$SHADER" > "$JUEGO/config/oculus.properties"
  ok "Shaders prendidos (Complementary Reimagined). Si anda lento, apagalos con la tecla O."
fi
OPC="$JUEGO/options.txt"
MARCA="$JUEGO/.teclas-warbandcraft-1"
poner() { # clave valor: reemplaza o agrega en options.txt
  touch "$OPC"
  if grep -q "^$1:" "$OPC"; then
    awk -v k="$1" -v v="$2" -F: 'BEGIN{OFS=":"} $1==k{print k":"v; next} {print}' "$OPC" > "$OPC.tmp" && mv "$OPC.tmp" "$OPC"
  else
    printf '%s:%s\n' "$1" "$2" >> "$OPC"
  fi
}
if [[ ! -f "$OPC" ]]; then
  poner version 3465; poner lang es_ar; poner renderDistance 10; poner simulationDistance 8
fi
if [[ ! -f "$MARCA" ]]; then
  NADA=key.keyboard.unknown
  poner key_iris.keybind.toggleShaders "$NADA"          # K = nuestro Panel
  poner key_iris.keybind.reload "$NADA"                 # R = órdenes de Recruits
  poner key_gui.xaero_new_waypoint "$NADA"              # B = meterse en una batalla
  poner key_gui.xaero_waypoints_key key.keyboard.i      # U = pantalla de Recruits
  poner key_key.cataclysm.boots_ability key.keyboard.h  # V = vista aérea
  poner key_key.cataclysm.chestplate_ability key.keyboard.n
  poner key_key.saveToolbarActivator "$NADA"            # C y X quedan para Cataclysm
  poner key_key.loadToolbarActivator "$NADA"
  echo "Teclas acomodadas por el instalador de Warband Craft" > "$MARCA"
  ok "Idioma y teclas acomodadas"
fi

rm -rf "$TMP"
echo
printf '\033[32m================================================\n'
printf '  LISTO. Abrí Prism Launcher, elegí la instancia\n'
printf '  "Warband Craft" y dale a Iniciar.\n'
printf '================================================\033[0m\n'
