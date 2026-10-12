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
echo [1/4] Descargando Java 17 (solo la primera vez, ~190 MB)...
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

echo [2/4] Compilando (la primera vez tarda varios minutos: baja Minecraft y Forge)...
call gradlew.bat build --no-daemon
if errorlevel 1 (
  echo.
  echo ERROR al compilar. Copia el texto de arriba y pegaselo a Claude.
  pause
  exit /b 1
)

set "MODS=%APPDATA%\.minecraft\mods"
if not "%~1"=="" set "MODS=%~1"
if not exist "%MODS%" mkdir "%MODS%"

echo [3/4] Copiando el mod a %MODS%
copy /y "build\libs\warbandcraft-0.1.0.jar" "%MODS%\" >nul

echo [4/4] Revisando Villager Recruits...
if exist "%MODS%\recruits-*.jar" (
  echo    Ya tenes Recruits instalado:
  dir /b "%MODS%\recruits-*.jar"
  echo    Tiene que ser la 1.15 o mas nueva para Minecraft 1.20.1.
) else (
  echo    Descargando Recruits 1.15.2 desde Modrinth...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest 'https://cdn.modrinth.com/data/WOg9lm4u/versions/2zXpVxK4/recruits-1.20.1-1.15.2.jar' -OutFile '%MODS%\recruits-1.20.1-1.15.2.jar'"
)

echo.
echo ================================================
echo   LISTO. Abri Minecraft con el perfil de Forge 1.20.1.
echo ================================================
pause
