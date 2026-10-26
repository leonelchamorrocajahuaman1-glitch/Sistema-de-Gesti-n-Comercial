<#
  Sistema de Gestión Comercial - instalación de la VERSIÓN WEB en la computadora servidor (Windows)

  Requisito: la base de datos ya instalada con INSTALAR.bat (deja conexion.properties en la
  carpeta del proyecto). Se ejecuta con INSTALAR_WEB.bat (clic derecho > Ejecutar como administrador).

  Qué hace:
    1. Compila la versión web (web\target\sistema-web.jar) con el Maven incluido (mvnw).
    2. Copia el programa y conexion.properties a C:\Program Files\SistemaGestionWeb
       (conexion.properties solo legible por administradores y por el servicio).
    3. Registra una tarea de Windows que arranca el sistema al encender la PC, con la
       cuenta limitada "Servicio local", y lo reinicia si se detiene.
    4. Abre el puerto 8080 en el Firewall SOLO para la red local.
    5. Crea el icono "Sistema de Gestión (web)" en el escritorio de todos los usuarios.

  Con -Desinstalar deshace los pasos 2 a 5 (no toca la base de datos).
#>
param(
    [switch]$Desinstalar,
    [int]$Puerto = 8080
)
$ErrorActionPreference = "Stop"

$Destino = Join-Path $env:ProgramFiles "SistemaGestionWeb"
$Tarea = "Sistema de Gestion Web"
$Regla = "Sistema de Gestion Web (puerto $Puerto, red local)"
$Icono = Join-Path ([Environment]::GetFolderPath("CommonDesktopDirectory")) "Sistema de Gestión (web).url"

function Paso($texto) { Write-Host "`n==> $texto" -ForegroundColor Cyan }
function Nativo([scriptblock]$bloque) {
    # Ver instalar_servidor.ps1: los programas externos que escriben en stderr no deben abortar.
    $ErrorActionPreference = "Continue"
    & $bloque
}

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Haga clic derecho sobre INSTALAR_WEB.bat y elija 'Ejecutar como administrador'."
}

$raiz = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
try { Start-Transcript -Path (Join-Path $raiz "instalacion_web.log") -Force | Out-Null } catch { }
trap {
    Write-Host "`nERROR: $_" -ForegroundColor Red
    Write-Host "Envíe una foto de esta ventana o el archivo instalacion_web.log para recibir ayuda." -ForegroundColor Yellow
    try { Stop-Transcript | Out-Null } catch { }
    exit 1
}

function DetenerSistema {
    if (Get-ScheduledTask -TaskName $Tarea -ErrorAction SilentlyContinue) {
        Stop-ScheduledTask -TaskName $Tarea -ErrorAction SilentlyContinue
    }
    Get-CimInstance Win32_Process -Filter "Name like 'java%.exe'" |
        Where-Object { $_.CommandLine -like "*SistemaGestionWeb*sistema-web.jar*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 2
}

# ---------------------------------------------------------------- desinstalar
if ($Desinstalar) {
    Paso "Deteniendo el sistema web"
    DetenerSistema
    if (Get-ScheduledTask -TaskName $Tarea -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $Tarea -Confirm:$false
    }
    Paso "Quitando la regla del Firewall y el icono"
    Get-NetFirewallRule -DisplayName "Sistema de Gestion Web*" -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    Remove-Item $Icono -ErrorAction SilentlyContinue
    Paso "Borrando $Destino"
    Remove-Item $Destino -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "`nListo. La base de datos y sus datos NO se modificaron." -ForegroundColor Green
    try { Stop-Transcript | Out-Null } catch { }
    exit 0
}

# ---------------------------------------------------------------- Java
Paso "Verificando Java (17 o superior)"
function VersionJava($exe) {
    $java = Join-Path (Split-Path $exe) "java.exe"
    if (-not (Test-Path $java)) { return 0 }
    $salida = (Nativo { & $java -version 2>&1 }) | ForEach-Object { "$_" }
    if (($salida -join " ") -match 'version "(\d+)(?:\.(\d+))?') {
        $mayor = [int]$Matches[1]
        if ($mayor -eq 1 -and $Matches[2]) { $mayor = [int]$Matches[2] }
        return $mayor
    }
    0
}
$rutas = "C:\Program Files\Eclipse Adoptium\*\bin\javaw.exe", "C:\Program Files\Java\*\bin\javaw.exe",
         "C:\Program Files\Microsoft\jdk*\bin\javaw.exe", "C:\Program Files\Zulu\*\bin\javaw.exe"
$candidatos = @(Get-ChildItem $rutas -ErrorAction SilentlyContinue | Sort-Object FullName -Descending |
                ForEach-Object { $_.FullName })
$c = Get-Command javaw.exe -ErrorAction SilentlyContinue
if ($c) { $candidatos = @($c.Source) + $candidatos }
$javaw = $candidatos | Where-Object { (VersionJava $_) -ge 17 } | Select-Object -First 1
if (-not $javaw) { throw "No se encontró Java 17 o superior. Instálelo desde https://adoptium.net y vuelva a ejecutar." }
Write-Host "Java: $javaw"

# ---------------------------------------------------------------- conexión a la base
Paso "Buscando conexion.properties (datos de conexión a PostgreSQL)"
$conexion = @((Join-Path $raiz "conexion.properties"), (Join-Path $Destino "conexion.properties")) |
            Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $conexion) {
    throw "No existe conexion.properties. Instale primero la base de datos con instalacion\windows\INSTALAR.bat."
}
Write-Host "Usando: $conexion"

# ---------------------------------------------------------------- compilar
Paso "Compilando la versión web (la primera vez descarga componentes y tarda unos minutos)"
$web = Join-Path $raiz "web"
$jar = Join-Path $web "target\sistema-web.jar"
$mvnw = Join-Path $web "mvnw.cmd"
if (Test-Path $mvnw) {
    Push-Location $web
    try {
        Nativo { & $mvnw -B -q package -DskipTests 2>&1 | ForEach-Object { "$_" } | Write-Host }
        $compilo = ($LASTEXITCODE -eq 0)
    } finally { Pop-Location }
    if (-not $compilo) {
        if (Test-Path $jar) { Write-Host "No se pudo compilar; se usará el programa compilado anteriormente." -ForegroundColor Yellow }
        else { throw "No se pudo compilar la versión web. Revise el mensaje anterior (¿hay conexión a internet?)." }
    }
}
if (-not (Test-Path $jar)) { throw "No se encontró $jar." }

# ---------------------------------------------------------------- copiar
Paso "Copiando el programa a $Destino"
DetenerSistema
New-Item -ItemType Directory -Force -Path $Destino, (Join-Path $Destino "logs") | Out-Null
Copy-Item $jar (Join-Path $Destino "sistema-web.jar") -Force
if ($conexion -ne (Join-Path $Destino "conexion.properties")) {
    Copy-Item $conexion (Join-Path $Destino "conexion.properties") -Force
}

# Cuenta limitada con la que corre el sistema (nombre traducido según el idioma de Windows).
$cuenta = (New-Object Security.Principal.SecurityIdentifier "S-1-5-19").Translate([Security.Principal.NTAccount]).Value
Write-Host "Cuenta del servicio: $cuenta"
# conexion.properties: solo administradores, SYSTEM y la cuenta del servicio pueden leerlo.
Nativo { icacls (Join-Path $Destino "conexion.properties") /inheritance:r /grant:r "*S-1-5-32-544:F" "*S-1-5-18:F" "*S-1-5-19:R" | Out-Null }
if ($LASTEXITCODE -ne 0) { throw "No se pudieron asignar los permisos de conexion.properties." }
# logs: el servicio necesita escribir el registro de eventos.
Nativo { icacls (Join-Path $Destino "logs") /grant "*S-1-5-19:(OI)(CI)M" | Out-Null }

# ---------------------------------------------------------------- arranque automático
Paso "Registrando el arranque automático al encender la PC"
$accion = New-ScheduledTaskAction -Execute $javaw `
    -Argument "-Xmx512m -jar `"$Destino\sistema-web.jar`" --server.port=$Puerto" -WorkingDirectory $Destino
$disparador = New-ScheduledTaskTrigger -AtStartup
$ajustes = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
    -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -MultipleInstances IgnoreNew
$quien = New-ScheduledTaskPrincipal -UserId $cuenta -LogonType ServiceAccount
Register-ScheduledTask -TaskName $Tarea -Action $accion -Trigger $disparador -Settings $ajustes -Principal $quien `
    -Description "Sistema de Gestión Comercial - página web en el puerto $Puerto" -Force | Out-Null
Start-ScheduledTask -TaskName $Tarea

# ---------------------------------------------------------------- firewall
Paso "Abriendo el puerto $Puerto en el Firewall (solo red local)"
Get-NetFirewallRule -DisplayName "Sistema de Gestion Web*" -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName $Regla -Direction Inbound -Protocol TCP -LocalPort $Puerto `
    -RemoteAddress LocalSubnet -Action Allow -Profile Any | Out-Null

# ---------------------------------------------------------------- icono
Paso "Creando el icono del escritorio"
$iconoArchivo = Join-Path $env:SystemRoot "System32\shell32.dll"
$contenido = "[InternetShortcut]`r`nURL=http://localhost:$Puerto/`r`nIconFile=$iconoArchivo`r`nIconIndex=13`r`n"
[IO.File]::WriteAllText($Icono, $contenido)

# ---------------------------------------------------------------- comprobar
Paso "Esperando a que el sistema responda"
$listo = $false
for ($i = 0; $i -lt 60 -and -not $listo; $i++) {
    Start-Sleep -Seconds 2
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$Puerto/login" -UseBasicParsing -TimeoutSec 3
        $listo = ($r.StatusCode -eq 200)
    } catch { }
}
if (-not $listo) {
    throw "El sistema no respondió en 2 minutos. Revise $Destino\logs\sistema-web.log"
}

$ips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
       Where-Object { $_.IPAddress -notmatch '^(127\.|169\.254\.)' -and $_.PrefixOrigin -ne 'WellKnown' } |
       Select-Object -ExpandProperty IPAddress
Write-Host ""
Write-Host "==========================================================" -ForegroundColor Green
Write-Host " Sistema web instalado y funcionando." -ForegroundColor Green
Write-Host " En esta PC: icono 'Sistema de Gestión (web)' del escritorio."
Write-Host " En las otras PC y celulares (misma red), abra el navegador en:"
foreach ($ip in $ips) { Write-Host "     http://${ip}:$Puerto" -ForegroundColor Yellow }
Write-Host " Arranca solo al encender esta PC (no hace falta iniciar sesión en Windows)."
Write-Host "==========================================================" -ForegroundColor Green
try { Stop-Transcript | Out-Null } catch { }
Start-Process "http://localhost:$Puerto/"
