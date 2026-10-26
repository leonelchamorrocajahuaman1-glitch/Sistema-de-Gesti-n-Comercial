@echo off
chcp 65001 >nul
title Sistema de Gestion Web - Desinstalar
rem Quita el arranque automatico, la regla del firewall, el icono y la carpeta del programa.
rem NO toca la base de datos: los datos del inventario se conservan.

net session >nul 2>&1
if errorlevel 1 (
    echo.
    echo  Haga clic derecho sobre DESINSTALAR_WEB.bat y elija "Ejecutar como administrador".
    echo.
    pause
    exit /b 1
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0instalar_web.ps1" -Desinstalar
echo.
pause
