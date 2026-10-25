#!/usr/bin/env bash
# Instala el esquema "inventario" en la base indicada.
#   ./install.sh [basedatos] [--demo]
# Variables estándar de libpq (PGHOST, PGPORT, PGUSER, PGPASSWORD) son respetadas.
set -euo pipefail
cd "$(dirname "$0")"

DB="${1:-inventario}"
DEMO="${2:-}"

for f in sql/01_esquema.sql sql/02_kardex.sql sql/03_procedimientos.sql \
         sql/04_alertas.sql sql/05_vistas.sql; do
  echo ">> $f"
  psql -X -q -v ON_ERROR_STOP=1 -d "$DB" -f "$f"
done

if [[ "$DEMO" == "--demo" ]]; then
  echo ">> sql/06_datos_demo.sql"
  psql -X -q -v ON_ERROR_STOP=1 -d "$DB" -f sql/06_datos_demo.sql
fi
echo "Instalación completada en la base \"$DB\"."
