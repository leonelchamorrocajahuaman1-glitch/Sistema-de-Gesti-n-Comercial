@echo off
REM Abre el Sistema de Gestion (servidor o computadora cliente).
cd /d "%~dp0"
set "JAVAW="
for %%J in (javaw.exe) do set "JAVAW=%%~$PATH:J"
if not defined JAVAW for /d %%D in ("C:\Program Files\Eclipse Adoptium\*" "C:\Program Files\Java\*" "C:\Program Files\Microsoft\jdk*") do if exist "%%~D\bin\javaw.exe" set "JAVAW=%%~D\bin\javaw.exe"
if not defined JAVAW (
  echo No se encontro Java. Instalelo desde https://adoptium.net y vuelva a intentar.
  pause
  exit /b 1
)
start "" "%JAVAW%" -jar "%~dp0sistema-gestion.jar"
