# Warband Craft: actualiza el juego a la última versión y lo abre.
# Lo usan el acceso directo "Jugar Warband Craft" (launcher oficial) y Prism Launcher (comando antes de abrir, con -Prism).
# Baja el paquete de la release pública "ultima" y corre el mismo instalador de siempre, que solo baja lo que cambió.
# Si no hay internet o GitHub falla, sigue con lo que ya está instalado: nunca te deja sin jugar.
param(
    [switch]$Prism
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$env:NOPAUSE = '1'

$url = 'https://github.com/aleberh23/warbandcraft/releases/download/ultima/WarbandCraft-juego.zip'
$tmp = Join-Path $env:TEMP 'warbandcraft-actualizar'

function AbrirLauncher {
    $candidatos = @(
        "${env:ProgramFiles(x86)}\Minecraft Launcher\MinecraftLauncher.exe",
        "$env:ProgramFiles\Minecraft Launcher\MinecraftLauncher.exe",
        'C:\XboxGames\Minecraft Launcher\Content\Minecraft.exe'
    )
    $exe = $candidatos | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
    try {
        if ($exe) { Start-Process -FilePath $exe }
        else { Start-Process 'shell:AppsFolder\Microsoft.4297127D64EC6_8wekyb3d8bbwe!Minecraft' }   # launcher de la Microsoft Store
    } catch {
        Write-Host "No encontré el launcher de Minecraft: abrilo vos y elegí el perfil 'Warband Craft'." -ForegroundColor Yellow
        Start-Sleep -Seconds 6
    }
}

try {
    Write-Host "Warband Craft: buscando la última versión..." -ForegroundColor Cyan
    if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    $zip = Join-Path $tmp 'juego.zip'
    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing -UserAgent 'warbandcraft-actualizador/1.0'
    Expand-Archive -LiteralPath $zip -DestinationPath $tmp -Force
    $inst = Get-ChildItem -LiteralPath $tmp -Recurse -Filter 'instalar.ps1' | Select-Object -First 1
    if (-not $inst) { throw 'el paquete no trae instalar.ps1' }
    $ps = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'

    if ($Prism) {
        $juego = $env:INST_MC_DIR
        if (-not $juego) { throw 'falta INST_MC_DIR (esto lo tiene que correr Prism Launcher)' }
        & $ps -NoProfile -ExecutionPolicy Bypass -File $inst.FullName -Juego $juego -SoloArchivos
        if ($LASTEXITCODE -ne 0) { throw "el instalador terminó con error ($LASTEXITCODE)" }
        # El actualizador también se actualiza solo
        Copy-Item -LiteralPath (Join-Path $inst.DirectoryName 'actualizar.ps1') -Destination (Join-Path $PSScriptRoot 'actualizar.ps1') -Force
    } else {
        & $ps -NoProfile -ExecutionPolicy Bypass -File $inst.FullName
        if ($LASTEXITCODE -ne 0) { throw "el instalador terminó con error ($LASTEXITCODE)" }
    }
} catch {
    Write-Host ""
    Write-Host "No se pudo actualizar: $($_.Exception.Message)" -ForegroundColor Yellow
    Write-Host "Sigo con la versión que ya tenés instalada."
    if (-not $Prism) { Start-Sleep -Seconds 5 }
}

if (-not $Prism) { AbrirLauncher }
exit 0
