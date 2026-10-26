-- =============================================================================
--  02. Motor del kardex valorizado (triggers)
--
--  Toda variación de existencias se materializa como un INSERT en kardex.
--  El trigger BEFORE INSERT:
--    1. valida producto, almacén, lote y cronología;
--    2. bloquea la fila de stock (serializa movimientos del mismo
--       producto-almacén y evita condiciones de carrera);
--    3. ENTRADA: crea una capa de costo (o, si revierte una salida anulada,
--                devuelve las unidades a las capas que esa salida consumió);
--       SALIDA : consume capas (PEPS o FEFO) y rechaza stock insuficiente;
--    4. valoriza según el método del producto (Promedio Ponderado o PEPS);
--    5. calcula el saldo y actualiza la tabla stock.
-- =============================================================================
SET search_path = inventario, public;

-- Bandera de transacción que autoriza a modificar stock / capas.
CREATE FUNCTION fn_autorizar_motor(p_activo boolean) RETURNS void
LANGUAGE sql AS $$
    SELECT set_config('inventario.motor_kardex', CASE WHEN p_activo THEN 'on' ELSE 'off' END, true);
$$;

CREATE FUNCTION fn_motor_autorizado() RETURNS boolean
LANGUAGE sql STABLE AS $$
    SELECT coalesce(current_setting('inventario.motor_kardex', true), 'off') = 'on';
$$;

-- -----------------------------------------------------------------------------
--  Núcleo: procesamiento de cada línea de kardex
-- -----------------------------------------------------------------------------
CREATE FUNCTION fn_kardex_procesar() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_producto     producto%ROWTYPE;
    v_stock        stock%ROWTYPE;
    v_lote         lote%ROWTYPE;
    v_capa         record;
    v_pendiente    numeric;
    v_tomar        numeric;
    v_costo_capas  numeric := 0;
    v_lotes        bigint[] := '{}';
    v_disponible   numeric;
    v_origen       kardex%ROWTYPE;
BEGIN
    -- 1. Validaciones de maestros ------------------------------------------
    IF NEW.cantidad IS NULL OR NEW.cantidad <= 0 THEN
        RAISE EXCEPTION 'La cantidad debe ser mayor que cero (recibido %)', NEW.cantidad
              USING ERRCODE = 'check_violation';
    END IF;

    SELECT * INTO v_producto FROM producto WHERE id = NEW.producto_id;
    IF NOT FOUND OR NOT v_producto.activo THEN
        RAISE EXCEPTION 'Producto % inexistente o inactivo', NEW.producto_id
              USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM almacen WHERE id = NEW.almacen_id AND activo) THEN
        RAISE EXCEPTION 'Almacén % inexistente o inactivo', NEW.almacen_id
              USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.lote_id IS NOT NULL THEN
        IF NOT v_producto.controla_lote THEN
            RAISE EXCEPTION 'El producto % no controla lotes', v_producto.codigo
                  USING ERRCODE = 'check_violation';
        END IF;
        SELECT * INTO v_lote FROM lote WHERE id = NEW.lote_id;
    ELSIF v_producto.controla_lote AND NEW.tipo_movimiento = 'ENTRADA'
          AND NEW.kardex_origen_id IS NULL THEN
        RAISE EXCEPTION 'El producto % exige lote en cada entrada', v_producto.codigo
              USING ERRCODE = 'not_null_violation';
    END IF;

    IF v_producto.controla_vencimiento AND NEW.tipo_movimiento = 'ENTRADA'
       AND NEW.lote_id IS NOT NULL AND v_lote.fecha_vencimiento IS NULL THEN
        RAISE EXCEPTION 'El lote del producto % debe tener fecha de vencimiento', v_producto.codigo
              USING ERRCODE = 'not_null_violation';
    END IF;

    -- 2. Bloqueo pesimista del saldo producto-almacén ----------------------
    INSERT INTO stock (almacen_id, producto_id)
    VALUES (NEW.almacen_id, NEW.producto_id)
    ON CONFLICT DO NOTHING;

    SELECT * INTO v_stock
      FROM stock
     WHERE almacen_id = NEW.almacen_id AND producto_id = NEW.producto_id
       FOR UPDATE;

    -- 3. Cronología: el kardex no admite movimientos retroactivos ----------
    -- Sin fecha explícita se usa la hora real tras obtener el bloqueo (no now(),
    -- que es el inicio de la transacción y puede ser anterior a la de una
    -- transacción concurrente que confirmó mientras esperábamos).
    NEW.fecha := coalesce(NEW.fecha, clock_timestamp());
    IF EXISTS (SELECT 1 FROM kardex
                WHERE almacen_id = NEW.almacen_id
                  AND producto_id = NEW.producto_id
                  AND fecha > NEW.fecha) THEN
        RAISE EXCEPTION 'Movimiento con fecha % anterior al último registrado para el producto % en el almacén %',
              NEW.fecha, v_producto.codigo, NEW.almacen_id
              USING ERRCODE = 'check_violation';
    END IF;

    PERFORM fn_autorizar_motor(true);

    IF NEW.tipo_movimiento = 'ENTRADA' THEN
        -- 4a. ENTRADA ------------------------------------------------------
        SELECT * INTO v_origen FROM kardex WHERE id = NEW.kardex_origen_id;

        IF v_origen.tipo_movimiento = 'SALIDA' AND v_origen.almacen_id = NEW.almacen_id THEN
            -- Reversión de una salida (anulación): las unidades vuelven a sus
            -- capas originales con su costo exacto, preservando el orden PEPS.
            IF v_origen.producto_id <> NEW.producto_id OR v_origen.cantidad <> NEW.cantidad THEN
                RAISE EXCEPTION 'La reversión debe coincidir con la salida % original', v_origen.id
                      USING ERRCODE = 'check_violation';
            END IF;
            IF EXISTS (SELECT 1 FROM kardex
                        WHERE kardex_origen_id = v_origen.id AND almacen_id = NEW.almacen_id) THEN
                RAISE EXCEPTION 'La salida % ya fue revertida', v_origen.id
                      USING ERRCODE = 'unique_violation';
            END IF;

            UPDATE capa_inventario c
               SET cantidad_disponible = c.cantidad_disponible + cc.cantidad
              FROM consumo_capa cc
             WHERE cc.kardex_id = v_origen.id AND c.id = cc.capa_id;

            NEW.lote_id        := v_origen.lote_id;
            NEW.capa_id        := NULL;
            NEW.costo_unitario := v_origen.costo_unitario;
            NEW.costo_total    := v_origen.costo_total;
        ELSE
            IF NEW.costo_unitario IS NULL OR NEW.costo_unitario < 0 THEN
                RAISE EXCEPTION 'Costo unitario inválido para la entrada de %', v_producto.codigo
                      USING ERRCODE = 'check_violation';
            END IF;
            NEW.costo_unitario := round(NEW.costo_unitario, 6);
            NEW.costo_total    := round(NEW.cantidad * NEW.costo_unitario, 4);

            INSERT INTO capa_inventario (almacen_id, producto_id, lote_id, kardex_id,
                                         fecha_ingreso, cantidad_inicial,
                                         cantidad_disponible, costo_unitario)
            VALUES (NEW.almacen_id, NEW.producto_id, NEW.lote_id, NEW.id,
                    NEW.fecha, NEW.cantidad, NEW.cantidad, NEW.costo_unitario)
            RETURNING id INTO NEW.capa_id;
        END IF;

        NEW.saldo_cantidad := v_stock.cantidad + NEW.cantidad;
        NEW.saldo_valor    := v_stock.valor_total + NEW.costo_total;
    ELSE
        -- 4b. SALIDA -------------------------------------------------------
        IF v_stock.cantidad < NEW.cantidad THEN
            RAISE EXCEPTION 'Stock insuficiente de % en almacén %: disponible %, solicitado %',
                  v_producto.codigo, NEW.almacen_id, v_stock.cantidad, NEW.cantidad
                  USING ERRCODE = 'check_violation', HINT = 'STOCK_INSUFICIENTE';
        END IF;

        v_pendiente := NEW.cantidad;

        -- PEPS: se consumen las capas más antiguas (FIFO).
        -- PROMEDIO: FEFO, primero los lotes que vencen antes.
        -- Los lotes vencidos se excluyen salvo autorización explícita (mermas).
        FOR v_capa IN
            SELECT c.id, c.lote_id, c.cantidad_disponible, c.costo_unitario
              FROM capa_inventario c
              LEFT JOIN lote l ON l.id = c.lote_id
             WHERE c.almacen_id = NEW.almacen_id
               AND c.producto_id = NEW.producto_id
               AND c.cantidad_disponible > 0
               AND (NEW.lote_id IS NULL OR c.lote_id = NEW.lote_id)
               AND (NEW.capa_id IS NULL OR c.id = NEW.capa_id)
               AND (NEW.permitir_vencido
                    OR l.fecha_vencimiento IS NULL
                    OR l.fecha_vencimiento >= NEW.fecha::date)
             ORDER BY CASE WHEN v_producto.metodo_valuacion = 'PROMEDIO'
                           THEN l.fecha_vencimiento END NULLS LAST,
                      c.fecha_ingreso, c.id
               FOR UPDATE OF c
        LOOP
            v_tomar := least(v_capa.cantidad_disponible, v_pendiente);

            UPDATE capa_inventario
               SET cantidad_disponible = cantidad_disponible - v_tomar
             WHERE id = v_capa.id;

            INSERT INTO consumo_capa (kardex_id, capa_id, cantidad, costo_unitario)
            VALUES (NEW.id, v_capa.id, v_tomar, v_capa.costo_unitario);

            v_costo_capas := v_costo_capas + v_tomar * v_capa.costo_unitario;
            IF v_capa.lote_id IS NOT NULL AND NOT v_capa.lote_id = ANY (v_lotes) THEN
                v_lotes := v_lotes || v_capa.lote_id;
            END IF;

            v_pendiente := v_pendiente - v_tomar;
            EXIT WHEN v_pendiente = 0;
        END LOOP;

        IF v_pendiente > 0 THEN
            v_disponible := NEW.cantidad - v_pendiente;
            RAISE EXCEPTION 'Stock utilizable insuficiente de % en almacén % (lote/capa solicitada o lotes vencidos): utilizable %, solicitado %',
                  v_producto.codigo, NEW.almacen_id, v_disponible, NEW.cantidad
                  USING ERRCODE = 'check_violation', HINT = 'STOCK_INSUFICIENTE';
        END IF;

        -- Si la salida tomó un único lote, queda registrado en la línea.
        IF NEW.lote_id IS NULL AND cardinality(v_lotes) = 1 THEN
            NEW.lote_id := v_lotes[1];
        END IF;

        -- Valorización de la salida.
        IF NEW.cantidad = v_stock.cantidad THEN
            -- Se agota el saldo: sale todo el valor (sin residuos de redondeo).
            NEW.costo_total := v_stock.valor_total;
        ELSIF v_producto.metodo_valuacion = 'PROMEDIO' THEN
            NEW.costo_total := round(NEW.cantidad * v_stock.costo_promedio, 4);
        ELSE
            NEW.costo_total := round(v_costo_capas, 4);
        END IF;
        NEW.costo_total    := least(NEW.costo_total, v_stock.valor_total);
        NEW.costo_unitario := round(NEW.costo_total / NEW.cantidad, 6);

        NEW.saldo_cantidad := v_stock.cantidad - NEW.cantidad;
        NEW.saldo_valor    := v_stock.valor_total - NEW.costo_total;
    END IF;

    -- 5. Saldo resultante ---------------------------------------------------
    IF NEW.saldo_cantidad = 0 THEN
        NEW.saldo_valor := 0;
    END IF;
    NEW.saldo_costo_unitario := CASE
        WHEN NEW.saldo_cantidad = 0 THEN 0
        -- En promedio ponderado las salidas no alteran el costo promedio
        -- (evita la deriva por redondeo de recalcularlo desde el valor).
        WHEN NEW.tipo_movimiento = 'SALIDA' AND v_producto.metodo_valuacion = 'PROMEDIO'
             THEN v_stock.costo_promedio
        ELSE round(NEW.saldo_valor / NEW.saldo_cantidad, 6)
    END;

    UPDATE stock
       SET cantidad       = NEW.saldo_cantidad,
           valor_total    = NEW.saldo_valor,
           costo_promedio = NEW.saldo_costo_unitario,
           actualizado_en = now()
     WHERE almacen_id = NEW.almacen_id AND producto_id = NEW.producto_id;

    PERFORM fn_autorizar_motor(false);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_kardex_procesar
    BEFORE INSERT ON kardex
    FOR EACH ROW EXECUTE FUNCTION fn_kardex_procesar();

-- -----------------------------------------------------------------------------
--  Inmutabilidad del kardex y de los consumos
-- -----------------------------------------------------------------------------
CREATE FUNCTION fn_bloquear_modificacion() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'La tabla % es inmutable: las correcciones se hacen con documentos de anulación o ajuste',
          TG_TABLE_NAME
          USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER trg_kardex_inmutable
    BEFORE UPDATE OR DELETE ON kardex
    FOR EACH ROW EXECUTE FUNCTION fn_bloquear_modificacion();
CREATE TRIGGER trg_kardex_no_truncate
    BEFORE TRUNCATE ON kardex
    FOR EACH STATEMENT EXECUTE FUNCTION fn_bloquear_modificacion();
CREATE TRIGGER trg_consumo_inmutable
    BEFORE UPDATE OR DELETE ON consumo_capa
    FOR EACH ROW EXECUTE FUNCTION fn_bloquear_modificacion();

-- -----------------------------------------------------------------------------
--  Protección de stock y capas: solo el motor del kardex puede tocarlos
-- -----------------------------------------------------------------------------
CREATE FUNCTION fn_proteger_stock() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF fn_motor_autorizado() THEN
        RETURN coalesce(NEW, OLD);
    END IF;

    IF TG_OP = 'INSERT' AND NEW.cantidad = 0 AND NEW.valor_total = 0 THEN
        RETURN NEW;                       -- alta de saldo vacío
    ELSIF TG_OP = 'UPDATE'
          AND NEW.cantidad       = OLD.cantidad
          AND NEW.valor_total    = OLD.valor_total
          AND NEW.costo_promedio = OLD.costo_promedio THEN
        RETURN NEW;                       -- p. ej. cambio de stock_minimo
    ELSIF TG_OP = 'DELETE' AND OLD.cantidad = 0 THEN
        RETURN OLD;
    END IF;

    RAISE EXCEPTION 'El stock solo puede modificarse mediante movimientos de kardex'
          USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER trg_stock_proteger
    BEFORE INSERT OR UPDATE OR DELETE ON stock
    FOR EACH ROW EXECUTE FUNCTION fn_proteger_stock();

CREATE FUNCTION fn_proteger_capa() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF fn_motor_autorizado() THEN
        RETURN coalesce(NEW, OLD);
    END IF;
    RAISE EXCEPTION 'Las capas de inventario solo pueden modificarse mediante movimientos de kardex'
          USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER trg_capa_proteger
    BEFORE INSERT OR UPDATE OR DELETE ON capa_inventario
    FOR EACH ROW EXECUTE FUNCTION fn_proteger_capa();

-- -----------------------------------------------------------------------------
--  Producto: el método de valuación y el control de lotes no pueden cambiar
--  una vez que el producto tiene movimientos (alteraría el kardex histórico).
-- -----------------------------------------------------------------------------
CREATE FUNCTION fn_producto_controlar_cambios() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.metodo_valuacion, NEW.controla_lote, NEW.controla_vencimiento)
       IS DISTINCT FROM (OLD.metodo_valuacion, OLD.controla_lote, OLD.controla_vencimiento)
       AND EXISTS (SELECT 1 FROM kardex WHERE producto_id = OLD.id) THEN
        RAISE EXCEPTION 'El producto % ya tiene movimientos: no se puede cambiar su método de valuación ni el control de lotes',
              OLD.codigo USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_producto_controlar
    BEFORE UPDATE ON producto
    FOR EACH ROW EXECUTE FUNCTION fn_producto_controlar_cambios();

-- -----------------------------------------------------------------------------
--  Documentos: solo se permite pasar de PROCESADO a ANULADO
-- -----------------------------------------------------------------------------
CREATE FUNCTION fn_documento_controlar_cambios() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Los documentos no se eliminan; use sp_anular_documento'
              USING ERRCODE = 'insufficient_privilege';
    END IF;

    IF OLD.estado = 'PROCESADO' AND NEW.estado = 'ANULADO'
       AND (to_jsonb(NEW) - 'estado') = (to_jsonb(OLD) - 'estado') THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'Documento %: solo se permite el cambio de estado PROCESADO → ANULADO', OLD.id
          USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER trg_documento_controlar
    BEFORE UPDATE OR DELETE ON documento
    FOR EACH ROW EXECUTE FUNCTION fn_documento_controlar_cambios();
