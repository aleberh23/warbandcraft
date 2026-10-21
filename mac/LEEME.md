# Warband Craft en Mac

Sirve para Mac con chip Apple (M1, M2, M3, M4) o Intel. Hay que hacerlo **una sola vez**; después, para actualizar,
es un doble click.

## Lo que necesitás

- Minecraft **Java Edition** comprado, con tu cuenta de Microsoft.
- Unos 3 GB libres.

## 1. Instalar Prism Launcher

Es un launcher gratis que maneja instancias con mods y baja Java solo.

1. Bajalo de **https://prismlauncher.org/download/mac/** y arrastralo a Aplicaciones.
2. Abrilo. Si macOS dice que no se puede abrir: Ajustes del Sistema → Privacidad y seguridad → "Abrir igualmente".
3. En el asistente del principio dejá todo como viene (que **baje Java automáticamente**).
4. Arriba a la derecha → **Cuentas → Agregar Microsoft** y entrá con tu cuenta.
5. Cerralo.

## 2. Bajar el repo

El repo es privado, así que la Mac tiene que entrar a GitHub con tu cuenta. Lo más fácil es con la herramienta `gh`.
Abrí la app **Terminal** (Cmd + Espacio, "Terminal") y pegá esto, una línea por vez:

```bash
# Si no tenés Homebrew (si "brew" no existe), instalalo primero:
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

brew install gh git
gh auth login          # elegí GitHub.com → HTTPS → Login with a web browser, y seguí los pasos
gh repo clone aleberh23/warbandcraft ~/warbandcraft
```

Queda en tu carpeta personal, en `warbandcraft`.

## 3. Instalar el juego

En Finder abrí la carpeta `warbandcraft` → `mac` y hacé **doble click en `Actualizar Warband Craft.command`**.

- La primera vez macOS puede decir que no se puede abrir porque no es de un desarrollador identificado: click derecho
  sobre el archivo → **Abrir** → **Abrir**. Después ya no pregunta.
- Se abre una Terminal que baja Forge, los ~25 mods, los shaders y la última versión del mod. Tarda unos minutos.
- Cuando dice **LISTO**, abrí Prism Launcher: aparece la instancia **Warband Craft**. Doble click y a jugar.
  (Si Prism estaba abierto y no la ves, cerralo y abrilo de nuevo.)

## Actualizar

Cada vez que haya cambios en el repo: **doble click en `Actualizar Warband Craft.command`** otra vez. Baja el código
nuevo, la última compilación del mod y los mods que hayan cambiado. Tus mundos y tu configuración no se tocan.

Si querés probar un cambio que todavía no subiste (o GitHub todavía está compilando), en la Terminal:

```bash
cd ~/warbandcraft && ./mac/actualizar.sh --compilar
```

Compila el mod en la Mac (la primera vez baja Java 17 y Forge: tarda varios minutos).

## Si algo anda mal

- **Va lento**: apagá los shaders con la tecla **O**. En Prism, click derecho en la instancia → Editar → Configuración →
  Memoria: con 16 GB de RAM poné 6144 como máximo.
- **El instalador da un ERROR**: copiá el texto de la Terminal y pasáselo a Claude.
- **Teclas**: V vista aérea · K panel · J rutas · B batalla/asedio · M mapa de Recruits · R órdenes · U facción · O shaders.

## Dónde queda todo

`~/Library/Application Support/PrismLauncher/instances/WarbandCraft/minecraft` (mundos en `saves`, mods en `mods`).
Los mods que agregues vos a mano en `mods` se respetan; las versiones viejas de los del paquete van a `mods-viejos`.
