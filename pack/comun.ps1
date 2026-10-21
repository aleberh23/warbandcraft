# Warband Craft: funciones que comparten el instalador del juego y el del servidor.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Paso($texto) { Write-Host ""; Write-Host "== $texto" -ForegroundColor Cyan }
function Ok($texto) { Write-Host "   $texto" -ForegroundColor Green }
function Info($texto) { Write-Host "   $texto" }

function Sha1($path) { (Get-FileHash -Algorithm SHA1 -LiteralPath $path).Hash.ToLower() }

# Baja un archivo y revisa que sea el correcto (sha1). Si ya está y coincide, no hace nada.
function Bajar($url, $destino, $sha1) {
    if ((Test-Path -LiteralPath $destino) -and $sha1 -and ((Sha1 $destino) -eq $sha1)) { return $false }
    $tmp = "$destino.part"
    for ($intento = 1; $intento -le 3; $intento++) {
        try {
            Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing -UserAgent 'warbandcraft-instalador/1.0'
            if ($sha1 -and ((Sha1 $tmp) -ne $sha1)) { throw "el archivo bajado no es el esperado" }
            Move-Item -LiteralPath $tmp -Destination $destino -Force
            return $true
        } catch {
            if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force }
            if ($intento -eq 3) { throw "No se pudo bajar $url ($($_.Exception.Message))" }
            Start-Sleep -Seconds 2
        }
    }
}

# Java 17 propio (solo para instalar Forge y, en el servidor, para correrlo). No toca el Java que ya tengas.
function Java17($carpeta, $url) {
    $java = Join-Path $carpeta 'bin\java.exe'
    if (Test-Path -LiteralPath $java) { return $java }
    Info "Bajando Java 17 (solo la primera vez, ~45 MB)..."
    $zip = "$carpeta.zip"; $tmp = "$carpeta.tmp"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $carpeta) | Out-Null
    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
    if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Recurse -Force }
    Expand-Archive -LiteralPath $zip -DestinationPath $tmp -Force
    $dentro = Get-ChildItem -LiteralPath $tmp -Directory | Select-Object -First 1
    if (Test-Path -LiteralPath $carpeta) { Remove-Item -LiteralPath $carpeta -Recurse -Force }
    Move-Item -LiteralPath $dentro.FullName -Destination $carpeta
    Remove-Item -LiteralPath $tmp -Recurse -Force
    Remove-Item -LiteralPath $zip -Force
    if (-not (Test-Path -LiteralPath $java)) { throw "No se pudo preparar Java 17" }
    return $java
}

# Pone los archivos de la lista que van de este lado ("client" o "server") en $base\<folder>.
# Las versiones viejas de esos mods se mueven a mods-viejos/ en vez de borrarse.
function PonerArchivos($lista, $lado, $base, $aquiDir) {
    $quiero = @{}
    foreach ($f in $lista.files) {
        if ($f.side -ne 'both' -and $f.side -ne $lado) { continue }
        $dir = Join-Path $base $f.folder
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $destino = Join-Path $dir $f.file
        if ($f.folder -eq 'mods') { $quiero[$f.file] = $true }
        if (Bajar $f.url $destino $f.sha1) { Ok "$($f.name): bajado" } else { Ok "$($f.name): ya estaba" }
    }
    # Warband Craft viene en el paquete
    $mods = Join-Path $base 'mods'
    New-Item -ItemType Directory -Force -Path $mods | Out-Null
    $jar = Get-ChildItem -LiteralPath $aquiDir -Filter 'warbandcraft-*.jar' | Select-Object -First 1
    if (-not $jar) { throw "Falta warbandcraft-*.jar al lado del instalador" }
    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $mods $jar.Name) -Force
    $quiero[$jar.Name] = $true
    Ok "Warband Craft: $($jar.Name)"
    # Solo versiones viejas de los mods del paquete (mismo nombre antes del primer guion): los que agregues vos quedan
    $prefijos = @{}
    foreach ($n in $quiero.Keys) { $prefijos[($n -split '-')[0].ToLower()] = $true }
    foreach ($viejo in Get-ChildItem -LiteralPath $mods -Filter '*.jar') {
        if ($quiero.ContainsKey($viejo.Name)) { continue }
        if (-not $prefijos.ContainsKey(($viejo.Name -split '-')[0].ToLower())) { continue }
        $aparte = Join-Path $base 'mods-viejos'
        New-Item -ItemType Directory -Force -Path $aparte | Out-Null
        Move-Item -LiteralPath $viejo.FullName -Destination (Join-Path $aparte $viejo.Name) -Force
        Info "Saqué $($viejo.Name) (quedó en mods-viejos)"
    }
}

# Memoria para Minecraft según la RAM de la compu.
function Memoria($maximo) {
    $gb = [math]::Floor((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB)
    $mem = if ($gb -ge 16) { 6 } elseif ($gb -ge 12) { 5 } elseif ($gb -ge 8) { 4 } else { 3 }
    return [math]::Min($mem, $maximo)
}

function Fin($codigo) {
    if ($env:NOPAUSE -ne '1') { Write-Host ""; Read-Host "Apretá Enter para cerrar" | Out-Null }
    exit $codigo
}
