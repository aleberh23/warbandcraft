@echo off
setlocal
cd /d "%~dp0"
title Warband Craft - pruebas automaticas
rem Corre los GameTests sin abrir Minecraft. Resultado en test-log.txt
set "JAVA_HOME=%~dp0.jdk17"
set "PATH=%JAVA_HOME%\bin;%PATH%"
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo Primero corre compilar.bat una vez.
  pause
  exit /b 1
)
rem Mundo limpio en cada corrida: si no, quedan soldados y mobs de pruebas anteriores (las pruebas usan run-tests, no run)
if exist "run-tests\world" rmdir /s /q "run-tests\world"
echo Corriendo pruebas (tarda 1-3 minutos)...
call "%~dp0gradlew.bat" runGameTestServer --no-daemon > test-log.txt 2>&1
set "RC=1"
findstr /c:"required tests passed" test-log.txt >nul && set "RC=0"
if "%RC%"=="0" echo TODAS LAS PRUEBAS PASARON
if "%RC%"=="1" echo HAY PRUEBAS QUE FALLARON - Claude puede leer test-log.txt
>> test-log.txt echo RESULTADO=%RC%
if not "%NOPAUSE%"=="1" pause
exit /b %RC%
