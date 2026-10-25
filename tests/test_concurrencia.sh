#!/usr/bin/env bash
# Pruebas de concurrencia sobre una base ya instalada (vacía de movimientos).
#  1. Sobreventa: N sesiones simultáneas intentan vender más de lo que hay.
#  2. Transferencias cruzadas A→B y B→A simultáneas: sin interbloqueos.
set -euo pipefail
DB="$1"
q() { psql -X -q -At -v ON_ERROR_STOP=1 -d "$DB" -c "SET search_path = inventario" -c "$1"; }

q "INSERT INTO almacen (codigo, nombre) VALUES ('C1','Concurrencia 1'), ('C2','Concurrencia 2');
   INSERT INTO producto (codigo, nombre, metodo_valuacion) VALUES ('CONC', 'Concurrencia', 'PEPS');"
C1=$(q "SELECT id FROM almacen WHERE codigo = 'C1'")
C2=$(q "SELECT id FROM almacen WHERE codigo = 'C2'")

q "CALL sp_registrar_entrada('COMPRA', $C1, '[{\"producto\":\"CONC\",\"cantidad\":100,\"costo_unitario\":1}]')" >/dev/null

# --- 1. 40 ventas concurrentes de 5 unidades sobre un stock de 100 -----------
TMP=$(mktemp -d "${TMPDIR:-/tmp}/inv_conc.XXXXXX")
for i in $(seq 1 40); do
  ( if psql -X -q -At -d "$DB" -c "CALL inventario.sp_registrar_salida('VENTA', $C1,
        '[{\"producto\":\"CONC\",\"cantidad\":5}]', 'V-$i')" >/dev/null 2>"$TMP/err$i"; then
      touch "$TMP/ok$i"; fi ) &
done
wait
OK=$(ls "$TMP" | grep -c '^ok' || true)
STOCK=$(q "SELECT cantidad::int FROM stock WHERE almacen_id = $C1 AND producto_id = fn_producto_id('CONC')")
OTHER=$(cat "$TMP"/err* | grep -v 'Stock insuficiente' | grep -c ERROR || true)
echo "  ventas aceptadas: $OK / 40, stock final: $STOCK, errores ajenos al stock: $OTHER"
[[ "$OK" == 20 && "$STOCK" == 0 && "$OTHER" == 0 ]] || { echo "FALLO: sobreventa concurrente"; exit 1; }
echo "  ok: exactamente 20 ventas aceptadas, nunca stock negativo"

# --- 2. Transferencias cruzadas simultáneas ---------------------------------
q "CALL sp_registrar_entrada('COMPRA', $C1, '[{\"producto\":\"CONC\",\"cantidad\":500,\"costo_unitario\":2}]');
   CALL sp_registrar_entrada('COMPRA', $C2, '[{\"producto\":\"CONC\",\"cantidad\":500,\"costo_unitario\":3}]');" >/dev/null
rm -f "$TMP"/*
for i in $(seq 1 30); do
  if (( i % 2 )); then O=$C1; D=$C2; else O=$C2; D=$C1; fi
  ( psql -X -q -At -v ON_ERROR_STOP=1 -d "$DB" -c "CALL inventario.sp_transferir($O, $D,
        '[{\"producto\":\"CONC\",\"cantidad\":7}]', 'T-$i')" >/dev/null 2>"$TMP/err$i" \
      || touch "$TMP/fail$i" ) &
done
wait
FAIL=$(ls "$TMP" | grep -c '^fail' || true)
DEADLOCK=$(cat "$TMP"/err* | grep -ci deadlock || true)
TOTAL=$(q "SELECT sum(cantidad)::int || '/' || sum(valor_total) FROM stock WHERE producto_id = fn_producto_id('CONC')")
echo "  transferencias fallidas: $FAIL, interbloqueos: $DEADLOCK, total unidades/valor: $TOTAL"
[[ "$FAIL" == 0 && "$DEADLOCK" == 0 && "$TOTAL" == "1000/2500.0000" ]] \
  || { echo "FALLO: transferencias concurrentes"; cat "$TMP"/err*; exit 1; }
echo "  ok: 30 transferencias cruzadas sin interbloqueos y sin pérdida de valor"

N=$(q "SELECT count(*) FROM fn_verificar_integridad()")
[[ "$N" == 0 ]] || { echo "FALLO: integridad"; q "SELECT * FROM fn_verificar_integridad()"; exit 1; }
echo "  ok: verificación de integridad sin inconsistencias"
rm -rf "$TMP"
echo "TODAS LAS PRUEBAS DE CONCURRENCIA PASARON"
