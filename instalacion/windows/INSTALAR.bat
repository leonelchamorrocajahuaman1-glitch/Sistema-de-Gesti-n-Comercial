@echo off
chcp 65001 >nul
title Sistema de Gestion - Instalador
REM Instala TODO el Sistema de Gestion en esta computadora (servidor).
set "PS1=%~dp0instalacion\instalar_servidor.ps1"
if not exist "%PS1%" set "PS1=%~dp0instalar_servidor.ps1"
if not exist "%PS1%" goto sin_extraer

set "ESTE_BAT=%~f0"
net session >nul 2>&1
if not errorlevel 1 goto instalar

echo.
echo  Se necesitan permisos de administrador.
echo  Windows le mostrara un aviso: pulse SI.
echo  Si no lo ve, revise la barra de tareas (icono de escudo).
echo.
REM La ruta viaja por variable de entorno: funciona aunque tenga espacios o parentesis.
powershell -NoProfile -Command "try { Start-Process -Verb RunAs -FilePath $env:ESTE_BAT -ErrorAction Stop; exit 0 } catch { exit 1 }"
if errorlevel 1 goto sin_permiso
echo  El instalador continua en la NUEVA ventana que se abrio.
echo  Esta ventana se puede cerrar.
echo.
pause
exit /b

:instalar
powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1%"
echo.
echo  Si aparecio un error, envie una foto de esta ventana
echo  o el archivo instalacion.log de esta carpeta.
pause
exit /b

:sin_permiso
echo  No se otorgaron los permisos.
echo  Haga clic derecho en INSTALAR.bat y elija "Ejecutar como administrador".
echo.
pause
exit /b 1

:sin_extraer
echo.
echo  ERROR: falta la carpeta "instalacion".
echo  Primero descomprima el ZIP: clic derecho - Extraer todo,
echo  y ejecute INSTALAR.bat desde la carpeta extraida.
echo.
pause
exit /b 1
