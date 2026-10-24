"""Arma los paquetes para repartir: dist/WarbandCraft-<versión>-juego.zip, -servidor.zip y -prism.zip (instancia de Prism).

Adentro va solo lo nuestro (instaladores, lista de mods, warbandcraft.jar); los demás mods los bajan los instaladores
de Modrinth (Recruits y Workers son All Rights Reserved: no se pueden meter en el zip).
Uso: python tools/make_pack.py   (después de compilar: usa build/libs/warbandcraft-<versión>.jar)
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


def main():
    v = version()
    jar = ROOT / "build" / "libs" / f"warbandcraft-{v}.jar"
    if not jar.exists():
        raise SystemExit(f"Falta {jar}: compilá primero")
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
