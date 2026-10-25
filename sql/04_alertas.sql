-- =============================================================================
--  04. Alertas automáticas: stock mínimo / agotado y vencimiento de lotes
-- =============================================================================
SET search_path = inventario, public;

CREATE FUNCTION fn_abrir_alerta(p_tipo tipo_alerta, p_almacen_id integer,
                                p_producto_id integer, p_lote_id bigint, p_mensaje text)
RETURNS void
LANGUAGE sql AS $$
    INSERT INTO alerta (tipo, almacen_id, producto_id, lote_id, mensaje)
    VALUES (p_tipo, p_almacen_id, p_producto_id, p_lote_id, p_mensaje)
    ON CONFLICT DO NOTHING;   -- ya existe una alerta abierta equivalente
$$;

CREATE FUNCTION fn_resolver_alertas(p_tipos tipo_alerta[], p_almacen_id integer,
                                    p_producto_id integer, p_lote_id bigint DEFAULT NULL)
RETURNS void
LANGUAGE sql AS $$
    UPDATE alerta
       SET resuelta = true, resuelta_en = clock_timestamp()
     WHERE NOT resuelta
       AND tipo = ANY (p_tipos)
       AND almacen_id = p_almacen_id
       AND producto_id = p_producto_id
       AND lote_id IS NOT DISTINCT FROM p_lote_id;
$$;

-- -----------------------------------------------------------------------------
--  Stock mínimo y quiebre de stock (se evalúa en cada cambio de saldo)
-- -----------------------------------------------------------------------------
CREATE FUNCTION fn_alerta_stock() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_codigo text;
BEGIN
    SELECT codigo INTO v_codigo FROM producto WHERE id = NEW.producto_id;

    IF NEW.cantidad = 0 AND (TG_OP = 'UPDATE' AND OLD.cantidad > 0) THEN
        PERFORM fn_abrir_alerta('STOCK_AGOTADO', NEW.almacen_id, NEW.producto_id, NULL,
                format('Producto %s agotado en almacén %s', v_codigo, NEW.almacen_id));
    ELSIF NEW.cantidad > 0 THEN
        PERFORM fn_resolver_alertas('{STOCK_AGOTADO}', NEW.almacen_id, NEW.producto_id);
    END IF;

    IF NEW.stock_minimo > 0 AND NEW.cantidad <= NEW.stock_minimo THEN
        PERFORM fn_abrir_alerta('STOCK_MINIMO', NEW.almacen_id, NEW.producto_id, NULL,
                format('Producto %s en almacén %s bajo el mínimo: %s / %s',
                       v_codigo, NEW.almacen_id, NEW.cantidad, NEW.stock_minimo));
    ELSE
        PERFORM fn_resolver_alertas('{STOCK_MINIMO}', NEW.almacen_id, NEW.producto_id);
    END IF;

    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_stock_alerta_ins
    AFTER INSERT ON stock
    FOR EACH ROW WHEN (NEW.stock_minimo > 0)
    EXECUTE FUNCTION fn_alerta_stock();

CREATE TRIGGER trg_stock_alerta_upd
    AFTER UPDATE OF cantidad, stock_minimo ON stock
    FOR EACH ROW
    WHEN (OLD.cantidad IS DISTINCT FROM NEW.cantidad
          OR OLD.stock_minimo IS DISTINCT FROM NEW.stock_minimo)
    EXECUTE FUNCTION fn_alerta_stock();

-- -----------------------------------------------------------------------------
--  Vencimiento de lotes
-- -----------------------------------------------------------------------------
-- Evalúa un lote en un almacén a una fecha dada y abre / cierra alertas.
CREATE FUNCTION fn_evaluar_lote(p_almacen_id integer, p_lote_id bigint, p_fecha date)
RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    v_lote        lote%ROWTYPE;
    v_producto    producto%ROWTYPE;
    v_existencia  numeric;
BEGIN
    SELECT * INTO v_lote FROM lote WHERE id = p_lote_id;
    SELECT * INTO v_producto FROM producto WHERE id = v_lote.producto_id;

    IF v_lote.fecha_vencimiento IS NULL OR NOT v_producto.controla_vencimiento THEN
        RETURN;
    END IF;

    SELECT coalesce(sum(cantidad_disponible), 0) INTO v_existencia
      FROM capa_inventario
     WHERE almacen_id = p_almacen_id AND lote_id = p_lote_id;

    IF v_existencia = 0 THEN
        -- Sin existencias del lote ya no hay riesgo que alertar.
        PERFORM fn_resolver_alertas('{LOTE_POR_VENCER,LOTE_VENCIDO}',
                                    p_almacen_id, v_producto.id, p_lote_id);
    ELSIF v_lote.fecha_vencimiento < p_fecha THEN
        PERFORM fn_resolver_alertas('{LOTE_POR_VENCER}', p_almacen_id, v_producto.id, p_lote_id);
        PERFORM fn_abrir_alerta('LOTE_VENCIDO', p_almacen_id, v_producto.id, p_lote_id,
                format('Lote %s de %s VENCIDO el %s (%s unidades en almacén %s)',
                       v_lote.codigo, v_producto.codigo, v_lote.fecha_vencimiento,
                       v_existencia, p_almacen_id));
    ELSIF v_lote.fecha_vencimiento <= p_fecha + v_producto.dias_alerta_vencimiento THEN
        PERFORM fn_abrir_alerta('LOTE_POR_VENCER', p_almacen_id, v_producto.id, p_lote_id,
                format('Lote %s de %s vence el %s (en %s días; %s unidades en almacén %s)',
                       v_lote.codigo, v_producto.codigo, v_lote.fecha_vencimiento,
                       v_lote.fecha_vencimiento - p_fecha, v_existencia, p_almacen_id));
    END IF;
END;
$$;

-- Cada ingreso o consumo de una capa con lote re-evalúa ese lote.
CREATE FUNCTION fn_alerta_capa() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.lote_id IS NOT NULL THEN
        PERFORM fn_evaluar_lote(NEW.almacen_id, NEW.lote_id, NEW.fecha_ingreso::date);
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_capa_alerta_ins
    AFTER INSERT ON capa_inventario
    FOR EACH ROW EXECUTE FUNCTION fn_alerta_capa();

CREATE TRIGGER trg_capa_alerta_agotada
    AFTER UPDATE OF cantidad_disponible ON capa_inventario
    FOR EACH ROW WHEN (NEW.cantidad_disponible = 0 AND NEW.lote_id IS NOT NULL)
    EXECUTE FUNCTION fn_alerta_capa();

-- Barrido diario (programable con pg_cron:
--   SELECT cron.schedule('alertas-vencimiento', '0 6 * * *',
--          'CALL inventario.sp_generar_alertas_vencimiento()');
CREATE PROCEDURE sp_generar_alertas_vencimiento(p_fecha date DEFAULT current_date)
LANGUAGE plpgsql AS $$
DECLARE
    v_par record;
BEGIN
    FOR v_par IN
        SELECT DISTINCT c.almacen_id, c.lote_id
          FROM capa_inventario c
          JOIN lote l     ON l.id = c.lote_id
          JOIN producto p ON p.id = c.producto_id
         WHERE c.cantidad_disponible > 0
           AND p.controla_vencimiento
           AND l.fecha_vencimiento <= p_fecha + p.dias_alerta_vencimiento
    LOOP
        PERFORM fn_evaluar_lote(v_par.almacen_id, v_par.lote_id, p_fecha);
    END LOOP;
END;
$$;
