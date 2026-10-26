#!/usr/bin/env bash
# Habilita conexiones de la red local a PostgreSQL en un servidor Linux (Debian/Ubuntu).
#   sudo instalacion/linux/habilitar_red.sh 192.168.1.0/24 [basedatos]
# Requiere haber instalado antes la base con:  ./install.sh inventario --clave-app 'CLAVE'
set -euo pipefail
SUBRED="${1:?Indique la subred de la LAN, por ejemplo 192.168.1.0/24}"
DB="${2:-inventario}"

CONF=$(sudo -u postgres psql -XAtc "SHOW config_file")
HBA=$(sudo -u postgres psql -XAtc "SHOW hba_file")

cp "$CONF" "$CONF.respaldo"
cp "$HBA" "$HBA.respaldo"
sed -i -E "s/^[#[:space:]]*listen_addresses[[:space:]]*=.*/listen_addresses = '*'   # Sistema de Gestion (LAN)/" "$CONF"
grep -q "^listen_addresses = '\*'" "$CONF" || echo "listen_addresses = '*'" >> "$CONF"

REGLA="host    $DB    app_inventario    $SUBRED    scram-sha-256"
grep -qxF "$REGLA" "$HBA" || echo "$REGLA" >> "$HBA"

if command -v ufw >/dev/null && ufw status | grep -q active; then
  ufw allow from "$SUBRED" to any port 5432 proto tcp
fi

systemctl restart postgresql 2>/dev/null || service postgresql restart
echo "Listo. Los clientes deben usar como servidor alguna de estas IP:"
hostname -I
