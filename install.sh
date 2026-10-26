#!/usr/bin/env bash
# Instala el esquema "inventario" en la base indicada (en la computadora servidor).
#   ./install.sh [basedatos] [--demo] [--clave-app CLAVE]
#
#   --demo           carga datos de demostración
#   --clave-app X    habilita el rol de red app_inventario con la contraseña X
#                    (es el usuario con el que se conectan las aplicaciones Java)
# Variables estándar de libpq (PGHOST, PGPORT, PGUSER, PGPASSWORD) son respetadas.
set -euo pipefail
cd "$(dirname "$0")"

DB="inventario"
DEMO=0
CLAVE_APP=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --demo)      DEMO=1 ;;
    --clave-app) CLAVE_APP="$2"; shift ;;
    *)           DB="$1" ;;
  esac
  shift
done

for f in sql/01_esquema.sql sql/02_kardex.sql sql/03_procedimientos.sql \
         sql/04_alertas.sql sql/05_vistas.sql sql/06_usuarios.sql sql/07_seguridad.sql; do
  echo ">> $f"
  psql -X -q -v ON_ERROR_STOP=1 -d "$DB" -f "$f"
done

if [[ $DEMO == 1 ]]; then
  echo ">> sql/08_datos_demo.sql"
  psql -X -q -v ON_ERROR_STOP=1 -d "$DB" -f sql/08_datos_demo.sql
fi

if [[ -n "$CLAVE_APP" ]]; then
  echo ">> habilitando rol de red app_inventario"
  # La contraseña se pasa como variable de psql y se cita con %L (sin inyección).
  psql -X -q -v ON_ERROR_STOP=1 -d "$DB" -v clave="$CLAVE_APP" >/dev/null <<'SQL'
SELECT format('ALTER ROLE app_inventario LOGIN PASSWORD %L', :'clave') \gexec
SQL
fi
echo "Instalación completada en la base \"$DB\"."
