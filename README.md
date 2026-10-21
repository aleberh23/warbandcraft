# Warband Craft

Mod de Minecraft **1.20.1 (Forge 47.4.10)** que lo convierte en un Mount & Blade: reclutás aldeanos, los armás en tu
Campamento según su clase, y los llevás a asaltar aldeas y castillos de facciones rivales, comandándolos en primera
persona o desde la vista aérea. Es un addon de [Villager Recruits](https://modrinth.com/mod/villager-recruits).

## Jugar en Mac

Guía paso a paso: **[mac/LEEME.md](mac/LEEME.md)**. En corto: instalás Prism Launcher, clonás este repo y hacés doble
click en `mac/Actualizar Warband Craft.command`. Cada vez que haya cambios, doble click de nuevo.

## Jugar en Windows

`empaquetar.bat` arma `dist/WarbandCraft-<versión>-juego.zip` (y el del servidor) con su instalador. Ver `pack/cliente/LEEME.txt`.

## Compilar

```
./gradlew build        # -> build/libs/warbandcraft-<versión>.jar
```
La primera vez baja Minecraft, Forge y los mods de los que depende (a `libs/`, desde Modrinth). Necesita Java 17
(Gradle lo baja solo si no está). En Windows: `compilar.bat`. Pruebas automáticas: `probar.bat` (GameTests).

Cada push a `main` compila en GitHub Actions y deja el jar en la release **ultima** (la usa el instalador de Mac).

## Cosas de terceros

Villager Recruits y Villager Workers (talhanation) son **All Rights Reserved**: no se copian ni se suben a este repo.
Todos los mods del paquete (`pack/mods.json`) se bajan de Modrinth, de sus autores originales.

Para trabajar en el código con Claude: `CLAUDE.md` tiene la guía completa del proyecto.
