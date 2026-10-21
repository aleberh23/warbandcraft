@echo off
setlocal
cd /d "%~dp0"
title Warband Craft - compilar e instalar
echo ================================================
echo   Warband Craft: compilando e instalando el mod
echo ================================================
echo.

rem --- Java 17 propio (no toca el Java que ya tengas) ---
set "JDK_DIR=%~dp0.jdk17"
if exist "%JDK_DIR%\bin\java.exe" goto havejdk
echo [1/6] Descargando Java 17 (solo la primera vez, ~190 MB)...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile 'jdk17.zip'; Expand-Archive 'jdk17.zip' -DestinationPath '.jdk17tmp' -Force; $d = Get-ChildItem '.jdk17tmp' | Select-Object -First 1; Move-Item $d.FullName '.jdk17'; Remove-Item '.jdk17tmp' -Recurse -Force; Remove-Item 'jdk17.zip' -Force"
if not exist "%JDK_DIR%\bin\java.exe" (
  echo.
  echo ERROR: no se pudo descargar Java 17. Copia este texto y pasaselo a Claude.
  pause
  exit /b 1
)
:havejdk
set "JAVA_HOME=%JDK_DIR%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo [2/6] Compilando (la primera vez tarda varios minutos: baja Minecraft y Forge)...
echo       El detalle queda en build-log.txt
call "%~dp0gradlew.bat" build --no-daemon > build-log.txt 2>&1
set "RC=%ERRORLEVEL%"
type build-log.txt
if not "%RC%"=="0" (
  echo.
  echo ERROR al compilar. Claude puede leer build-log.txt directamente.
  if not "%NOPAUSE%"=="1" pause
  exit /b 1
)

set "MODS=%APPDATA%\.minecraft\mods"
if not "%~1"=="" set "MODS=%~1"
if not exist "%MODS%" mkdir "%MODS%"

echo [3/6] Copiando el mod a %MODS%
if exist "%MODS%\warbandcraft-*.jar" del /q "%MODS%\warbandcraft-*.jar"
copy /y "build\libs\warbandcraft-*.jar" "%MODS%\" >nul

echo [4/6] Revisando Villager Recruits...
if exist "%MODS%\recruits-*.jar" (
  echo    Ya tenes Recruits instalado:
  dir /b "%MODS%\recruits-*.jar"
  echo    Tiene que ser la 1.15 o mas nueva para Minecraft 1.20.1.
) else (
  echo    Copiando Recruits 1.15.2...
  copy /y "libs\recruits-1.20.1-1.15.2.jar" "%MODS%\" >nul
)

echo [5/6] Revisando Xaero's Minimap...
if exist "%MODS%\xaerominimap-*.jar" (
  echo    Ya tenes el minimapa instalado.
) else (
  echo    Copiando Xaero's Minimap...
  copy /y "libs\xaerominimap-forge-1.20.1-26.6.0.jar" "%MODS%\" >nul
)

echo [6/6] Revisando Villager Workers...
if exist "%MODS%\workers-*.jar" (
  echo    Ya tenes Villager Workers instalado:
  dir /b "%MODS%\workers-*.jar"
  echo    Tiene que ser la 2.0 o mas nueva para Minecraft 1.20.1.
) else (
  echo    Copiando Villager Workers 2.0.3...
  copy /y "libs\workers-1.20.1-2.0.3.jar" "%MODS%\" >nul
)

echo [7] Revisando los mods de mazmorras (YUNG's, Cataclysm, Dungeon Now Loading)...
call :copiamod "YungsApi-*.jar" "YungsApi-1.20-Forge-4.0.6.jar"
call :copiamod "YungsBetterDungeons-*.jar" "YungsBetterDungeons-1.20-Forge-4.0.4.jar"
call :copiamod "lionfishapi-*.jar" "lionfishapi-3.0.jar"
call :copiamod "curios-*.jar" "curios-forge-5.14.1+1.20.1.jar"
call :copiamod "L_Enders_Cataclysm-*.jar" "L_Enders_Cataclysm-3.31.jar"
if exist "%MODS%\Dungeon Now Loading-*.jar" (
  echo    Ya esta: Dungeon Now Loading
) else (
  call :copiamod "dungeon-now-loading-*.jar" "dungeon-now-loading-forge-1.20.1-2.2.jar"
)

echo [8] Revisando Epic Knights (armas y armaduras) y lo que pide...
call :copiamod "architectury-*.jar" "architectury-9.2.14-forge.jar"
call :copiamod "cloth-config-*.jar" "cloth-config-11.1.136-forge.jar"
call :copiamod "epic*knights-1*.jar" "epicknights-1.20.1-forge-10.15.jar"
call :copiamod "epic*knights*addon*.jar" "epicknightsaddon-1.20.x-forge-2.6.jar"
call :copiamod "epic*knights*antique*.jar" "epicknightsantique-1.20.x-forge-1.13.jar"
call :copiamod "epic*knights*slavic*.jar" "epicknightsslavic-1.20.x-forge-2.1.jar"

echo [9] Revisando las estructuras chicas (Dungeons and Taverns, Structory)...
call :copiamod "*taverns*.jar" "dungeonstaverns-3.0.3.f.jar"
call :copiamod "structory?1*.jar" "structory-1.3.5.jar"
call :copiamod "structory?towers*.jar" "structory_towers-1.0.7.jar"

echo.
echo ================================================
echo   LISTO. Abri Minecraft con el perfil de Forge 1.20.1.
echo ================================================
if not "%NOPAUSE%"=="1" pause
exit /b 0

:copiamod
rem %~1 = patron de lo que ya puede estar instalado, %~2 = jar de libs a copiar si falta (vacio = solo mirar)
if exist "%MODS%\%~1" (
  echo    Ya esta: %~1
  exit /b 0
)
if "%~2"=="" exit /b 0
echo    Copiando %~2...
copy /y "libs\%~2" "%MODS%\" >nul
exit /b 0
