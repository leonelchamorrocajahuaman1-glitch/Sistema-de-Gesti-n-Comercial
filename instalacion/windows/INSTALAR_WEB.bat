@echo off
chcp 65001 >nul
title Sistema de Gestion Web - Instalador
rem Instala la version web en la PC servidor: arranque automatico, firewall (solo red local) e icono.
rem Requisito previo: la base de datos ya instalada con INSTALAR.bat (crea conexion.properties).

net session >nul 2>&1
if errorlevel 1 (
    echo.
    echo  Este instalador necesita permisos de administrador.
    echo.
    echo  Cierre esta ventana, haga clic derecho sobre INSTALAR_WEB.bat
    echo  y elija "Ejecutar como administrador".
    echo.
    pause
    exit /b 1
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0instalar_web.ps1" %*
echo.
pause
