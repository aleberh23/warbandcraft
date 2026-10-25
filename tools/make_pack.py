"""Arma los paquetes para repartir: dist/WarbandCraft-<versión>-juego.zip, -servidor.zip y -prism.zip (instancia de Prism).

Adentro va solo lo nuestro (instaladores, lista de mods, warbandcraft.jar); los demás mods los bajan los instaladores
de Modrinth (Recruits y Workers son All Rights Reserved: no se pueden meter en el zip).
Uso: python tools/make_pack.py   (después de compilar: usa build/libs/warbandcraft-<versión>-<commit>.jar)
"""
import json
import pathlib
import re
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
PACK = ROOT / "pack"
CR, LF = chr(13), chr(10)


def version():
    m = re.search(r"^mod_version=(.+)$", (ROOT / "gradle.properties").read_text(encoding="utf-8"), re.M)
    return m.group(1).strip()


def add(z, path, name):
    """Los .ps1 van con BOM y CRLF: el PowerShell 5.1 de Windows los lee mal (acentos) sin BOM."""
    if path.suffix == ".ps1":
        text = path.read_text(encoding="utf-8-sig").replace(CR + LF, LF).replace(LF, CR + LF)
        z.writestr(name, text.encode("utf-8-sig"))
    else:
        z.write(path, name)


GUIDE = "GUIA-WARBAND-CRAFT.md"


def guide(v):
    """La guía para preguntarle a una IA: los libros del salón del tutorial (warband_tutorial/*.txt) en un solo .md."""
    books = sorted((ROOT / "src/main/resources/data/warbandcraft/warband_tutorial").glob("*.txt"))
    out = [f"# Warband Craft {v}: guía completa", "",
           "Mod de Minecraft 1.20.1 (Forge) que convierte el juego en un Mount & Blade. Esta guía tiene todo lo que explican",
           "los libros del salón del tutorial (el que aparece en el spawn de cada mundo nuevo).", "",
           "**Para la IA que lea esto:** respondé las preguntas del jugador con lo que dice esta guía. Los números exactos",
           "(costos, tiempos, chances) se pueden cambiar en `config/warbandcraft-common.toml`, en esta misma carpeta; cada",
           "número tiene un comentario que dice qué es. Si algo no está acá, decilo en vez de inventarlo.", ""]
    for i, p in enumerate(books):
        title, body = p.stem, []
        for line in p.read_text(encoding="utf-8-sig").splitlines():
            if not body and line.startswith("titulo:"):
                title = line[7:].strip()
            elif not body and line.startswith("cartel:"):
                continue
            elif line.strip() == "---":   # página nueva del libro = párrafo nuevo
                body.append("")
            elif line.startswith("## "):
                body += ["", "### " + line[3:].strip(), ""]
            elif body or line.strip():
                body.append(line.rstrip())
        out += ["", f"## {i}. {title}" if i else f"## {title}", ""]
        text = LF.join(body)
        while LF * 3 in text:
            text = text.replace(LF * 3, LF * 2)
        out.append(text.strip())
    return (LF.join(out) + LF).encode("utf-8")


def main():
    v = version()
    # El jar lleva el commit en el nombre (warbandcraft-1.0.0-60d2304.jar): se busca, no se arma el nombre.
    jars = [p for p in (ROOT / "build" / "libs").glob("warbandcraft-*.jar") if not re.search(r"-(sources|slim)\.jar$", p.name)]
    if len(jars) != 1:
        raise SystemExit(f"Tiene que haber un solo warbandcraft-*.jar en build/libs (hay {len(jars)}): compilá de nuevo")
    jar = jars[0]
    mods = json.loads((PACK / "mods.json").read_text(encoding="utf-8"))
    for f in mods["files"]:
        assert f["side"] in ("both", "client", "server"), f
        assert re.fullmatch(r"[0-9a-f]{40}", f["sha1"]), f
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    for kind, folder in (("juego", "cliente"), ("servidor", "servidor")):
        out = dist / f"WarbandCraft-{v}-{kind}.zip"
        top = f"WarbandCraft-{v}-{kind}/"
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
            for p in sorted((PACK / folder).iterdir()):
                add(z, p, top + p.name)
            add(z, PACK / "comun.ps1", top + "comun.ps1")
            z.write(PACK / "mods.json", top + "mods.json")
            z.write(jar, top + jar.name)
            z.writestr(top + GUIDE, guide(v))
        print(f"{out.relative_to(ROOT)}  ({out.stat().st_size // 1024} KB)")

    # Mac: scripts de bash con LF y permiso de ejecución guardado en el zip (la Utilidad de Archivo de macOS lo respeta).
    out = dist / f"WarbandCraft-{v}-mac.zip"
    top = f"WarbandCraft-{v}-mac/"
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for p in sorted((PACK / "mac").iterdir()):
            if p.suffix in (".sh", ".command"):
                info = zipfile.ZipInfo(top + p.name, date_time=(2026, 1, 1, 0, 0, 0))
                info.create_system = 3   # Unix: si no, se ignoran los permisos
                info.external_attr = 0o100755 << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                text = p.read_text(encoding="utf-8-sig").replace(CR + LF, LF)
                z.writestr(info, text.encode("utf-8"))
            else:
                z.write(p, top + p.name)
        z.write(PACK / "mods.json", top + "mods.json")
        z.write(jar, top + jar.name)
        z.writestr(top + GUIDE, guide(v))
    print(f"{out.relative_to(ROOT)}  ({out.stat().st_size // 1024} KB)")

    # Instancia de Prism Launcher: Minecraft + Forge, y antes de abrir corre actualizar.ps1 (baja mods y mod nuestro).
    # Se importa en Prism con Agregar instancia > Importar > link de la release.
    out = dist / f"WarbandCraft-{v}-prism.zip"
    cfg = (CR + LF).join([
        "InstanceType=OneSix",
        "name=Warband Craft",
        "iconKey=default",
        "OverrideCommands=true",
        'PreLaunchCommand=powershell -NoProfile -ExecutionPolicy Bypass -File \\"$INST_DIR/actualizar.ps1\\" -Prism',
        "OverrideMemory=true",
        "MinMemAlloc=2048",
        "MaxMemAlloc=6144",
    ]) + CR + LF
    pack_json = json.dumps({"formatVersion": 1, "components": [
        {"uid": "net.minecraft", "version": mods["minecraft"], "important": True},
        {"uid": "net.minecraftforge", "version": mods["forge"]},
    ]}, indent=2)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("instance.cfg", cfg)
        z.writestr("mmc-pack.json", pack_json)
        add(z, PACK / "cliente" / "actualizar.ps1", "actualizar.ps1")
    print(f"{out.relative_to(ROOT)}  ({out.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
