# Warband Craft: instala el juego para el launcher común de Minecraft.
# Lo llama "Instalar Warband Craft.bat". Todo va a una carpeta aparte (%APPDATA%\.warbandcraft) con su propio perfil
# en el launcher: no toca tus otros mods ni tus mundos de siempre. Volver a correrlo = actualizar.
param(
    [string]$Juego = (Join-Path $env:APPDATA '.warbandcraft'),
    [switch]$SinPerfil,
    [switch]$SoloArchivos   # Prism Launcher: solo mods, shaders y configuración (Prism ya pone Java y Forge)
)
. (Join-Path $PSScriptRoot 'comun.ps1')

try {
    $lista = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $PSScriptRoot 'mods.json') | ConvertFrom-Json
    $mc = Join-Path $env:APPDATA '.minecraft'
    $forgeId = "$($lista.minecraft)-forge-$($lista.forge)"

    Write-Host "================================================" -ForegroundColor Yellow
    Write-Host "  Warband Craft - instalador" -ForegroundColor Yellow
    Write-Host "================================================" -ForegroundColor Yellow
    Info "Se instala en: $Juego"

    if (-not $SoloArchivos) {
        Paso "Revisando el launcher de Minecraft"
        $perfiles = @('launcher_profiles.json', 'launcher_profiles_microsoft_store.json') |
            ForEach-Object { Join-Path $mc $_ } | Where-Object { Test-Path -LiteralPath $_ }
        if (-not $perfiles) {
            Write-Host "   No encontré el launcher de Minecraft en $mc." -ForegroundColor Red
            Write-Host "   Instalá el launcher oficial, abrilo una vez con tu cuenta y volvé a correr esto."
            Fin 1
        }
        while (Get-Process -Name 'MinecraftLauncher', 'Minecraft' -ErrorAction SilentlyContinue) {
            Write-Host "   El launcher de Minecraft está abierto: cerralo (si no, borra el perfil nuevo al cerrarse)." -ForegroundColor Yellow
            Read-Host "   Cuando lo cierres, apretá Enter" | Out-Null
        }
        Ok "Launcher encontrado"

        Paso "Java 17 (para instalar Forge)"
        $java = Java17 (Join-Path $Juego 'java') $lista.java
        Ok "Listo"

        Paso "Forge $($lista.forge)"
        if (Test-Path -LiteralPath (Join-Path $mc "versions\$forgeId\$forgeId.json")) {
            Ok "Ya estaba instalado"
        } else {
            $inst = Join-Path $env:TEMP "forge-$forgeId-installer.jar"
            Bajar $lista.forge_installer.url $inst $lista.forge_installer.sha1 | Out-Null
            Info "Instalando (baja Minecraft $($lista.minecraft) si hace falta, tarda un poco)..."
            $log = Join-Path $Juego 'forge-instalador.log'
            for ($intento = 1; $intento -le 3; $intento++) {   # a veces se corta la descarga de Mojang
                Start-Process -FilePath $java -ArgumentList '-jar', "`"$inst`"", '--installClient', "`"$mc`"" -WorkingDirectory $env:TEMP `
                    -Wait -NoNewWindow -RedirectStandardOutput $log -RedirectStandardError "$log.err" | Out-Null
                if (Test-Path -LiteralPath (Join-Path $mc "versions\$forgeId\$forgeId.json")) { break }
                if ($intento -lt 3) { Info "Se cortó la descarga, pruebo de nuevo ($intento/3)..." }
            }
            if (-not (Test-Path -LiteralPath (Join-Path $mc "versions\$forgeId\$forgeId.json"))) {
                throw "No se pudo instalar Forge (detalle en $log)"
            }
            Ok "Instalado"
        }
    }

    Paso "Mods y shaders"
    PonerArchivos $lista 'client' $Juego $PSScriptRoot

    Paso "Configuración inicial"
    $cfg = Join-Path $Juego 'config'
    New-Item -ItemType Directory -Force -Path $cfg | Out-Null
    $oculus = Join-Path $cfg 'oculus.properties'
    if (-not (Test-Path -LiteralPath $oculus)) {
        [IO.File]::WriteAllText($oculus, "enableShaders=true`r`nshaderPack=$($lista.shader)`r`n")
        Ok "Shaders prendidos (Complementary Reimagined)"
    }
    # options.txt: idioma la primera vez y, una sola vez (para no pisar lo que cambies después), las teclas que chocan.
    $opciones = Join-Path $Juego 'options.txt'
    $marca = Join-Path $Juego '.teclas-warbandcraft-1'
    $poner = [ordered]@{}
    if (-not (Test-Path -LiteralPath $opciones)) {
        $poner['version'] = '3465'   # 1.20.1: sin esto Minecraft lo lee como un archivo viejo y lo descarta
        $poner['lang'] = 'es_ar'; $poner['renderDistance'] = '10'; $poner['simulationDistance'] = '8'
    }
    if (-not (Test-Path -LiteralPath $marca)) {
        $nada = 'key.keyboard.unknown'
        $poner['key_iris.keybind.toggleShaders'] = $nada           # K = nuestro Panel (los shaders quedan en la O)
        $poner['key_iris.keybind.reload'] = $nada                  # R = órdenes de Recruits
        $poner['key_gui.xaero_new_waypoint'] = $nada               # B = meterse en una batalla
        $poner['key_gui.xaero_waypoints_key'] = 'key.keyboard.i'   # U = pantalla de Recruits
        $poner['key_key.cataclysm.boots_ability'] = 'key.keyboard.h'       # V = vista aérea
        $poner['key_key.cataclysm.chestplate_ability'] = 'key.keyboard.n'  # Y = opciones del minimapa
        $poner['key_key.saveToolbarActivator'] = $nada             # C y X quedan para Cataclysm
        $poner['key_key.loadToolbarActivator'] = $nada
    }
    if ($poner.Count -gt 0) {
        $lineas = [Collections.Generic.List[string]]::new()
        if (Test-Path -LiteralPath $opciones) { $lineas.AddRange([IO.File]::ReadAllLines($opciones)) }
        foreach ($k in $poner.Keys) {
            $i = $lineas.FindIndex([Predicate[string]]{ param($x) $x.StartsWith("$k`:") })
            if ($i -ge 0) { $lineas[$i] = "$k`:$($poner[$k])" } else { $lineas.Add("$k`:$($poner[$k])") }
        }
        [IO.File]::WriteAllLines($opciones, $lineas, (New-Object Text.UTF8Encoding $false))
        [IO.File]::WriteAllText($marca, "Teclas acomodadas por el instalador de Warband Craft`r`n")
        Ok "Idioma y teclas acomodadas"
    }

    if (-not $SinPerfil -and -not $SoloArchivos) {
        Paso "Perfil 'Warband Craft' en el launcher"
        $mem = Memoria 6
        $jvm = "-Xmx${mem}G -Xms2G -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M"
        $ahora = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
        foreach ($p in $perfiles) {
            Copy-Item -LiteralPath $p -Destination "$p.antes-de-warbandcraft" -Force
            $json = Get-Content -Raw -Encoding UTF8 -LiteralPath $p | ConvertFrom-Json
            if (-not $json.profiles) { $json | Add-Member -NotePropertyName profiles -NotePropertyValue ([pscustomobject]@{}) -Force }
            $perfil = [pscustomobject]@{
                name = 'Warband Craft'; type = 'custom'; icon = 'Furnace'
                lastVersionId = $forgeId; gameDir = $Juego; javaArgs = $jvm
                created = $ahora; lastUsed = $ahora
            }
            $json.profiles | Add-Member -NotePropertyName 'warbandcraft' -NotePropertyValue $perfil -Force
            [IO.File]::WriteAllText($p, ($json | ConvertTo-Json -Depth 32), (New-Object Text.UTF8Encoding $false))
        }
        Ok "Perfil listo ($mem GB de memoria)"
    }

    if (-not $SoloArchivos) {
        Paso "Acceso directo 'Jugar Warband Craft'"
        $act = Join-Path $Juego 'actualizador'
        New-Item -ItemType Directory -Force -Path $act | Out-Null
        $script = Join-Path $act 'actualizar.ps1'
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'actualizar.ps1') -Destination $script -Force
        $ws = New-Object -ComObject WScript.Shell
        $lnk = $ws.CreateShortcut((Join-Path ([Environment]::GetFolderPath('Desktop')) 'Jugar Warband Craft.lnk'))
        $lnk.TargetPath = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        $lnk.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$script`""
        $lnk.WorkingDirectory = $act
        $lnk.Description = 'Actualiza Warband Craft y abre el launcher de Minecraft'
        $lnk.Save()
        Ok "En el escritorio: entrá siempre por ahí y se actualiza solo"
    }

    Write-Host ""
    Write-Host "================================================" -ForegroundColor Green
    Write-Host "  LISTO. Abrí el launcher de Minecraft, elegí el" -ForegroundColor Green
    Write-Host "  perfil 'Warband Craft' (abajo a la izquierda)" -ForegroundColor Green
    Write-Host "  y dale a Jugar." -ForegroundColor Green
    Write-Host "================================================" -ForegroundColor Green
    Fin 0
} catch {
    Write-Host ""
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Sacale una foto a esta ventana y pasásela a Ale."
    Fin 1
}
