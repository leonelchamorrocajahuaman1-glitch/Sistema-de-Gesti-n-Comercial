@echo off
REM Abre el Sistema de Gestion (servidor o computadora cliente).
REM Necesita Java 17 o superior: se ignora un Java 8 que haya quedado en el PATH.
cd /d "%~dp0"
set "JAVAW="
for /d %%D in ("C:\Program Files\Eclipse Adoptium\*" "C:\Program Files\Java\*" "C:\Program Files\Microsoft\jdk*" "C:\Program Files\Zulu\*") do if exist "%%~D\bin\java.exe" call :probar "%%~D\bin\java.exe"
for %%J in (java.exe) do if not "%%~$PATH:J"=="" call :probar "%%~$PATH:J"
if not defined JAVAW (
  echo No se encontro Java 17 o superior.
  echo Instalelo desde https://adoptium.net y vuelva a intentar.
  pause
  exit /b 1
)
start "" "%JAVAW%" -jar "%~dp0sistema-gestion.jar"
exit /b

:probar
REM %1 = ruta de java.exe; se acepta si es version 17 a 99.
if defined JAVAW exit /b
"%~1" -version 2>&1 | findstr /r /c:"version \"1[7-9]" /c:"version \"[2-9][0-9]" >nul || exit /b
if exist "%~dp1javaw.exe" set "JAVAW=%~dp1javaw.exe"
exit /b
