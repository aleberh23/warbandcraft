@echo off
setlocal
cd /d "%~dp0"
title Warband Craft - Minecraft de desarrollo
rem Abre un Minecraft 1.20.1 de prueba con Warband Craft + Recruits (no toca tu instalacion normal)
set "JAVA_HOME=%~dp0.jdk17"
set "PATH=%JAVA_HOME%\bin;%PATH%"
if not exist run mkdir run
if not exist run\options.txt (
  > run\options.txt (
    echo lang:es_ar
    echo pauseOnLostFocus:false
    echo tutorialStep:none
    echo joinedFirstServer:true
    echo onboardAccessibility:false
    echo skipMultiplayerWarning:true
    echo guiScale:2
  )
)
call gradlew.bat runClient --no-daemon > client-log.txt 2>&1
