-- =============================================================================
--  05. Vistas de consulta, reportes y verificación de integridad
-- =============================================================================
SET search_path = inventario, public;

-- Kardex valorizado en formato contable (entradas | salidas | saldo).
CREATE VIEW v_kardex AS
SELECT k.id,
       k.fecha,
       a.codigo                       AS almacen,
       p.codigo                       AS producto,
       p.nombre                       AS descripcion,
       p.metodo_valuacion,
       l.codigo                       AS lote,
       d.id                           AS documento_id,
       d.tipo                         AS tipo_documento,
       d.referencia,
       CASE WHEN k.tipo_movimiento = 'ENTRADA' THEN k.cantidad       END AS entrada_cantidad,
       CASE WHEN k.tipo_movimiento = 'ENTRADA' THEN k.costo_unitario END AS entrada_costo_unitario,
       CASE WHEN k.tipo_movimiento = 'ENTRADA' THEN k.costo_total    END AS entrada_costo_total,
       CASE WHEN k.tipo_movimiento = 'SALIDA'  THEN k.cantidad       END AS salida_cantidad,
       CASE WHEN k.tipo_movimiento = 'SALIDA'  THEN k.costo_unitario END AS salida_costo_unitario,
       CASE WHEN k.tipo_movimiento = 'SALIDA'  THEN k.costo_total    END AS salida_costo_total,
       k.saldo_cantidad,
       k.saldo_costo_unitario,
       k.saldo_valor
  FROM kardex k
  JOIN almacen a   ON a.id = k.almacen_id
  JOIN producto p  ON p.id = k.producto_id
  JOIN documento d ON d.id = k.documento_id
  LEFT JOIN lote l ON l.id = k.lote_id;

-- Kardex de un producto en un almacén para un período, con saldo inicial.
CREATE FUNCTION fn_reporte_kardex(p_producto text, p_almacen_id integer,
                                  p_desde timestamptz DEFAULT '-infinity',
                                  p_hasta timestamptz DEFAULT 'infinity')
RETURNS TABLE (fecha timestamptz, concepto text, lote varchar,
               entrada_cantidad numeric, entrada_costo_unitario numeric, entrada_costo_total numeric,
               salida_cantidad numeric,  salida_costo_unitario numeric,  salida_costo_total numeric,
               saldo_cantidad numeric,   saldo_costo_unitario numeric,   saldo_valor numeric)
LANGUAGE sql STABLE AS $$
    WITH previo AS (
        SELECT k.saldo_cantidad, k.saldo_costo_unitario, k.saldo_valor
          FROM kardex k
         WHERE k.producto_id = inventario.fn_producto_id(p_producto)
           AND k.almacen_id = p_almacen_id
           AND k.fecha < p_desde
         ORDER BY k.fecha DESC, k.id DESC
         LIMIT 1)
    SELECT p_desde, 'SALDO INICIAL', NULL::varchar,
           NULL, NULL, NULL, NULL, NULL, NULL,
           coalesce(pr.saldo_cantidad, 0), coalesce(pr.saldo_costo_unitario, 0),
           coalesce(pr.saldo_valor, 0)
      FROM (SELECT 1) x LEFT JOIN previo pr ON true
     WHERE p_desde > '-infinity'
    UNION ALL
    SELECT * FROM (
        SELECT v.fecha, v.tipo_documento || coalesce(' ' || v.referencia, ''), v.lote,
               v.entrada_cantidad, v.entrada_costo_unitario, v.entrada_costo_total,
               v.salida_cantidad,  v.salida_costo_unitario,  v.salida_costo_total,
               v.saldo_cantidad,   v.saldo_costo_unitario,   v.saldo_valor
          FROM inventario.v_kardex v
          JOIN inventario.almacen a ON a.codigo = v.almacen
         WHERE v.producto = p_producto
           AND a.id = p_almacen_id
           AND v.fecha >= p_desde AND v.fecha <= p_hasta
         ORDER BY v.fecha, v.id) movimientos;
$$;

-- Stock valorizado por almacén y producto.
CREATE VIEW v_stock_valorizado AS
SELECT a.codigo  AS almacen,
       p.codigo  AS producto,
       p.nombre  AS descripcion,
       p.unidad_medida,
       p.metodo_valuacion,
       s.cantidad,
       s.costo_promedio AS costo_unitario,
       s.valor_total,
       s.stock_minimo,
       (s.stock_minimo > 0 AND s.cantidad <= s.stock_minimo) AS bajo_minimo
  FROM stock s
  JOIN almacen a  ON a.id = s.almacen_id
  JOIN producto p ON p.id = s.producto_id;

-- Existencias por lote con semáforo de vencimiento.
CREATE VIEW v_stock_por_lote AS
SELECT a.codigo  AS almacen,
       p.codigo  AS producto,
       l.codigo  AS lote,
       l.fecha_vencimiento,
       l.fecha_vencimiento - current_date AS dias_para_vencer,
       sum(c.cantidad_disponible) AS cantidad,
       -- PEPS: valor real de cada capa; PROMEDIO: el lote vale al costo promedio.
       round(CASE WHEN p.metodo_valuacion = 'PEPS'
                  THEN sum(c.cantidad_disponible * c.costo_unitario)
                  ELSE sum(c.cantidad_disponible) * s.costo_promedio END, 4) AS valor,
       CASE
           WHEN l.fecha_vencimiento IS NULL                                        THEN 'SIN VENCIMIENTO'
           WHEN l.fecha_vencimiento < current_date                                  THEN 'VENCIDO'
           WHEN l.fecha_vencimiento <= current_date + p.dias_alerta_vencimiento     THEN 'POR VENCER'
           ELSE 'VIGENTE'
       END AS estado
  FROM capa_inventario c
  JOIN almacen a  ON a.id = c.almacen_id
  JOIN producto p ON p.id = c.producto_id
  JOIN stock s    ON s.almacen_id = c.almacen_id AND s.producto_id = c.producto_id
  LEFT JOIN lote l ON l.id = c.lote_id
 WHERE c.cantidad_disponible > 0
 GROUP BY a.codigo, p.codigo, p.metodo_valuacion, p.dias_alerta_vencimiento,
          s.costo_promedio, l.codigo, l.fecha_vencimiento;

CREATE VIEW v_valor_inventario_almacen AS
SELECT a.codigo AS almacen, a.nombre,
       count(*) FILTER (WHERE s.cantidad > 0) AS productos_con_stock,
       coalesce(sum(s.valor_total), 0)        AS valor_total
  FROM almacen a
  LEFT JOIN stock s ON s.almacen_id = a.id
 GROUP BY a.id, a.codigo, a.nombre;

CREATE VIEW v_alertas_activas AS
SELECT al.id, al.tipo, a.codigo AS almacen, p.codigo AS producto, l.codigo AS lote,
       al.mensaje, al.creada_en
  FROM alerta al
  JOIN almacen a  ON a.id = al.almacen_id
  JOIN producto p ON p.id = al.producto_id
  LEFT JOIN lote l ON l.id = al.lote_id
 WHERE NOT al.resuelta;

-- -----------------------------------------------------------------------------
--  Auditoría: devuelve una fila por cada inconsistencia (vacío = íntegro)
-- -----------------------------------------------------------------------------
CREATE FUNCTION fn_verificar_integridad()
RETURNS TABLE (regla text, almacen_id integer, producto_id integer, detalle text)
LANGUAGE sql STABLE AS $$
    -- 1. Stock = suma de capas disponibles
    SELECT 'stock = Σ capas', s.almacen_id, s.producto_id,
           format('stock %s vs capas %s', s.cantidad, coalesce(c.total, 0))
      FROM inventario.stock s
      LEFT JOIN (SELECT almacen_id, producto_id, sum(cantidad_disponible) total
                   FROM inventario.capa_inventario GROUP BY 1, 2) c
             USING (almacen_id, producto_id)
     WHERE s.cantidad <> coalesce(c.total, 0)
    UNION ALL
    -- 2. Stock = saldo de la última línea de kardex
    SELECT 'stock = último saldo kardex', s.almacen_id, s.producto_id,
           format('stock %s/%s vs kardex %s/%s', s.cantidad, s.valor_total,
                  k.saldo_cantidad, k.saldo_valor)
      FROM inventario.stock s
      LEFT JOIN LATERAL (SELECT saldo_cantidad, saldo_valor FROM inventario.kardex k
                          WHERE k.almacen_id = s.almacen_id AND k.producto_id = s.producto_id
                          ORDER BY k.fecha DESC, k.id DESC LIMIT 1) k ON true
     WHERE s.cantidad <> coalesce(k.saldo_cantidad, 0)
        OR s.valor_total <> coalesce(k.saldo_valor, 0)
    UNION ALL
    -- 3. Saldo de kardex = Σ entradas − Σ salidas
    SELECT 'Σ entradas − Σ salidas = stock', s.almacen_id, s.producto_id,
           format('stock %s vs movimientos %s', s.cantidad, coalesce(m.neto, 0))
      FROM inventario.stock s
      LEFT JOIN (SELECT almacen_id, producto_id,
                        sum(CASE tipo_movimiento WHEN 'ENTRADA' THEN cantidad ELSE -cantidad END) neto
                   FROM inventario.kardex GROUP BY 1, 2) m
             USING (almacen_id, producto_id)
     WHERE s.cantidad <> coalesce(m.neto, 0)
    UNION ALL
    -- 4. Cada salida consumió exactamente su cantidad
    SELECT 'salida = Σ consumos', k.almacen_id, k.producto_id,
           format('kardex %s: %s vs %s', k.id, k.cantidad, coalesce(sum(cc.cantidad), 0))
      FROM inventario.kardex k
      LEFT JOIN inventario.consumo_capa cc ON cc.kardex_id = k.id
     WHERE k.tipo_movimiento = 'SALIDA'
     GROUP BY k.id
    HAVING k.cantidad <> coalesce(sum(cc.cantidad), 0)
    UNION ALL
    -- 5. Ninguna existencia negativa
    SELECT 'stock no negativo', s.almacen_id, s.producto_id, s.cantidad::text
      FROM inventario.stock s WHERE s.cantidad < 0;
$$;

-- -----------------------------------------------------------------------------
--  Endurecimiento: cada rutina resuelve nombres en su propio esquema,
--  independientemente del search_path de la sesión que la invoque.
--  (fn_autorizar_motor se excluye: una cláusula SET revertiría su set_config.)
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    v_rutina record;
BEGIN
    FOR v_rutina IN
        SELECT p.oid::regprocedure AS firma, p.prokind
          FROM pg_proc p
         WHERE p.pronamespace = 'inventario'::regnamespace
           AND p.proname <> 'fn_autorizar_motor'
    LOOP
        EXECUTE format('ALTER %s %s SET search_path = inventario, pg_temp',
                       CASE v_rutina.prokind WHEN 'p' THEN 'PROCEDURE' ELSE 'FUNCTION' END,
                       v_rutina.firma);
    END LOOP;
END;
$$;
