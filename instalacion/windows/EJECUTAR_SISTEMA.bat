@echo off
REM Inicia el Sistema de Gestion (servidor y clientes).
where javaw >nul 2>nul
if errorlevel 1 (
  echo No se encontro Java. Instale Java 17 o superior desde https://adoptium.net
  pause
  exit /b 1
)
start "" javaw -jar "%~dp0sistema-gestion.jar"
