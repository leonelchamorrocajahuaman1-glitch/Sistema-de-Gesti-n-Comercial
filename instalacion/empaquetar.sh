#!/usr/bin/env bash
# Genera dist/SistemaGestion-Windows.zip: todo lo necesario para instalar en Windows
# (programa, scripts SQL e instalador). Requiere haber compilado: cd aplicacion && mvn package
set -euo pipefail
cd "$(dirname "$0")/.."
JAR=aplicacion/target/sistema-gestion.jar
[[ -f $JAR ]] || { echo "Falta $JAR (ejecute: cd aplicacion && mvn package)"; exit 1; }

TMP=$(mktemp -d)
P="$TMP/SistemaGestion"
mkdir -p "$P/sql" "$P/instalacion"
cp "$JAR" "$P/"
cp sql/0*.sql "$P/sql/"
python3 - "$P" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
def copiar(origen, destino, bom=False):
    texto = pathlib.Path(origen).read_text(encoding='utf-8-sig').replace('\r\n', '\n').replace('\n', '\r\n')
    (p / destino).write_text(texto, encoding='utf-8-sig' if bom else 'utf-8', newline='')
copiar('instalacion/windows/INSTALAR.bat', 'INSTALAR.bat')
copiar('instalacion/windows/EJECUTAR_SISTEMA.bat', 'EJECUTAR_SISTEMA.bat')
# PowerShell 5.1 necesita BOM para leer las tildes correctamente.
copiar('instalacion/windows/instalar_servidor.ps1', 'instalacion/instalar_servidor.ps1', bom=True)
(p / 'LEAME.txt').write_text(
    "SISTEMA DE GESTION COMERCIAL\r\n\r\n"
    "Requisito: tener instalado PostgreSQL (https://www.postgresql.org/download/windows/).\r\n\r\n"
    "1. Haga doble clic en INSTALAR.bat y acepte los permisos de administrador.\r\n"
    "2. Escriba la contrasena de 'postgres' que eligio al instalar PostgreSQL.\r\n"
    "3. Al terminar se abre el sistema. Usuario: admin  Contrasena: admin123\r\n\r\n"
    "Despues use el icono 'Sistema de Gestion' del escritorio.\r\n"
    "Para otras computadoras de la red copie la carpeta PARA_COMPUTADORAS_CLIENTE.\r\n",
    encoding='utf-8', newline='')
PY
mkdir -p dist
rm -f dist/SistemaGestion-Windows.zip
(cd "$TMP" && python3 -m zipfile -c "$OLDPWD/dist/SistemaGestion-Windows.zip" SistemaGestion)
rm -rf "$TMP"
echo "Paquete generado: dist/SistemaGestion-Windows.zip"
