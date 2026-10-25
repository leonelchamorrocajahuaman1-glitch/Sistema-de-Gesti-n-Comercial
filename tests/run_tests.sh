#!/usr/bin/env bash
# Crea una base temporal, instala el sistema y ejecuta todas las pruebas.
#   tests/run_tests.sh            (requiere createdb/dropdb/psql en PATH)
set -euo pipefail
cd "$(dirname "$0")/.."

DB="inventario_test_$$"
trap 'dropdb --if-exists "$DB" >/dev/null 2>&1 || true' EXIT

createdb "$DB"
./install.sh "$DB" > /dev/null

echo "=== Pruebas funcionales ==="
psql -X -q -v ON_ERROR_STOP=1 -o /dev/null -d "$DB" -f tests/test_inventario.sql 2>&1 \
  | sed -e 's/^psql:[^ ]* NOTICE:  /  /'

echo
echo "=== Pruebas de concurrencia ==="
tests/test_concurrencia.sh "$DB"
