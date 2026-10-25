#!/usr/bin/env bash
# Ejecutado una sola vez por la imagen oficial de postgres al crear el volumen.
set -euo pipefail
for f in /sql/01_esquema.sql /sql/02_kardex.sql /sql/03_procedimientos.sql \
         /sql/04_alertas.sql /sql/05_vistas.sql /sql/06_datos_demo.sql; do
  psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f "$f"
done
psql -X -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "ALTER DATABASE \"$POSTGRES_DB\" SET search_path = inventario, public"
