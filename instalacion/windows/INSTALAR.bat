@echo off
REM Instala TODO el Sistema de Gestion en esta computadora (servidor).
REM Solo haga doble clic: pedira permisos de administrador automaticamente.
net session >nul 2>&1
if errorlevel 1 (
  powershell -NoProfile -Command "Start-Process -Verb RunAs -FilePath \"%~f0\""
  exit /b
)
chcp 65001 >nul
set "PS1=%~dp0instalacion\instalar_servidor.ps1"
if not exist "%PS1%" set "PS1=%~dp0instalar_servidor.ps1"
powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1%"
echo.
pause
