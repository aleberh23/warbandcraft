@echo off
cd /d "%~dp0"
title Warband Craft - instalador
if not exist "%~dp0instalar.ps1" (
  echo Primero descomprimi el .zip entero en una carpeta y despues abri este archivo desde ahi.
  pause
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0instalar.ps1" %*
