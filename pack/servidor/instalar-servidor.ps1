# Warband Craft: arma un servidor de Forge en esta misma carpeta. Lo llama "Instalar servidor.bat".
# Volver a correrlo = actualizar (no toca el mundo ni la configuración).
param(
    [switch]$AceptarEula
)
. (Join-Path $PSScriptRoot 'comun.ps1')

try {
    $lista = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $PSScriptRoot 'mods.json') | ConvertFrom-Json
    $dir = $PSScriptRoot
    $forgeId = "$($lista.minecraft)-$($lista.forge)"

    Write-Host "================================================" -ForegroundColor Yellow
    Write-Host "  Warband Craft - servidor" -ForegroundColor Yellow
    Write-Host "================================================" -ForegroundColor Yellow
    Info "Carpeta del servidor: $dir"

    Paso "Java 17"
    $java = Java17 (Join-Path $dir 'java') $lista.java
    Ok "Listo"

    Paso "Forge $($lista.forge)"
    if (Test-Path -LiteralPath (Join-Path $dir "libraries\net\minecraftforge\forge\$forgeId\win_args.txt")) {
        Ok "Ya estaba instalado"
    } else {
        $inst = Join-Path $dir "forge-$forgeId-installer.jar"
        Bajar $lista.forge_installer.url $inst $lista.forge_installer.sha1 | Out-Null
        Info "Instalando (baja el servidor de Minecraft, tarda un poco)..."
        $log = Join-Path $dir 'forge-instalador.log'
        for ($intento = 1; $intento -le 3; $intento++) {   # a veces se corta la descarga de Mojang
            Start-Process -FilePath $java -ArgumentList '-jar', "`"$inst`"", '--installServer', "`"$dir`"" -WorkingDirectory $dir `
                -Wait -NoNewWindow -RedirectStandardOutput $log -RedirectStandardError "$log.err" | Out-Null
            if (Test-Path -LiteralPath (Join-Path $dir "libraries\net\minecraftforge\forge\$forgeId\win_args.txt")) { break }
            if ($intento -lt 3) { Info "Se cortó la descarga, pruebo de nuevo ($intento/3)..." }
        }
        if (-not (Test-Path -LiteralPath (Join-Path $dir "libraries\net\minecraftforge\forge\$forgeId\win_args.txt"))) {
            throw "No se pudo instalar Forge (detalle en $log)"
        }
        Remove-Item -LiteralPath $inst -Force
        Ok "Instalado"
    }

    Paso "Mods"
    PonerArchivos $lista 'server' $dir $PSScriptRoot

    Paso "Configuración"
    $mem = Memoria 4
    [IO.File]::WriteAllText((Join-Path $dir 'user_jvm_args.txt'),
        "# Memoria del servidor (la puso el instalador de Warband Craft)`r`n-Xmx${mem}G`r`n-Xms2G`r`n-XX:+UseG1GC`r`n")
    Ok "Memoria: $mem GB"
    $props = Join-Path $dir 'server.properties'
    if (-not (Test-Path -LiteralPath $props)) {
        [IO.File]::WriteAllText($props, (@(
            'motd=Warband Craft', 'difficulty=normal', 'max-players=8', 'view-distance=10', 'simulation-distance=8',
            'spawn-protection=0', 'allow-flight=true', 'online-mode=true', 'enable-command-block=true', 'server-port=25565'
        ) -join "`r`n") + "`r`n")
        Ok "server.properties de partida"
    }
    $eula = Join-Path $dir 'eula.txt'
    if (-not ((Test-Path -LiteralPath $eula) -and ((Get-Content -Raw -LiteralPath $eula) -match 'eula=true'))) {
        if (-not $AceptarEula) {
            Write-Host ""
            Write-Host "   Para correr un servidor hay que aceptar el EULA de Minecraft:" -ForegroundColor Yellow
            Write-Host "   https://aka.ms/MinecraftEULA"
            $r = Read-Host "   ¿Lo aceptás? (S/N)"
            if ($r -notmatch '^[sSyY]') { Write-Host "   Sin aceptarlo el servidor no arranca. Volvé a correr esto cuando quieras."; Fin 1 }
        }
        [IO.File]::WriteAllText($eula, "eula=true`r`n")
        Ok "EULA aceptado"
    }

    Write-Host ""
    Write-Host "================================================" -ForegroundColor Green
    Write-Host "  LISTO. Para prenderlo: 'Iniciar servidor.bat'." -ForegroundColor Green
    Write-Host "  Cómo entra tu amigo: mirá LEEME.txt." -ForegroundColor Green
    Write-Host "================================================" -ForegroundColor Green
    Fin 0
} catch {
    Write-Host ""
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Sacale una foto a esta ventana y pasásela a Claude."
    Fin 1
}
