@echo off
cd /d "%~dp0"
title Warband Craft - instalar servidor
if not exist "%~dp0instalar-servidor.ps1" (
  echo Primero descomprimi el .zip entero en una carpeta y despues abri este archivo desde ahi.
  pause
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0instalar-servidor.ps1" %*
