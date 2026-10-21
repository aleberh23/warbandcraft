@echo off
setlocal
cd /d "%~dp0"
title Warband Craft - armar los paquetes para repartir
set "JAVA_HOME=%~dp0.jdk17"
echo Compilando...
call "%~dp0gradlew.bat" build --no-daemon -q
if errorlevel 1 (
  echo ERROR al compilar.
  if not "%NOPAUSE%"=="1" pause
  exit /b 1
)
python "%~dp0tools\make_pack.py"
if errorlevel 1 (
  echo ERROR al armar los paquetes.
  if not "%NOPAUSE%"=="1" pause
  exit /b 1
)
echo.
echo Listo: los dos .zip estan en la carpeta dist.
if not "%NOPAUSE%"=="1" pause