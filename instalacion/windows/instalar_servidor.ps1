<#
  Sistema de Gestión Comercial - instalación automática de la COMPUTADORA SERVIDOR (Windows)

  Requisito: PostgreSQL 15 o superior instalado (https://www.postgresql.org/download/windows/).
  Java se instala automáticamente si falta (mediante winget).
  Se ejecuta con INSTALAR.bat (pide permisos de administrador solo).

  Lo único que se pregunta es la contraseña de 'postgres' elegida al instalar PostgreSQL.
  Todo lo demás es automático:
    1. Crea la base de datos "inventario" con tablas, triggers y procedimientos.
    2. Genera la contraseña de red del usuario app_inventario y la guarda en la
       configuración de la aplicación (conexion.properties junto al .jar).
    3. Configura PostgreSQL y el Firewall para aceptar conexiones de la red local.
    4. Crea el acceso directo "Sistema de Gestión" en el escritorio.
    5. Prepara la carpeta PARA_COMPUTADORAS_CLIENTE lista para copiar a otras PC.
    6. Abre el sistema.
#>
param(
    [string]$BaseDatos = "inventario",
    [int]$Puerto = 5432,
    [switch]$Demo
)
$ErrorActionPreference = "Stop"

function Paso($texto) { Write-Host "`n==> $texto" -ForegroundColor Cyan }
# Windows PowerShell 5.1 convierte en error fatal cualquier línea que un programa externo
# (psql, java) escriba en stderr cuando está activo "Stop" y se redirige 2>: los avisos
# NOTICE de psql o una contraseña incorrecta abortarían la instalación. Se ejecutan aquí
# con "Continue" y el resultado se juzga por $LASTEXITCODE.
function Nativo([scriptblock]$bloque) {
    $ErrorActionPreference = "Continue"
    & $bloque
}
function Texto([Security.SecureString]$s) {
    [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s))
}
function ClaveAleatoria {
    $chars = [char[]]"ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
    $bytes = New-Object byte[] 20
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}
function LeerPropiedades($archivo) {
    $p = @{}
    if (Test-Path $archivo) {
        foreach ($l in Get-Content $archivo) {
            if ($l -match '^\s*([^#=]+?)\s*=\s*(.*)$') { $p[$Matches[1]] = $Matches[2] }
        }
    }
    $p
}
function EscribirPropiedades($archivo, $hostServidor, $clave) {
    $texto = "# Sistema de Gestion - conexion al servidor (generado por el instalador)`r`n" +
             "host=$hostServidor`r`npuerto=$Puerto`r`nbase_datos=$BaseDatos`r`n" +
             "usuario=app_inventario`r`nclave=$clave`r`n"
    [IO.File]::WriteAllText($archivo, $texto)
}

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Ejecute INSTALAR.bat (o este script como administrador)."
}

# Carpeta raíz: paquete de descarga (instalacion\) o repositorio (instalacion\windows\).
$raiz = @((Join-Path $PSScriptRoot ".."), (Join-Path $PSScriptRoot "..\..")) |
        Where-Object { Test-Path (Join-Path $_ "sql\01_esquema.sql") } | Select-Object -First 1
if (-not $raiz) { throw "No se encontró la carpeta 'sql'. Descomprima el paquete completo." }
$raiz = (Resolve-Path $raiz).Path
try { Start-Transcript -Path (Join-Path $raiz "instalacion.log") -Force | Out-Null } catch { }
trap {
    Write-Host "`nERROR: $_" -ForegroundColor Red
    Write-Host "Envíe una foto de esta ventana o el archivo instalacion.log para recibir ayuda." -ForegroundColor Yellow
    try { Stop-Transcript | Out-Null } catch { }
    exit 1
}
$sqlDir = Join-Path $raiz "sql"
# Paquete de descarga: el .jar está en la raíz; repositorio: aplicacion\target tras compilar.
$jar = @((Join-Path $raiz "sistema-gestion.jar"), (Join-Path $raiz "aplicacion\target\sistema-gestion.jar")) |
       Where-Object { Test-Path $_ } | Select-Object -First 1

# ---------------------------------------------------------------- Java
Paso "Verificando Java"
function VersionJava($javaw) {
    # "java version "1.8.0_381"" -> 8 ; "openjdk version "17.0.8"" -> 17
    $java = Join-Path (Split-Path $javaw) "java.exe"
    if (-not (Test-Path $java)) { return 0 }
    $salida = (Nativo { & $java -version 2>&1 }) | ForEach-Object { "$_" }
    if (($salida -join " ") -match 'version "(\d+)(?:\.(\d+))?') {
        $mayor = [int]$Matches[1]
        if ($mayor -eq 1 -and $Matches[2]) { $mayor = [int]$Matches[2] }
        return $mayor
    }
    0
}
function BuscarJavaw {
    # Muchas PC tienen Java 8 en el PATH: la aplicación necesita Java 17 o superior.
    $rutas = "C:\Program Files\Eclipse Adoptium\*\bin\javaw.exe", "C:\Program Files\Java\*\bin\javaw.exe",
             "C:\Program Files\Microsoft\jdk*\bin\javaw.exe", "C:\Program Files\Zulu\*\bin\javaw.exe"
    $candidatos = @(Get-ChildItem $rutas -ErrorAction SilentlyContinue | Sort-Object FullName -Descending |
                    ForEach-Object { $_.FullName })
    $c = Get-Command javaw.exe -ErrorAction SilentlyContinue
    if ($c) { $candidatos = @($c.Source) + $candidatos }
    foreach ($j in $candidatos) {
        $v = VersionJava $j
        if ($v -ge 17) { return $j }
        Write-Host "   (se ignora Java $v en ${j}; se necesita 17 o superior)"
    }
}
$javaw = BuscarJavaw
if (-not $javaw) {
    if (Get-Command winget -ErrorAction SilentlyContinue) {
        Write-Host "No hay Java 17 o superior. Instalando Java 21 (Temurin)..."
        winget install --id EclipseAdoptium.Temurin.21.JRE -e --silent --accept-package-agreements --accept-source-agreements
        $javaw = BuscarJavaw
    }
    if (-not $javaw) { throw "Instale Java 17 o superior desde https://adoptium.net y vuelva a ejecutar INSTALAR.bat." }
}
Write-Host "Java: $javaw"

# ---------------------------------------------------------------- PostgreSQL
Paso "Buscando PostgreSQL"
$servicio = Get-CimInstance Win32_Service | Where-Object { $_.Name -like "postgresql*" } |
            Sort-Object Name -Descending | Select-Object -First 1
if (-not $servicio) {
    throw "No se encontró PostgreSQL. Instálelo desde https://www.postgresql.org/download/windows/ y vuelva a ejecutar."
}
if ($servicio.State -ne "Running") { Start-Service -Name $servicio.Name }
$ruta = $servicio.PathName
$pgCtl = if ($ruta -match '^"([^"]+)"') { $Matches[1] } else { ($ruta -split ' ')[0] }
$bin = Split-Path $pgCtl
$datos = if ($ruta -match '-D\s+"([^"]+)"') { $Matches[1] } elseif ($ruta -match '-D\s+(\S+)') { $Matches[1] }
$psql = Join-Path $bin "psql.exe"
Write-Host "Servicio: $($servicio.Name)   Datos: $datos"
# Si hubo otra versión de PostgreSQL instalada, el instalador oficial elige 5433, 5434...
if (-not $PSBoundParameters.ContainsKey("Puerto")) {
    $lineaPuerto = Get-Content (Join-Path $datos "postgresql.conf") -ErrorAction SilentlyContinue |
                   Where-Object { $_ -match '^\s*port\s*=\s*(\d+)' } | Select-Object -Last 1
    if ($lineaPuerto -match '^\s*port\s*=\s*(\d+)') { $Puerto = [int]$Matches[1] }
}
Write-Host "Puerto: $Puerto"

$version = [int]((& $psql --version) -replace '^\D*(\d+).*$', '$1')
if ($version -lt 15) { throw "Se requiere PostgreSQL 15 o superior (encontrado: $version)." }

$env:PGHOST = "localhost"
$env:PGPORT = "$Puerto"
$env:PGUSER = "postgres"
$env:PGCLIENTENCODING = "UTF8"
for ($intento = 1; ; $intento++) {
    $env:PGPASSWORD = Texto (Read-Host "Escriba la contraseña de 'postgres' (la que eligió al instalar PostgreSQL)" -AsSecureString)
    $errorPsql = (Nativo { & $psql -X -q -d postgres -c "SELECT 1" 2>&1 }) | ForEach-Object { "$_" }
    if ($LASTEXITCODE -eq 0) { break }
    if (-not ($errorPsql -match "password|contrase")) {
        # No es la contraseña: servicio detenido, puerto equivocado, pg_hba.conf, etc.
        throw "No se pudo conectar a PostgreSQL en el puerto ${Puerto}: $($errorPsql -join ' ')"
    }
    if ($intento -ge 3) { throw "No se pudo conectar como 'postgres'. Verifique la contraseña." }
    Write-Host "Contraseña incorrecta, intente de nuevo." -ForegroundColor Red
}

function Psql([string[]]$argumentos) {
    Nativo { & $psql -X -q -v ON_ERROR_STOP=1 @argumentos }
    if ($LASTEXITCODE -ne 0) { throw "psql terminó con error ($LASTEXITCODE)." }
}
function Consulta([string]$bd, [string]$sql) {
    Nativo { & $psql -X -At -d $bd -c $sql }
}
# Oculta los avisos NOTICE de los scripts (los errores se siguen mostrando).
$env:PGOPTIONS = "-c client_min_messages=warning"

# ---------------------------------------------------------------- base de datos
Paso "Instalando la base de datos '$BaseDatos'"
$existeBd = (Consulta "postgres" "SELECT 1 FROM pg_database WHERE datname = '$BaseDatos'") -eq "1"
if (-not $existeBd) {
    Psql @("-d", "postgres", "-c", "CREATE DATABASE $BaseDatos ENCODING 'UTF8' TEMPLATE template0")
}
$instalar = $true
if ($existeBd -and (Consulta $BaseDatos "SELECT 1 FROM pg_namespace WHERE nspname = 'inventario'") -eq "1") {
    Write-Host "La base '$BaseDatos' ya tiene el sistema instalado; se conservan sus datos." -ForegroundColor Yellow
    $r = Read-Host "Para BORRAR todo y reinstalar escriba BORRAR; para conservar pulse Enter"
    $instalar = ($r -eq "BORRAR")
}
if ($instalar) {
    if (-not $Demo) {
        $Demo = (Read-Host "¿Cargar productos y usuarios de ejemplo para probar? (S/N)") -match '^[sS]'
    }
    $archivos = "01_esquema.sql", "02_kardex.sql", "03_procedimientos.sql", "04_alertas.sql",
                "05_vistas.sql", "06_usuarios.sql", "07_seguridad.sql"
    if ($Demo) { $archivos += "08_datos_demo.sql" }
    foreach ($a in $archivos) {
        Write-Host "   $a"
        Psql @("-d", $BaseDatos, "-f", (Join-Path $sqlDir $a)) | Out-Null
    }
}

Paso "Configurando el usuario de red app_inventario"
$configServidor = Join-Path $raiz "conexion.properties"
$clave = (LeerPropiedades $configServidor)["clave"]
if (-not $clave) { $clave = ClaveAleatoria }
# La contraseña viaja por variable de entorno y se cita con %L: no hay inyección SQL.
$env:CLAVE_APP = $clave
"\getenv clave CLAVE_APP`nSELECT format('ALTER ROLE app_inventario LOGIN PASSWORD %L', :'clave') \gexec" |
    & $psql -X -q -v ON_ERROR_STOP=1 -d $BaseDatos | Out-Null
if ($LASTEXITCODE -ne 0) { throw "No se pudo asignar la contraseña de red." }
Remove-Item Env:CLAVE_APP
EscribirPropiedades $configServidor "localhost" $clave
# También en el perfil del usuario: así funciona al ejecutar desde Visual Studio Code.
$configUsuario = Join-Path $env:USERPROFILE ".sistema-gestion\conexion.properties"
New-Item -ItemType Directory -Force (Split-Path $configUsuario) | Out-Null
EscribirPropiedades $configUsuario "localhost" $clave
Write-Host "Configuración guardada en $configServidor y $configUsuario"

# ---------------------------------------------------------------- red local
Paso "Habilitando conexiones desde la red local"
$ips = @(Get-NetIPAddress -AddressFamily IPv4 |
       Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" -and $_.PrefixOrigin -ne "WellKnown" })
# La IP que se entrega a los clientes debe ser la de la tarjeta conectada al router (la que
# tiene puerta de enlace), no la de adaptadores virtuales (VirtualBox, Hyper-V, VPN).
$conRouter = @(Get-NetIPConfiguration -ErrorAction SilentlyContinue |
               Where-Object { $_.IPv4DefaultGateway } | ForEach-Object { $_.InterfaceIndex })
$ips = @($ips | Sort-Object { if ($conRouter -contains $_.InterfaceIndex) { 0 } else { 1 } })

function Red($ip, [int]$prefijo) {
    # Se aplica la máscara byte por byte: en PowerShell 0xFFFFFFFF vale -1 (Int32) y las
    # operaciones de 32 bits sin signo fallan.
    $b = ([Net.IPAddress]$ip).GetAddressBytes()
    for ($i = 0; $i -lt 4; $i++) {
        $bits = [Math]::Max(0, [Math]::Min(8, $prefijo - 8 * $i))
        $b[$i] = $b[$i] -band ((0xFF00 -shr $bits) -band 0xFF)
    }
    "$([Net.IPAddress]::new([byte[]]$b).ToString())/$prefijo"
}

if ($ips.Count -gt 0) {
    $conf = Join-Path $datos "postgresql.conf"
    $contenido = Get-Content $conf -Encoding UTF8
    if (-not ($contenido -match "^\s*listen_addresses\s*=\s*'\*'")) {
        # El respaldo guarda el archivo ORIGINAL: no se pisa al reinstalar.
        if (-not (Test-Path "$conf.respaldo")) { Copy-Item $conf "$conf.respaldo" }
        $contenido = $contenido | Where-Object { $_ -notmatch "^\s*listen_addresses\s*=" }
        $contenido += "listen_addresses = '*'   # Sistema de Gestion: aceptar conexiones de la LAN"
        # .NET escribe UTF-8 sin BOM (PostgreSQL no admite BOM en sus archivos de configuración).
        [IO.File]::WriteAllLines($conf, [string[]]$contenido)
    }

    $hba = Join-Path $datos "pg_hba.conf"
    $lineasHba = Get-Content $hba -Encoding UTF8
    if (-not (Test-Path "$hba.respaldo")) { Copy-Item $hba "$hba.respaldo" }
    foreach ($ip in $ips) {
        $subred = Red $ip.IPAddress $ip.PrefixLength
        $regla = "host    $BaseDatos    app_inventario    $subred    scram-sha-256"
        # La línea guardada lleva un comentario al final: se compara solo el comienzo.
        if (-not ($lineasHba | Where-Object { $_.Trim().StartsWith($regla) })) {
            [IO.File]::AppendAllText($hba, "`r`n$regla   # Sistema de Gestion (LAN)`r`n")
            Write-Host "Red local permitida: $subred"
        }
    }

    if (-not (Get-NetFirewallRule -DisplayName "PostgreSQL - Sistema de Gestion" -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -DisplayName "PostgreSQL - Sistema de Gestion" -Direction Inbound -Protocol TCP `
            -LocalPort $Puerto -RemoteAddress LocalSubnet -Action Allow -Profile Any | Out-Null
        Write-Host "Firewall: puerto $Puerto abierto para la red local"
    }

    Restart-Service -Name $servicio.Name -Force
    Start-Sleep -Seconds 3
} else {
    Write-Host "Este equipo no está conectado a una red: el sistema funcionará solo en esta computadora." -ForegroundColor Yellow
}

# ---------------------------------------------------------------- aplicación
Paso "Instalando la aplicación"
if (-not $jar) {
    Write-Host "`nLa base de datos quedó instalada." -ForegroundColor Green
    Write-Host "Falta compilar el programa: en Visual Studio Code use Terminal > Ejecutar tarea >"
    Write-Host "'Compilar programa', o ejecute F5 para abrirlo directamente."
    try { Stop-Transcript | Out-Null } catch { }
    exit 0
}
$jarDir = Split-Path $jar
# Repositorio (aplicacion\target): la configuración también junto al .jar, para que el icono
# funcione con otras cuentas de Windows que no tienen el archivo en su perfil.
if ($jarDir -ne $raiz) { EscribirPropiedades (Join-Path $jarDir "conexion.properties") "localhost" $clave }
$escritorio = [Environment]::GetFolderPath("CommonDesktopDirectory")
$ws = New-Object -ComObject WScript.Shell
$acceso = $ws.CreateShortcut((Join-Path $escritorio "Sistema de Gestión.lnk"))
$acceso.TargetPath = $javaw
$acceso.Arguments = "-jar `"$jar`""
$acceso.WorkingDirectory = $jarDir
$acceso.IconLocation = "$javaw,0"
$acceso.Save()
Write-Host "Acceso directo creado en el escritorio: 'Sistema de Gestión'"

if ($ips.Count -gt 0) {
    $clientes = Join-Path $raiz "PARA_COMPUTADORAS_CLIENTE"
    New-Item -ItemType Directory -Force $clientes | Out-Null
    Copy-Item $jar $clientes -Force
    @((Join-Path $raiz "EJECUTAR_SISTEMA.bat"), (Join-Path $raiz "instalacion\windows\EJECUTAR_SISTEMA.bat")) |
        Where-Object { Test-Path $_ } | Select-Object -First 1 | ForEach-Object { Copy-Item $_ $clientes -Force }
    EscribirPropiedades (Join-Path $clientes "conexion.properties") $ips[0].IPAddress $clave
}

# ---------------------------------------------------------------- resumen
Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host " INSTALACIÓN COMPLETA" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host " Abra el sistema con el icono 'Sistema de Gestión' del escritorio."
Write-Host " Usuario: admin    Contraseña: admin123   (le pedirá cambiarla)"
if ($ips.Count -gt 0) {
    Write-Host ""
    Write-Host " Para usarlo en OTRAS computadoras de la red:"
    Write-Host "   1. Copie la carpeta PARA_COMPUTADORAS_CLIENTE (por USB o red)."
    Write-Host "   2. En la otra PC haga doble clic en EJECUTAR_SISTEMA.bat (necesita Java)."
    Write-Host "   IP de este servidor: $($ips[0].IPAddress)  (fíjela en el router para que no cambie)" -ForegroundColor Yellow
}
Start-Process -FilePath $javaw -ArgumentList "-jar `"$jar`"" -WorkingDirectory $jarDir
try { Stop-Transcript | Out-Null } catch { }
