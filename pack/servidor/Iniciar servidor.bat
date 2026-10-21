@echo off
cd /d "%~dp0"
title Warband Craft - servidor
if not exist "%~dp0javain\java.exe" (
  echo Primero abri "Instalar servidor.bat".
  pause
  exit /b 1
)
echo Prendiendo el servidor... Para apagarlo escribi  stop  y Enter.
"%~dp0javain\java.exe" @user_jvm_args.txt @libraries/net/minecraftforge/forge/1.20.1-47.4.10/win_args.txt nogui
echo.
echo El servidor se apago.
if not "%NOPAUSE%"=="1" pause
