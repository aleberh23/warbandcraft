@echo off
setlocal
cd /d "%~dp0"
title Warband Craft - conectar con GitHub (una sola vez)
rem Sube a GitHub lo que hay en esta carpeta, en lugar de lo que habia. Lo viejo de GitHub queda en la rama "respaldo-github".
rem Despues de esto: commit + push desde IntelliJ y GitHub compila y actualiza el servidor solo.

set "REPO=https://github.com/aleberh23/warbandcraft"

where git >nul 2>&1
if errorlevel 1 (
  echo No encuentro git. Instalalo de https://git-scm.com/download/win y volve a correr esto.
  goto error
)

git config user.email >nul 2>&1
if errorlevel 1 git config user.email "aledeberhardt@gmail.com"
git config user.name >nul 2>&1
if errorlevel 1 git config user.name "Ale"

echo [1/5] Poniendo los workflows de GitHub Actions en su lugar...
if not exist ".github\workflows" mkdir ".github\workflows"
if exist "tools\build.yml" move /y "tools\build.yml" ".github\workflows\build.yml" >nul
if exist "tools\deploy.yml" move /y "tools\deploy.yml" ".github\workflows\deploy.yml" >nul
if exist "subir.bat" del /q "subir.bat"
if exist "tools\server_mods.py" del /q "tools\server_mods.py"

echo [2/5] Guardando todo en git (respetando .gitignore)...
git rm -r -q --cached --ignore-unmatch . >nul
git add -A
git update-index --chmod=+x gradlew
git diff --cached --quiet
if errorlevel 1 git commit -q -m "Version de la PC: reemplaza lo que habia en GitHub"

echo [3/5] Conectando con %REPO% ...
git remote get-url origin >nul 2>&1
if errorlevel 1 git remote add origin %REPO%.git
git branch -M main

echo [4/5] Guardando lo que habia en GitHub en la rama respaldo-github ^(la primera vez te pide iniciar sesion^)...
git fetch -q origin main
if errorlevel 1 goto error_push
git push -q origin FETCH_HEAD:refs/heads/respaldo-github
if errorlevel 1 goto error_push

echo [5/5] Subiendo tu version a main...
git push --force -u origin main
if errorlevel 1 goto error_push

echo.
echo ================================================
echo   LISTO. GitHub esta compilando y despues sube al servidor.
echo   De ahora en mas: commit + push desde IntelliJ.
echo   Este archivo ya no hace falta, lo podes borrar.
echo ================================================
start "" "%REPO%/actions"
pause
exit /b 0

:error_push
echo.
echo No se pudo hablar con GitHub. Copia este texto y pasaselo a Claude.
:error
pause
exit /b 1
