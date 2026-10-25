-- =============================================================================
--  Pruebas funcionales del sistema de inventario.
--  Se ejecutan sobre una base recién instalada (ver tests/run_tests.sh).
--  Cualquier aserción fallida aborta la ejecución con error.
-- =============================================================================
\set ON_ERROR_STOP 1
SET client_min_messages = warning;
SET search_path = inventario, public;

-- -----------------------------------------------------------------------------
--  Utilidades de prueba
-- -----------------------------------------------------------------------------
CREATE SCHEMA pruebas;

CREATE FUNCTION pruebas.igual(p_actual anyelement, p_esperado anyelement, p_msg text)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF p_actual IS DISTINCT FROM p_esperado THEN
        RAISE EXCEPTION 'FALLO: % — esperado <%>, obtenido <%>', p_msg, p_esperado, p_actual;
    END IF;
    RAISE NOTICE 'ok: %', p_msg;
END;
$$;

-- Ejecuta p_sql y exige que falle con un mensaje que contenga p_patron.
-- El bloque EXCEPTION crea un subtransacción: nada del intento queda aplicado.
CREATE FUNCTION pruebas.falla(p_sql text, p_patron text, p_msg text)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE p_sql;
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM ILIKE '%' || p_patron || '%' THEN
            RAISE NOTICE 'ok: % (rechazado: %)', p_msg, SQLERRM;
            RETURN;
        END IF;
        RAISE EXCEPTION 'FALLO: % — error inesperado: %', p_msg, SQLERRM;
    END;
    RAISE EXCEPTION 'FALLO: % — la operación debía ser rechazada', p_msg;
END;
$$;

CREATE FUNCTION pruebas.stock(p_almacen integer, p_producto text)
RETURNS stock LANGUAGE sql AS $$
    SELECT * FROM inventario.stock
     WHERE almacen_id = p_almacen AND producto_id = inventario.fn_producto_id(p_producto);
$$;

CREATE FUNCTION pruebas.integro() RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    v_n integer;
BEGIN
    SELECT count(*) INTO v_n FROM inventario.fn_verificar_integridad();
    PERFORM pruebas.igual(v_n, 0, 'verificación de integridad sin inconsistencias');
END;
$$;

SET client_min_messages = notice;

-- -----------------------------------------------------------------------------
--  Datos maestros
-- -----------------------------------------------------------------------------
INSERT INTO almacen (codigo, nombre) VALUES ('A1', 'Principal'), ('A2', 'Sucursal'), ('A3', 'Inactivo');
UPDATE almacen SET activo = false WHERE codigo = 'A3';

INSERT INTO producto (codigo, nombre, metodo_valuacion, controla_lote, controla_vencimiento,
                      dias_alerta_vencimiento) VALUES
    ('PROM', 'Producto promedio', 'PROMEDIO', false, false, 30),
    ('PEPS', 'Producto PEPS',     'PEPS',     false, false, 30),
    ('LOTE', 'Perecible PEPS',    'PEPS',     true,  true,  15),
    ('FEFO', 'Perecible promedio','PROMEDIO', true,  true,  15);

-- =============================================================================
\echo '== 1. Promedio ponderado: caso de libro =='
-- =============================================================================
CALL sp_registrar_entrada('COMPRA', 1, '[{"producto":"PROM","cantidad":100,"costo_unitario":10}]',
                          'F-1', '2026-01-02');
CALL sp_registrar_entrada('COMPRA', 1, '[{"producto":"PROM","cantidad":50,"costo_unitario":13}]',
                          'F-2', '2026-01-05');
SELECT pruebas.igual((pruebas.stock(1,'PROM')).costo_promedio, 11.000000::numeric(18,6),
                     'CPP tras dos compras = (1000 + 650) / 150 = 11');

CALL sp_registrar_salida('VENTA', 1, '[{"producto":"PROM","cantidad":120}]', 'B-1', '2026-01-08');
SELECT pruebas.igual(
    (SELECT costo_total FROM kardex WHERE producto_id = fn_producto_id('PROM') ORDER BY id DESC LIMIT 1),
    1320.0000::numeric(18,4), 'costo de venta 120 × 11 = 1320');
SELECT pruebas.igual((pruebas.stock(1,'PROM')).valor_total, 330.0000::numeric(18,4),
                     'saldo valorizado 30 × 11 = 330');

CALL sp_registrar_entrada('COMPRA', 1, '[{"producto":"PROM","cantidad":70,"costo_unitario":14}]',
                          'F-3', '2026-01-10');
SELECT pruebas.igual((pruebas.stock(1,'PROM')).costo_promedio, 13.100000::numeric(18,6),
                     'nuevo CPP = (330 + 980) / 100 = 13.10');

CALL sp_registrar_salida('VENTA', 1, '[{"producto":"PROM","cantidad":100}]', 'B-2', '2026-01-12');
SELECT pruebas.igual((pruebas.stock(1,'PROM')).cantidad, 0::numeric(14,4), 'stock agotado');
SELECT pruebas.igual((pruebas.stock(1,'PROM')).valor_total, 0::numeric(18,4),
                     'al agotarse el valor queda en cero (sin residuo)');
SELECT pruebas.igual(
    (SELECT costo_total FROM kardex WHERE producto_id = fn_producto_id('PROM') ORDER BY id DESC LIMIT 1),
    1310.0000::numeric(18,4), 'última salida se lleva todo el valor: 1310');

-- Promedio con decimales periódicos: 3 unidades por 10.00 → CPP 3.333333
CALL sp_registrar_entrada('COMPRA', 1, '[{"producto":"PROM","cantidad":3,"costo_unitario":3.333333}]',
                          'F-4', '2026-01-13');
CALL sp_registrar_entrada('COMPRA', 1, '[{"producto":"PROM","cantidad":3,"costo_unitario":3.333334}]',
                          'F-5', '2026-01-13');
CALL sp_registrar_salida('CONSUMO', 1, '[{"producto":"PROM","cantidad":1}]', NULL, '2026-01-13');
CALL sp_registrar_salida('CONSUMO', 1, '[{"producto":"PROM","cantidad":5}]', NULL, '2026-01-13');
SELECT pruebas.igual((pruebas.stock(1,'PROM')).valor_total, 0::numeric(18,4),
                     'sin residuos de redondeo tras consumir decimales periódicos');
SELECT pruebas.integro();

-- =============================================================================
\echo '== 2. PEPS: caso de libro =='
-- =============================================================================
CALL sp_registrar_entrada('COMPRA', 1, '[{"producto":"PEPS","cantidad":100,"costo_unitario":10}]',
                          'F-10', '2026-01-02');
CALL sp_registrar_entrada('COMPRA', 1, '[{"producto":"PEPS","cantidad":50,"costo_unitario":13}]',
                          'F-11', '2026-01-05');
CALL sp_registrar_salida('VENTA', 1, '[{"producto":"PEPS","cantidad":120}]', 'B-10', '2026-01-08');
SELECT pruebas.igual(
    (SELECT costo_total FROM kardex WHERE producto_id = fn_producto_id('PEPS') ORDER BY id DESC LIMIT 1),
    1260.0000::numeric(18,4), 'salida PEPS = 100 × 10 + 20 × 13 = 1260');
SELECT pruebas.igual(
    (SELECT count(*) FROM consumo_capa cc JOIN kardex k ON k.id = cc.kardex_id
      WHERE k.producto_id = fn_producto_id('PEPS'))::int, 2, 'la salida consumió dos capas');
SELECT pruebas.igual((pruebas.stock(1,'PEPS')).valor_total, 390.0000::numeric(18,4),
                     'saldo PEPS 30 × 13 = 390');

CALL sp_registrar_entrada('COMPRA', 1, '[{"producto":"PEPS","cantidad":70,"costo_unitario":14}]',
                          'F-12', '2026-01-10');
CALL sp_registrar_salida('VENTA', 1, '[{"producto":"PEPS","cantidad":50}]', 'B-11', '2026-01-12');
SELECT pruebas.igual(
    (SELECT costo_total FROM kardex WHERE producto_id = fn_producto_id('PEPS') ORDER BY id DESC LIMIT 1),
    670.0000::numeric(18,4), 'salida PEPS = 30 × 13 + 20 × 14 = 670');
SELECT pruebas.igual((pruebas.stock(1,'PEPS')).valor_total, 700.0000::numeric(18,4),
                     'saldo PEPS 50 × 14 = 700');
SELECT pruebas.integro();

-- =============================================================================
\echo '== 3. Stock negativo imposible bajo cualquier vía =='
-- =============================================================================
SELECT pruebas.falla($$CALL inventario.sp_registrar_salida('VENTA', 1,
        '[{"producto":"PEPS","cantidad":50.0001}]', NULL, '2026-01-20')$$,
    'Stock insuficiente', 'vender más de lo disponible');

-- Documento con varias líneas: si una falla, no se registra ninguna.
SELECT pruebas.falla($$CALL inventario.sp_registrar_salida('VENTA', 1,
        '[{"producto":"PEPS","cantidad":10},{"producto":"PROM","cantidad":1}]', NULL, '2026-01-20')$$,
    'Stock insuficiente', 'documento multilínea con una línea sin stock');
SELECT pruebas.igual((pruebas.stock(1,'PEPS')).cantidad, 50::numeric(14,4),
                     'atomicidad: la primera línea tampoco se aplicó');

SELECT pruebas.falla($$CALL inventario.sp_registrar_salida('VENTA', 2,
        '[{"producto":"PEPS","cantidad":1}]', NULL, '2026-01-20')$$,
    'Stock insuficiente', 'vender en un almacén sin stock');
SELECT pruebas.falla($$UPDATE inventario.stock SET cantidad = -5 WHERE almacen_id = 1$$,
    'solo puede modificarse mediante movimientos', 'UPDATE directo a stock');
SELECT pruebas.falla($$UPDATE inventario.capa_inventario SET cantidad_disponible = 0$$,
    'solo pueden modificarse mediante movimientos', 'UPDATE directo a capas');
SELECT pruebas.falla($$INSERT INTO inventario.stock (almacen_id, producto_id, cantidad, valor_total)
                       VALUES (2, 1, 999, 10)$$,
    'solo puede modificarse mediante movimientos', 'INSERT de stock ficticio');
SELECT pruebas.falla($$UPDATE inventario.kardex SET cantidad = 1$$, 'inmutable', 'UPDATE al kardex');
SELECT pruebas.falla($$DELETE FROM inventario.kardex$$, 'inmutable', 'DELETE al kardex');
SELECT pruebas.falla($$TRUNCATE inventario.kardex, inventario.capa_inventario, inventario.consumo_capa$$, 'inmutable', 'TRUNCATE al kardex');
SELECT pruebas.falla($$DELETE FROM inventario.documento$$, 'no se eliminan', 'DELETE de documentos');
SELECT pruebas.falla($$CALL inventario.sp_registrar_salida('VENTA', 1,
        '[{"producto":"PEPS","cantidad":-3}]', NULL, '2026-01-20')$$,
    'mayor que cero', 'cantidad negativa');
SELECT pruebas.falla($$CALL inventario.sp_registrar_entrada('COMPRA', 1,
        '[{"producto":"PEPS","cantidad":3,"costo_unitario":-1}]', NULL, '2026-01-20')$$,
    'Costo unitario inválido', 'costo negativo');
SELECT pruebas.falla($$CALL inventario.sp_registrar_entrada('COMPRA', 3,
        '[{"producto":"PEPS","cantidad":3,"costo_unitario":1}]', NULL, '2026-01-20')$$,
    'inactivo', 'movimiento en almacén inactivo');
SELECT pruebas.falla($$CALL inventario.sp_registrar_entrada('COMPRA', 1,
        '[{"producto":"PEPS","cantidad":3,"costo_unitario":1}]', NULL, '2025-12-31')$$,
    'anterior al último', 'movimiento retroactivo');
SELECT pruebas.falla($$CALL inventario.sp_registrar_entrada('VENTA', 1,
        '[{"producto":"PEPS","cantidad":3,"costo_unitario":1}]')$$,
    'no es de entrada', 'tipo de documento incoherente');
SELECT pruebas.falla($$CALL inventario.sp_registrar_salida('VENTA', 1, '[]')$$,
    'no vacío', 'documento sin ítems');
SELECT pruebas.integro();

-- =============================================================================
\echo '== 4. Lotes, vencimientos y FEFO =='
-- =============================================================================
SELECT pruebas.falla($$CALL inventario.sp_registrar_entrada('COMPRA', 1,
        '[{"producto":"LOTE","cantidad":5,"costo_unitario":1}]', NULL, '2026-02-01')$$,
    'exige lote', 'entrada sin lote de producto con control de lote');
SELECT pruebas.falla($$CALL inventario.sp_registrar_entrada('COMPRA', 1,
        '[{"producto":"LOTE","cantidad":5,"costo_unitario":1,"lote":"X"}]', NULL, '2026-02-01')$$,
    'fecha de vencimiento', 'lote sin vencimiento en producto perecible');
SELECT pruebas.falla($$CALL inventario.sp_registrar_entrada('COMPRA', 1,
        '[{"producto":"PEPS","cantidad":5,"costo_unitario":1,"lote":"X"}]', NULL, '2026-02-01')$$,
    'no controla lotes', 'lote en producto sin control de lote');

-- FEFO (producto promedio): aunque L-TARDE ingresa primero, sale antes L-PRONTO.
CALL sp_registrar_entrada('COMPRA', 1, $$[
    {"producto":"FEFO","cantidad":10,"costo_unitario":2,"lote":"L-TARDE", "fecha_vencimiento":"2026-12-31"},
    {"producto":"FEFO","cantidad":10,"costo_unitario":4,"lote":"L-PRONTO","fecha_vencimiento":"2026-03-15"}
]$$, 'F-20', '2026-02-01');
CALL sp_registrar_salida('VENTA', 1, '[{"producto":"FEFO","cantidad":8}]', 'B-20', '2026-02-02');
SELECT pruebas.igual(
    (SELECT l.codigo FROM kardex k JOIN lote l ON l.id = k.lote_id ORDER BY k.id DESC LIMIT 1)::text,
    'L-PRONTO', 'FEFO: se despacha primero el lote que vence antes');
SELECT pruebas.igual(
    (SELECT costo_total FROM kardex ORDER BY id DESC LIMIT 1), 24.0000::numeric(18,4),
    'FEFO no altera la valorización: 8 × CPP 3 = 24');

SELECT pruebas.igual(
    (SELECT count(*) FROM alerta WHERE tipo = 'LOTE_POR_VENCER' AND NOT resuelta)::int, 0,
    'L-PRONTO aún fuera de la ventana de alerta (15 días)');
CALL sp_generar_alertas_vencimiento('2026-03-05');
SELECT pruebas.igual(
    (SELECT count(*) FROM v_alertas_activas WHERE tipo = 'LOTE_POR_VENCER' AND lote = 'L-PRONTO')::int, 1,
    'alerta de lote por vencer a 10 días');
CALL sp_generar_alertas_vencimiento('2026-03-05');
SELECT pruebas.igual(
    (SELECT count(*) FROM alerta WHERE tipo = 'LOTE_POR_VENCER')::int, 1,
    'el barrido es idempotente (no duplica alertas)');
CALL sp_generar_alertas_vencimiento('2026-03-16');
SELECT pruebas.igual(
    (SELECT tipo::text FROM v_alertas_activas WHERE lote = 'L-PRONTO'), 'LOTE_VENCIDO',
    'la alerta escala a LOTE_VENCIDO y la de "por vencer" se cierra');

-- Lote vencido: no se puede vender ni transferir, sí dar de baja como merma.
SELECT pruebas.falla($$CALL inventario.sp_registrar_salida('VENTA', 1,
        '[{"producto":"FEFO","cantidad":1,"lote":"L-PRONTO"}]', NULL, '2026-03-16')$$,
    'Stock utilizable insuficiente', 'vender un lote vencido');
SELECT pruebas.falla($$CALL inventario.sp_transferir(1, 2,
        '[{"producto":"FEFO","cantidad":1,"lote":"L-PRONTO"}]', NULL, '2026-03-16')$$,
    'Stock utilizable insuficiente', 'transferir un lote vencido');
SELECT pruebas.falla($$CALL inventario.sp_registrar_salida('VENTA', 1,
        '[{"producto":"FEFO","cantidad":11}]', NULL, '2026-03-16')$$,
    'Stock utilizable insuficiente', 'venta sin lote que requeriría tocar el vencido');
CALL sp_registrar_salida('VENTA', 1, '[{"producto":"FEFO","cantidad":10}]', 'B-21', '2026-03-16');
SELECT pruebas.igual(
    (SELECT l.codigo FROM kardex k JOIN lote l ON l.id = k.lote_id ORDER BY k.id DESC LIMIT 1)::text,
    'L-TARDE', 'la venta sin lote salta automáticamente el lote vencido');
CALL sp_registrar_salida('MERMA', 1, '[{"producto":"FEFO","cantidad":2,"lote":"L-PRONTO"}]',
                         'Baja por vencimiento', '2026-03-17');
SELECT pruebas.igual(
    (SELECT count(*) FROM v_alertas_activas WHERE lote = 'L-PRONTO')::int, 0,
    'al retirar todo el lote vencido su alerta se resuelve sola');
SELECT pruebas.integro();

-- =============================================================================
\echo '== 5. Transferencias multialmacén =='
-- =============================================================================
CALL sp_registrar_entrada('COMPRA', 1, $$[
    {"producto":"LOTE","cantidad":30,"costo_unitario":5,"lote":"B-1","fecha_vencimiento":"2027-06-30"},
    {"producto":"LOTE","cantidad":30,"costo_unitario":6,"lote":"B-2","fecha_vencimiento":"2027-09-30"}
]$$, 'F-30', '2026-04-01');
CALL sp_transferir(1, 2, '[{"producto":"LOTE","cantidad":40},{"producto":"PEPS","cantidad":20}]',
                   'GR-1', '2026-04-02');

SELECT pruebas.igual((pruebas.stock(2,'LOTE')).cantidad, 40::numeric(14,4), 'destino recibe 40');
SELECT pruebas.igual((pruebas.stock(2,'LOTE')).valor_total, 210.0000::numeric(18,4),
                     'costo viaja con la mercadería: 30 × 5 + 10 × 6 = 210');
SELECT pruebas.igual(
    (SELECT string_agg(lote || ':' || cantidad::int, ',' ORDER BY lote) FROM v_stock_por_lote
      WHERE almacen = 'A2' AND producto = 'LOTE'), 'B-1:30,B-2:10',
    'el destino conserva lotes y vencimientos');
SELECT pruebas.igual(
    (pruebas.stock(1,'LOTE')).valor_total + (pruebas.stock(2,'LOTE')).valor_total,
    330.0000::numeric(18,4), 'la transferencia no crea ni destruye valor');
SELECT pruebas.igual((pruebas.stock(2,'PEPS')).valor_total, 280.0000::numeric(18,4),
                     'PEPS transferido a costo de capa: 20 × 14');
SELECT pruebas.falla($$CALL inventario.sp_transferir(1, 2,
        '[{"producto":"LOTE","cantidad":21}]', NULL, '2026-04-03')$$,
    'Stock insuficiente', 'transferir más de lo disponible');
SELECT pruebas.falla($$CALL inventario.sp_transferir(1, 1,
        '[{"producto":"LOTE","cantidad":1}]', NULL, '2026-04-03')$$,
    'distintos', 'transferencia al mismo almacén');
SELECT pruebas.integro();

-- =============================================================================
\echo '== 6. Anulaciones =='
-- =============================================================================
SELECT id AS doc_gr1 FROM documento WHERE referencia = 'GR-1' \gset
CALL sp_anular_documento(:doc_gr1, 'Guía emitida por error', '2026-04-05');
SELECT pruebas.igual((pruebas.stock(2,'LOTE')).cantidad, 0::numeric(14,4),
                     'anular la transferencia vacía el destino');
SELECT pruebas.igual((pruebas.stock(1,'LOTE')).valor_total, 330.0000::numeric(18,4),
                     'y devuelve el valor exacto al origen');
SELECT pruebas.igual((pruebas.stock(1,'PEPS')).valor_total, 700.0000::numeric(18,4),
                     'PEPS recupera su capa a costo original');
SELECT pruebas.igual((SELECT estado::text FROM documento WHERE referencia = 'GR-1' AND tipo = 'TRANSFERENCIA'),
                     'ANULADO', 'documento marcado como ANULADO');
SELECT pruebas.falla(format($$CALL inventario.sp_anular_documento(%s, 'otra vez', '2026-04-06')$$,
                            :doc_gr1), 'ya está anulado', 'doble anulación');

-- Una compra cuyo stock ya se vendió no puede anularse.
CALL sp_registrar_entrada('COMPRA', 2, '[{"producto":"PROM","cantidad":10,"costo_unitario":7}]',
                          'F-40', '2026-04-06');
CALL sp_registrar_salida('VENTA', 2, '[{"producto":"PROM","cantidad":4}]', 'B-40', '2026-04-07');
SELECT id AS doc_f40 FROM documento WHERE referencia = 'F-40' \gset
SELECT id AS doc_b40 FROM documento WHERE referencia = 'B-40' \gset
SELECT pruebas.falla(format($$CALL inventario.sp_anular_documento(%s, 'error', '2026-04-08')$$,
                            :doc_f40), 'insuficiente', 'anular compra ya consumida');
CALL sp_anular_documento(:doc_b40, 'venta anulada', '2026-04-08');
SELECT pruebas.igual((pruebas.stock(2,'PROM')).cantidad, 10::numeric(14,4),
                     'anular la venta restituye las unidades');
SELECT pruebas.igual(
    (SELECT cantidad_disponible FROM capa_inventario c JOIN kardex k ON k.capa_id = c.id
      WHERE k.documento_id = :doc_f40), 10::numeric(14,4),
    'las unidades vuelven a su capa original (no se crea una capa nueva)');
CALL sp_anular_documento(:doc_f40, 'compra anulada', '2026-04-08');
SELECT pruebas.igual((pruebas.stock(2,'PROM')).cantidad, 0::numeric(14,4),
                     'tras anular venta y compra el saldo vuelve a cero');
SELECT pruebas.falla($$UPDATE inventario.documento SET referencia = 'X' WHERE id = 1$$,
    'solo se permite', 'editar un documento procesado');
SELECT pruebas.integro();

-- =============================================================================
\echo '== 7. Inventario físico =='
-- =============================================================================
CALL sp_inventario_fisico(1, $$[
    {"producto":"PEPS","cantidad_fisica":47},
    {"producto":"LOTE","cantidad_fisica":62,"lote":"B-2"}
]$$, 'TOMA-2026-04', '2026-04-10');
SELECT pruebas.igual((pruebas.stock(1,'PEPS')).cantidad, 47::numeric(14,4), 'faltante de 3 ajustado');
SELECT pruebas.igual(
    (SELECT cantidad FROM v_stock_por_lote WHERE almacen = 'A1' AND lote = 'B-2'), 62::numeric,
    'sobrante del lote B-2 ajustado');
SELECT pruebas.igual(
    (SELECT count(*) FROM kardex WHERE documento_id =
        (SELECT id FROM documento WHERE referencia = 'TOMA-2026-04'))::int, 2,
    'el documento contiene un ajuste negativo y uno positivo');
SELECT pruebas.integro();

-- =============================================================================
\echo '== 8. Alertas de stock mínimo =='
-- =============================================================================
CALL sp_configurar_stock_minimo(1, 'PEPS', 45);
SELECT pruebas.igual(
    (SELECT count(*) FROM v_alertas_activas WHERE tipo = 'STOCK_MINIMO' AND producto = 'PEPS')::int, 0,
    'sin alerta mientras stock (47) > mínimo (45)');
CALL sp_registrar_salida('VENTA', 1, '[{"producto":"PEPS","cantidad":2}]', 'B-50', '2026-04-11');
SELECT pruebas.igual(
    (SELECT count(*) FROM v_alertas_activas WHERE tipo = 'STOCK_MINIMO' AND producto = 'PEPS')::int, 1,
    'alerta al llegar al mínimo');
CALL sp_registrar_salida('VENTA', 1, '[{"producto":"PEPS","cantidad":1}]', 'B-51', '2026-04-11');
SELECT pruebas.igual(
    (SELECT count(*) FROM alerta WHERE tipo = 'STOCK_MINIMO' AND NOT resuelta)::int, 1,
    'no se duplican alertas abiertas');
CALL sp_registrar_salida('VENTA', 1, '[{"producto":"PEPS","cantidad":44}]', 'B-52', '2026-04-12');
SELECT pruebas.igual(
    (SELECT count(*) FROM v_alertas_activas WHERE tipo = 'STOCK_AGOTADO' AND producto = 'PEPS' AND almacen = 'A1')::int, 1,
    'alerta de quiebre de stock');
CALL sp_registrar_entrada('COMPRA', 1, '[{"producto":"PEPS","cantidad":100,"costo_unitario":15}]',
                          'F-50', '2026-04-13');
SELECT pruebas.igual(
    (SELECT count(*) FROM v_alertas_activas WHERE producto = 'PEPS' AND almacen = 'A1')::int, 0,
    'la reposición resuelve las alertas automáticamente');
SELECT pruebas.integro();

-- =============================================================================
\echo '== 9. Reporte de kardex =='
-- =============================================================================
SELECT pruebas.igual(
    (SELECT saldo_valor FROM fn_reporte_kardex('PROM', 1, '2026-01-09') LIMIT 1),
    330.0000::numeric, 'saldo inicial del reporte al 09/01 = 330');
SELECT * FROM fn_reporte_kardex('PEPS', 1);

\echo 'TODAS LAS PRUEBAS FUNCIONALES PASARON'
