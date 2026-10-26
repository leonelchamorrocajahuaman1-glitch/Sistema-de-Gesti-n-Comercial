@echo off
REM Instala la base de datos y habilita la red local en la COMPUTADORA SERVIDOR.
REM Clic derecho > Ejecutar como administrador.
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0instalar_servidor.ps1" %*
pause
