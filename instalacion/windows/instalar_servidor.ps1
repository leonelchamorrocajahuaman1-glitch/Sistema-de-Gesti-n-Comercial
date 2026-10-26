<#
  Sistema de Gestión Comercial - instalación de la COMPUTADORA SERVIDOR (Windows)

  Requisitos: PostgreSQL 15 o superior instalado (https://www.postgresql.org/download/windows/)
  Ejecutar con INSTALAR_SERVIDOR.bat (clic derecho > Ejecutar como administrador).

  Pasos que realiza:
    1. Crea la base de datos "inventario" e instala tablas, triggers y procedimientos.
    2. Habilita el usuario de red app_inventario con la contraseña que usted elija.
    3. Configura PostgreSQL para aceptar conexiones de la red local (LAN).
    4. Abre el puerto 5432 en el Firewall de Windows solo para la subred local.
    5. Reinicia PostgreSQL y muestra la IP que deben usar las computadoras cliente.
#>
param(
    [string]$BaseDatos = "inventario",
    [int]$Puerto = 5432,
    [switch]$Demo
)
$ErrorActionPreference = "Stop"

function Paso($texto) { Write-Host "`n==> $texto" -ForegroundColor Cyan }
function Texto([Security.SecureString]$s) {
    [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s))
}

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Ejecute este instalador como administrador (clic derecho > Ejecutar como administrador)."
}

$raiz = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$sqlDir = Join-Path $raiz "sql"

# ---------------------------------------------------------------- PostgreSQL
Paso "Buscando PostgreSQL instalado"
$servicio = Get-CimInstance Win32_Service | Where-Object { $_.Name -like "postgresql*" } |
            Sort-Object Name -Descending | Select-Object -First 1
if (-not $servicio) {
    throw "No se encontró PostgreSQL. Instálelo desde https://www.postgresql.org/download/windows/ y vuelva a ejecutar."
}
$ruta = $servicio.PathName
$pgCtl = if ($ruta -match '^"([^"]+)"') { $Matches[1] } else { ($ruta -split ' ')[0] }
$bin = Split-Path $pgCtl
$datos = if ($ruta -match '-D\s+"([^"]+)"') { $Matches[1] } elseif ($ruta -match '-D\s+(\S+)') { $Matches[1] }
$psql = Join-Path $bin "psql.exe"
Write-Host "Servicio: $($servicio.Name)"
Write-Host "Datos:    $datos"

$version = [int]((& $psql --version) -replace '^\D*(\d+).*$', '$1')
if ($version -lt 15) { throw "Se requiere PostgreSQL 15 o superior (encontrado: $version)." }

$env:PGHOST = "localhost"
$env:PGPORT = "$Puerto"
$env:PGUSER = "postgres"
$env:PGCLIENTENCODING = "UTF8"
$env:PGPASSWORD = Texto (Read-Host "Contraseña del usuario 'postgres' (la definida al instalar PostgreSQL)" -AsSecureString)

& $psql -X -q -d postgres -c "SELECT 1" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "No se pudo conectar como 'postgres'. Verifique la contraseña." }

function Psql([string[]]$argumentos) {
    & $psql -X -q -v ON_ERROR_STOP=1 @argumentos
    if ($LASTEXITCODE -ne 0) { throw "psql terminó con error ($LASTEXITCODE)." }
}

# ---------------------------------------------------------------- base de datos
Paso "Instalando la base de datos '$BaseDatos'"
$existeBd = (& $psql -X -At -d postgres -c "SELECT 1 FROM pg_database WHERE datname = '$BaseDatos'") -eq "1"
if (-not $existeBd) {
    Psql @("-d", "postgres", "-c", "CREATE DATABASE $BaseDatos ENCODING 'UTF8' TEMPLATE template0")
}
$instalar = $true
if ($existeBd -and (& $psql -X -At -d $BaseDatos -c "SELECT 1 FROM pg_namespace WHERE nspname = 'inventario'") -eq "1") {
    Write-Host "La base '$BaseDatos' ya tiene el sistema instalado." -ForegroundColor Yellow
    $r = Read-Host "¿Reinstalar? ESTO BORRA TODOS LOS DATOS (escriba BORRAR para confirmar, Enter para conservar)"
    $instalar = ($r -eq "BORRAR")
}
if ($instalar) {
    $archivos = "01_esquema.sql", "02_kardex.sql", "03_procedimientos.sql", "04_alertas.sql",
                "05_vistas.sql", "06_usuarios.sql", "07_seguridad.sql"
    if ($Demo) { $archivos += "08_datos_demo.sql" }
    foreach ($a in $archivos) {
        Write-Host "   $a"
        Psql @("-d", $BaseDatos, "-f", (Join-Path $sqlDir $a)) | Out-Null
    }
}

Paso "Configurando el usuario de red app_inventario"
do {
    $c1 = Texto (Read-Host "Contraseña de red para las computadoras cliente (mín. 8 caracteres)" -AsSecureString)
    $c2 = Texto (Read-Host "Repita la contraseña" -AsSecureString)
    if ($c1 -ne $c2) { Write-Host "No coinciden." -ForegroundColor Red }
    elseif ($c1.Length -lt 8) { Write-Host "Demasiado corta." -ForegroundColor Red }
} until ($c1 -eq $c2 -and $c1.Length -ge 8)
# La contraseña viaja por variable de entorno y se cita con %L: no hay inyección SQL.
$env:CLAVE_APP = $c1
"\getenv clave CLAVE_APP`nSELECT format('ALTER ROLE app_inventario LOGIN PASSWORD %L', :'clave') \gexec" |
    & $psql -X -q -v ON_ERROR_STOP=1 -d $BaseDatos | Out-Null
if ($LASTEXITCODE -ne 0) { throw "No se pudo asignar la contraseña de red." }
Remove-Item Env:CLAVE_APP

# ---------------------------------------------------------------- red local
Paso "Habilitando conexiones desde la red local"
$ips = Get-NetIPAddress -AddressFamily IPv4 |
       Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" -and $_.PrefixOrigin -ne "WellKnown" }
if (-not $ips) { throw "No se encontró una dirección IPv4 de red local en este equipo." }

function Red($ip, [int]$prefijo) {
    $b = ([Net.IPAddress]$ip).GetAddressBytes()
    [array]::Reverse($b)
    $n = [BitConverter]::ToUInt32($b, 0)
    $mascara = if ($prefijo -eq 0) { [uint32]0 } else { [uint32]([uint64]0xFFFFFFFF -shl (32 - $prefijo) -band 0xFFFFFFFF) }
    $r = [BitConverter]::GetBytes([uint32]($n -band $mascara))
    [array]::Reverse($r)
    "$(([Net.IPAddress]$r).ToString())/$prefijo"
}

$conf = Join-Path $datos "postgresql.conf"
$contenido = Get-Content $conf -Encoding UTF8
if (-not ($contenido -match "^\s*listen_addresses\s*=\s*'\*'")) {
    Copy-Item $conf "$conf.respaldo" -Force
    $contenido = $contenido | Where-Object { $_ -notmatch "^\s*listen_addresses\s*=" }
    $contenido += "listen_addresses = '*'   # Sistema de Gestion: aceptar conexiones de la LAN"
    # .NET escribe UTF-8 sin BOM (PostgreSQL no admite BOM en sus archivos de configuración).
    [IO.File]::WriteAllLines($conf, [string[]]$contenido)
    Write-Host "postgresql.conf: listen_addresses = '*'"
}

$hba = Join-Path $datos "pg_hba.conf"
$lineasHba = Get-Content $hba -Encoding UTF8
Copy-Item $hba "$hba.respaldo" -Force
foreach ($ip in $ips) {
    $subred = Red $ip.IPAddress $ip.PrefixLength
    $regla = "host    $BaseDatos    app_inventario    $subred    scram-sha-256"
    if (-not ($lineasHba -contains $regla)) {
        [IO.File]::AppendAllText($hba, "`r`n$regla   # Sistema de Gestion (LAN)`r`n")
        Write-Host "pg_hba.conf: permitido $subred"
    }
}

if (-not (Get-NetFirewallRule -DisplayName "PostgreSQL - Sistema de Gestion" -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName "PostgreSQL - Sistema de Gestion" -Direction Inbound -Protocol TCP `
        -LocalPort $Puerto -RemoteAddress LocalSubnet -Action Allow -Profile Any | Out-Null
    Write-Host "Firewall: puerto $Puerto abierto para la subred local"
}

Paso "Reiniciando PostgreSQL"
Restart-Service -Name $servicio.Name -Force
Start-Sleep -Seconds 3

# ---------------------------------------------------------------- resumen
Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host " SERVIDOR LISTO" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host " En ESTA computadora (servidor) configure la aplicación con:"
Write-Host "     Servidor: localhost"
Write-Host " En cada computadora CLIENTE configure la aplicación con:"
foreach ($ip in $ips) { Write-Host "     Servidor: $($ip.IPAddress)   ($($ip.InterfaceAlias))" -ForegroundColor Yellow }
Write-Host "     Puerto: $Puerto   Base de datos: $BaseDatos"
Write-Host "     Usuario de red: app_inventario   Contraseña: la que acaba de definir"
Write-Host ""
Write-Host " Usuario inicial de la aplicación: admin / admin123 (se pedirá cambiarla)"
Write-Host " Recomendación: asigne una IP fija al servidor en su router."
