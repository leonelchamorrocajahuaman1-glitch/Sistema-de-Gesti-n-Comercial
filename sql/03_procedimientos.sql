-- =============================================================================
--  03. Procedimientos almacenados de negocio
--
--  Cada procedimiento crea un documento y sus líneas de kardex dentro de la
--  transacción del llamador: o se registra todo el documento o nada.
--  Los ítems se reciben como JSON:
--    entradas      [{"producto":"P001","cantidad":10,"costo_unitario":5.5,
--                    "lote":"L-01","fecha_vencimiento":"2027-01-31"}]
--    salidas       [{"producto":"P001","cantidad":3,"lote":"L-01",
--                    "precio_unitario":9.9}]                 (lote y precio opcionales)
--    transferencia [{"producto":"P001","cantidad":3,"lote":"L-01"}]   (lote opcional)
--    inv. físico   [{"producto":"P001","cantidad_fisica":7,"lote":"L-01",
--                    "costo_unitario":5.5}]                           (costo opcional)
--
--  p_fecha: si se omite, cada línea toma la hora real en que obtiene el
--  bloqueo del saldo, lo que garantiza un kardex cronológico bajo concurrencia.
-- =============================================================================
SET search_path = inventario, public;

-- -----------------------------------------------------------------------------
--  Utilidades
-- -----------------------------------------------------------------------------
CREATE FUNCTION fn_producto_id(p_codigo text) RETURNS integer
LANGUAGE plpgsql STABLE AS $$
DECLARE
    v_id integer;
BEGIN
    SELECT id INTO v_id FROM producto WHERE codigo = p_codigo;
    IF v_id IS NULL THEN
        RAISE EXCEPTION 'Producto % no existe', p_codigo USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN v_id;
END;
$$;

-- Busca un lote existente o lo registra (solo en entradas).
CREATE FUNCTION fn_obtener_lote(p_producto_id integer, p_codigo text,
                                p_vencimiento date, p_crear boolean)
RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v_lote lote%ROWTYPE;
BEGIN
    IF p_codigo IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT * INTO v_lote FROM lote WHERE producto_id = p_producto_id AND codigo = p_codigo;

    IF FOUND THEN
        IF p_vencimiento IS NOT NULL AND v_lote.fecha_vencimiento IS DISTINCT FROM p_vencimiento THEN
            RAISE EXCEPTION 'El lote % ya existe con vencimiento %, distinto de %',
                  p_codigo, v_lote.fecha_vencimiento, p_vencimiento
                  USING ERRCODE = 'check_violation';
        END IF;
        RETURN v_lote.id;
    END IF;

    IF NOT p_crear THEN
        RAISE EXCEPTION 'Lote % no existe para el producto %', p_codigo, p_producto_id
              USING ERRCODE = 'foreign_key_violation';
    END IF;

    INSERT INTO lote (producto_id, codigo, fecha_vencimiento)
    VALUES (p_producto_id, p_codigo, p_vencimiento)
    ON CONFLICT (producto_id, codigo) DO NOTHING
    RETURNING id INTO v_lote.id;

    IF v_lote.id IS NULL THEN  -- creado concurrentemente por otra sesión
        RETURN fn_obtener_lote(p_producto_id, p_codigo, p_vencimiento, false);
    END IF;
    RETURN v_lote.id;
END;
$$;

CREATE FUNCTION fn_validar_items(p_items jsonb) RETURNS void
LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
    IF p_items IS NULL OR jsonb_typeof(p_items) <> 'array' OR jsonb_array_length(p_items) = 0 THEN
        RAISE EXCEPTION 'Se requiere un arreglo JSON de ítems no vacío'
              USING ERRCODE = 'invalid_parameter_value';
    END IF;
END;
$$;

-- Bloquea en orden canónico todas las filas de stock que tocará un documento.
-- Así dos documentos concurrentes con los mismos productos (p. ej. dos
-- transferencias cruzadas A→B y B→A) nunca producen un interbloqueo.
CREATE FUNCTION fn_bloquear_saldos(p_almacenes integer[], p_productos integer[])
RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO stock (almacen_id, producto_id)
    SELECT a, p FROM unnest(p_almacenes) a CROSS JOIN unnest(p_productos) p
    ORDER BY a, p
    ON CONFLICT DO NOTHING;

    PERFORM 1
       FROM stock
      WHERE almacen_id = ANY (p_almacenes) AND producto_id = ANY (p_productos)
      ORDER BY almacen_id, producto_id
        FOR UPDATE;
END;
$$;

CREATE FUNCTION fn_productos_de_items(p_items jsonb) RETURNS integer[]
LANGUAGE sql STABLE AS $$
    SELECT array_agg(DISTINCT fn_producto_id(i ->> 'producto'))
      FROM jsonb_array_elements(p_items) i;
$$;

-- -----------------------------------------------------------------------------
--  Entradas: compras, inventario inicial, devoluciones de clientes, ajustes +
-- -----------------------------------------------------------------------------
CREATE PROCEDURE sp_registrar_entrada(
    p_tipo          tipo_documento,
    p_almacen_id    integer,
    p_items         jsonb,
    p_referencia    text        DEFAULT NULL,
    p_fecha         timestamptz DEFAULT NULL,
    INOUT p_documento_id bigint DEFAULT NULL)
LANGUAGE plpgsql AS $$
DECLARE
    v_item         record;
    v_producto_id  integer;
BEGIN
    IF p_tipo NOT IN ('INVENTARIO_INICIAL', 'COMPRA', 'DEVOLUCION_CLIENTE', 'AJUSTE_POSITIVO') THEN
        RAISE EXCEPTION 'Tipo de documento % no es de entrada', p_tipo
              USING ERRCODE = 'invalid_parameter_value';
    END IF;
    PERFORM fn_validar_items(p_items);
    PERFORM fn_bloquear_saldos(ARRAY[p_almacen_id], fn_productos_de_items(p_items));

    INSERT INTO documento (tipo, fecha, almacen_id, referencia)
    VALUES (p_tipo, coalesce(p_fecha, now()), p_almacen_id, p_referencia)
    RETURNING id INTO p_documento_id;

    FOR v_item IN
        SELECT * FROM jsonb_to_recordset(p_items)
                 AS x(producto text, cantidad numeric, costo_unitario numeric,
                      lote text, fecha_vencimiento date)
    LOOP
        v_producto_id := fn_producto_id(v_item.producto);

        INSERT INTO kardex (documento_id, fecha, almacen_id, producto_id, lote_id,
                            tipo_movimiento, cantidad, costo_unitario)
        VALUES (p_documento_id, p_fecha, p_almacen_id, v_producto_id,
                fn_obtener_lote(v_producto_id, v_item.lote, v_item.fecha_vencimiento, true),
                'ENTRADA', v_item.cantidad, v_item.costo_unitario);
    END LOOP;
END;
$$;

-- -----------------------------------------------------------------------------
--  Salidas: ventas, consumos internos, mermas, devoluciones a proveedor, ajustes -
-- -----------------------------------------------------------------------------
CREATE PROCEDURE sp_registrar_salida(
    p_tipo          tipo_documento,
    p_almacen_id    integer,
    p_items         jsonb,
    p_referencia    text        DEFAULT NULL,
    p_fecha         timestamptz DEFAULT NULL,
    INOUT p_documento_id bigint DEFAULT NULL)
LANGUAGE plpgsql AS $$
DECLARE
    v_item         record;
    v_producto_id  integer;
BEGIN
    IF p_tipo NOT IN ('VENTA', 'CONSUMO', 'MERMA', 'DEVOLUCION_PROVEEDOR', 'AJUSTE_NEGATIVO') THEN
        RAISE EXCEPTION 'Tipo de documento % no es de salida', p_tipo
              USING ERRCODE = 'invalid_parameter_value';
    END IF;
    PERFORM fn_validar_items(p_items);
    PERFORM fn_bloquear_saldos(ARRAY[p_almacen_id], fn_productos_de_items(p_items));

    INSERT INTO documento (tipo, fecha, almacen_id, referencia)
    VALUES (p_tipo, coalesce(p_fecha, now()), p_almacen_id, p_referencia)
    RETURNING id INTO p_documento_id;

    FOR v_item IN
        SELECT * FROM jsonb_to_recordset(p_items)
                 AS x(producto text, cantidad numeric, lote text, precio_unitario numeric)
    LOOP
        v_producto_id := fn_producto_id(v_item.producto);

        -- Solo las mermas y ajustes negativos pueden retirar lotes vencidos;
        -- nunca se vende ni transfiere mercadería vencida.
        INSERT INTO kardex (documento_id, fecha, almacen_id, producto_id, lote_id,
                            tipo_movimiento, cantidad, permitir_vencido, precio_venta)
        VALUES (p_documento_id, p_fecha, p_almacen_id, v_producto_id,
                fn_obtener_lote(v_producto_id, v_item.lote, NULL, false),
                'SALIDA', v_item.cantidad, p_tipo IN ('MERMA', 'AJUSTE_NEGATIVO'),
                CASE WHEN p_tipo = 'VENTA'
                     THEN coalesce(v_item.precio_unitario,
                                   (SELECT precio_venta FROM producto WHERE id = v_producto_id))
                END);
    END LOOP;
END;
$$;

-- -----------------------------------------------------------------------------
--  Transferencias entre almacenes
--  La salida del origen se valoriza con su método; la entrada en destino
--  replica lote por lote lo consumido, conservando lote, vencimiento y costo.
-- -----------------------------------------------------------------------------
CREATE PROCEDURE sp_transferir(
    p_almacen_origen   integer,
    p_almacen_destino  integer,
    p_items            jsonb,
    p_referencia       text        DEFAULT NULL,
    p_fecha            timestamptz DEFAULT NULL,
    INOUT p_documento_id bigint DEFAULT NULL)
LANGUAGE plpgsql AS $$
DECLARE
    v_item         record;
    v_producto_id  integer;
    v_salida       kardex%ROWTYPE;
    v_pieza        record;
BEGIN
    IF p_almacen_origen = p_almacen_destino THEN
        RAISE EXCEPTION 'El almacén de origen y destino deben ser distintos'
              USING ERRCODE = 'invalid_parameter_value';
    END IF;
    PERFORM fn_validar_items(p_items);
    PERFORM fn_bloquear_saldos(ARRAY[p_almacen_origen, p_almacen_destino],
                               fn_productos_de_items(p_items));

    INSERT INTO documento (tipo, fecha, almacen_id, almacen_destino_id, referencia)
    VALUES ('TRANSFERENCIA', coalesce(p_fecha, now()), p_almacen_origen, p_almacen_destino, p_referencia)
    RETURNING id INTO p_documento_id;

    FOR v_item IN
        SELECT * FROM jsonb_to_recordset(p_items) AS x(producto text, cantidad numeric, lote text)
    LOOP
        v_producto_id := fn_producto_id(v_item.producto);

        INSERT INTO kardex (documento_id, fecha, almacen_id, producto_id, lote_id,
                            tipo_movimiento, cantidad)
        VALUES (p_documento_id, p_fecha, p_almacen_origen, v_producto_id,
                fn_obtener_lote(v_producto_id, v_item.lote, NULL, false),
                'SALIDA', v_item.cantidad)
        RETURNING * INTO v_salida;

        FOR v_pieza IN
            SELECT c.lote_id,
                   sum(cc.cantidad) AS cantidad,
                   -- PEPS: costo exacto de la capa; PROMEDIO: costo promedio de salida.
                   CASE WHEN p.metodo_valuacion = 'PEPS'
                        THEN cc.costo_unitario ELSE v_salida.costo_unitario END AS costo_unitario,
                   min(c.fecha_ingreso) AS orden
              FROM consumo_capa cc
              JOIN capa_inventario c ON c.id = cc.capa_id
              JOIN producto p        ON p.id = c.producto_id
             WHERE cc.kardex_id = v_salida.id
             GROUP BY c.lote_id, 3
             ORDER BY orden, c.lote_id NULLS FIRST
        LOOP
            INSERT INTO kardex (documento_id, fecha, almacen_id, producto_id, lote_id,
                                tipo_movimiento, cantidad, costo_unitario, kardex_origen_id)
            VALUES (p_documento_id, p_fecha, p_almacen_destino, v_producto_id, v_pieza.lote_id,
                    'ENTRADA', v_pieza.cantidad, v_pieza.costo_unitario, v_salida.id);
        END LOOP;
    END LOOP;
END;
$$;

-- -----------------------------------------------------------------------------
--  Inventario físico (toma de inventario): genera los ajustes + / − necesarios
--  para que el sistema coincida con el conteo.  Con lote, el conteo es por lote.
-- -----------------------------------------------------------------------------
CREATE PROCEDURE sp_inventario_fisico(
    p_almacen_id    integer,
    p_items         jsonb,
    p_referencia    text        DEFAULT NULL,
    p_fecha         timestamptz DEFAULT NULL,
    INOUT p_documento_id bigint DEFAULT NULL)
LANGUAGE plpgsql AS $$
DECLARE
    v_item         record;
    v_producto_id  integer;
    v_lote_id      bigint;
    v_sistema      numeric;
    v_diferencia   numeric;
    v_costo        numeric;
BEGIN
    PERFORM fn_validar_items(p_items);
    PERFORM fn_bloquear_saldos(ARRAY[p_almacen_id], fn_productos_de_items(p_items));

    INSERT INTO documento (tipo, fecha, almacen_id, referencia)
    VALUES ('INVENTARIO_FISICO', coalesce(p_fecha, now()), p_almacen_id, p_referencia)
    RETURNING id INTO p_documento_id;

    FOR v_item IN
        SELECT * FROM jsonb_to_recordset(p_items)
                 AS x(producto text, cantidad_fisica numeric, lote text,
                      fecha_vencimiento date, costo_unitario numeric)
    LOOP
        IF v_item.cantidad_fisica IS NULL OR v_item.cantidad_fisica < 0 THEN
            RAISE EXCEPTION 'Cantidad física inválida para %', v_item.producto
                  USING ERRCODE = 'check_violation';
        END IF;

        v_producto_id := fn_producto_id(v_item.producto);
        v_lote_id := fn_obtener_lote(v_producto_id, v_item.lote, v_item.fecha_vencimiento, true);

        SELECT coalesce(sum(cantidad_disponible), 0) INTO v_sistema
          FROM capa_inventario
         WHERE almacen_id = p_almacen_id AND producto_id = v_producto_id
           AND (v_item.lote IS NULL OR lote_id = v_lote_id);

        v_diferencia := v_item.cantidad_fisica - v_sistema;

        IF v_diferencia > 0 THEN
            SELECT coalesce(v_item.costo_unitario, nullif(costo_promedio, 0),
                            (SELECT costo_unitario FROM kardex
                              WHERE almacen_id = p_almacen_id AND producto_id = v_producto_id
                              ORDER BY id DESC LIMIT 1),
                            0)
              INTO v_costo
              FROM stock WHERE almacen_id = p_almacen_id AND producto_id = v_producto_id;

            INSERT INTO kardex (documento_id, fecha, almacen_id, producto_id, lote_id,
                                tipo_movimiento, cantidad, costo_unitario)
            VALUES (p_documento_id, p_fecha, p_almacen_id, v_producto_id, v_lote_id,
                    'ENTRADA', v_diferencia, v_costo);
        ELSIF v_diferencia < 0 THEN
            INSERT INTO kardex (documento_id, fecha, almacen_id, producto_id, lote_id,
                                tipo_movimiento, cantidad, permitir_vencido)
            VALUES (p_documento_id, p_fecha, p_almacen_id, v_producto_id, v_lote_id,
                    'SALIDA', -v_diferencia, true);
        END IF;
    END LOOP;
END;
$$;

-- -----------------------------------------------------------------------------
--  Anulación: genera el movimiento inverso de cada línea (el kardex nunca se
--  borra).  Las salidas revertidas restituyen sus capas originales a su costo
--  exacto; una entrada solo puede anularse si su capa sigue disponible.
-- -----------------------------------------------------------------------------
CREATE PROCEDURE sp_anular_documento(
    p_documento_id  bigint,
    p_motivo        text,
    p_fecha         timestamptz DEFAULT NULL,
    INOUT p_anulacion_id bigint DEFAULT NULL)
LANGUAGE plpgsql AS $$
DECLARE
    v_doc     documento%ROWTYPE;
    v_linea   kardex%ROWTYPE;
BEGIN
    SELECT * INTO v_doc FROM documento WHERE id = p_documento_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Documento % no existe', p_documento_id USING ERRCODE = 'no_data_found';
    ELSIF v_doc.estado = 'ANULADO' THEN
        RAISE EXCEPTION 'Documento % ya está anulado', p_documento_id USING ERRCODE = 'check_violation';
    ELSIF v_doc.tipo = 'ANULACION' THEN
        RAISE EXCEPTION 'Un documento de anulación no puede anularse' USING ERRCODE = 'check_violation';
    ELSIF coalesce(trim(p_motivo), '') = '' THEN
        RAISE EXCEPTION 'Debe indicar el motivo de la anulación' USING ERRCODE = 'invalid_parameter_value';
    END IF;

    PERFORM fn_bloquear_saldos(
        (SELECT array_agg(DISTINCT almacen_id)  FROM kardex WHERE documento_id = p_documento_id),
        (SELECT array_agg(DISTINCT producto_id) FROM kardex WHERE documento_id = p_documento_id));

    INSERT INTO documento (tipo, fecha, almacen_id, referencia, observacion, documento_anulado_id)
    VALUES ('ANULACION', coalesce(p_fecha, now()), v_doc.almacen_id, v_doc.referencia, p_motivo, p_documento_id)
    RETURNING id INTO p_anulacion_id;

    -- Orden inverso: en una transferencia primero se retira del destino.
    FOR v_linea IN
        SELECT * FROM kardex WHERE documento_id = p_documento_id ORDER BY id DESC
    LOOP
        IF v_linea.tipo_movimiento = 'ENTRADA' THEN
            INSERT INTO kardex (documento_id, fecha, almacen_id, producto_id, lote_id,
                                tipo_movimiento, capa_id, cantidad, permitir_vencido,
                                kardex_origen_id)
            VALUES (p_anulacion_id, p_fecha, v_linea.almacen_id, v_linea.producto_id,
                    v_linea.lote_id, 'SALIDA', v_linea.capa_id, v_linea.cantidad, true,
                    v_linea.id);
        ELSE
            -- El trigger devuelve las unidades a las capas originales.
            INSERT INTO kardex (documento_id, fecha, almacen_id, producto_id, lote_id,
                                tipo_movimiento, cantidad, kardex_origen_id)
            VALUES (p_anulacion_id, p_fecha, v_linea.almacen_id, v_linea.producto_id,
                    v_linea.lote_id, 'ENTRADA', v_linea.cantidad, v_linea.id);
        END IF;
    END LOOP;

    UPDATE documento SET estado = 'ANULADO' WHERE id = p_documento_id;
END;
$$;

-- -----------------------------------------------------------------------------
--  Configuración de stock mínimo por producto-almacén
-- -----------------------------------------------------------------------------
CREATE PROCEDURE sp_configurar_stock_minimo(
    p_almacen_id  integer,
    p_producto    text,
    p_minimo      numeric)
LANGUAGE plpgsql AS $$
DECLARE
    v_producto_id integer := fn_producto_id(p_producto);
BEGIN
    INSERT INTO stock (almacen_id, producto_id, stock_minimo)
    VALUES (p_almacen_id, v_producto_id, p_minimo)
    ON CONFLICT (almacen_id, producto_id) DO UPDATE SET stock_minimo = EXCLUDED.stock_minimo;
END;
$$;
