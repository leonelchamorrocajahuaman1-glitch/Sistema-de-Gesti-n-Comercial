-- =============================================================================
--  06. Usuarios de la aplicación y vistas para la interfaz de escritorio
--
--  Las contraseñas se guardan con bcrypt (pgcrypto) y nunca salen del
--  servidor: la aplicación solo invoca fn_login / sp_cambiar_clave.
-- =============================================================================
SET search_path = inventario, public;

CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA inventario;

CREATE TYPE rol_usuario AS ENUM ('ADMINISTRADOR', 'ALMACENERO', 'VENDEDOR');

CREATE TABLE usuario (
    id                  integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre_usuario      varchar(30)  NOT NULL UNIQUE
                        CHECK (nombre_usuario ~ '^[a-z0-9._-]{3,30}$'),
    nombre_completo     varchar(100) NOT NULL,
    rol                 rol_usuario  NOT NULL,
    almacen_id          integer      REFERENCES almacen (id),
    hash_clave          text         NOT NULL,
    debe_cambiar_clave  boolean      NOT NULL DEFAULT true,
    activo              boolean      NOT NULL DEFAULT true,
    intentos_fallidos   integer      NOT NULL DEFAULT 0,
    bloqueado_hasta     timestamptz,
    ultimo_acceso       timestamptz,
    creado_en           timestamptz  NOT NULL DEFAULT now()
);

CREATE VIEW v_usuarios AS
SELECT u.id, u.nombre_usuario, u.nombre_completo, u.rol, u.almacen_id,
       a.codigo AS almacen, u.activo, u.debe_cambiar_clave, u.ultimo_acceso
  FROM usuario u
  LEFT JOIN almacen a ON a.id = u.almacen_id;

CREATE FUNCTION fn_validar_clave(p_clave text) RETURNS void
LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
    IF p_clave IS NULL OR length(p_clave) < 6 THEN
        RAISE EXCEPTION 'La contraseña debe tener al menos 6 caracteres'
              USING ERRCODE = 'invalid_parameter_value';
    END IF;
END;
$$;

-- Inicio de sesión.  No lanza excepción ante credenciales inválidas para que
-- el contador de intentos fallidos se confirme; 5 fallos bloquean 5 minutos.
CREATE FUNCTION fn_login(p_usuario text, p_clave text)
RETURNS TABLE (estado text, id integer, nombre_usuario varchar, nombre_completo varchar,
               rol rol_usuario, almacen_id integer, debe_cambiar_clave boolean)
LANGUAGE plpgsql AS $$
DECLARE
    v_u usuario%ROWTYPE;
BEGIN
    SELECT * INTO v_u FROM usuario u WHERE u.nombre_usuario = lower(trim(p_usuario)) FOR UPDATE;

    IF NOT FOUND THEN
        estado := 'CREDENCIALES_INVALIDAS';
    ELSIF NOT v_u.activo THEN
        estado := 'USUARIO_INACTIVO';
    ELSIF v_u.bloqueado_hasta > now() THEN
        estado := 'USUARIO_BLOQUEADO';
    ELSIF v_u.hash_clave <> crypt(p_clave, v_u.hash_clave) THEN
        UPDATE usuario u
           SET intentos_fallidos = u.intentos_fallidos + 1,
               bloqueado_hasta = CASE WHEN u.intentos_fallidos + 1 >= 5
                                      THEN now() + interval '5 minutes' END
         WHERE u.id = v_u.id;
        estado := 'CREDENCIALES_INVALIDAS';
    ELSE
        UPDATE usuario u
           SET intentos_fallidos = 0, bloqueado_hasta = NULL, ultimo_acceso = now()
         WHERE u.id = v_u.id;
        estado := 'OK';
        id := v_u.id;
        nombre_usuario := v_u.nombre_usuario;
        nombre_completo := v_u.nombre_completo;
        rol := v_u.rol;
        almacen_id := v_u.almacen_id;
        debe_cambiar_clave := v_u.debe_cambiar_clave;
    END IF;
    RETURN NEXT;
END;
$$;

CREATE PROCEDURE sp_cambiar_clave(p_usuario text, p_clave_actual text, p_clave_nueva text)
LANGUAGE plpgsql AS $$
DECLARE
    v_hash text;
BEGIN
    SELECT hash_clave INTO v_hash FROM usuario WHERE nombre_usuario = p_usuario AND activo;
    IF v_hash IS NULL OR v_hash <> crypt(p_clave_actual, v_hash) THEN
        RAISE EXCEPTION 'La contraseña actual no es correcta' USING ERRCODE = 'invalid_password';
    END IF;
    IF p_clave_nueva = p_clave_actual THEN
        RAISE EXCEPTION 'La nueva contraseña debe ser distinta de la actual'
              USING ERRCODE = 'invalid_parameter_value';
    END IF;
    PERFORM fn_validar_clave(p_clave_nueva);

    UPDATE usuario
       SET hash_clave = crypt(p_clave_nueva, gen_salt('bf', 10)), debe_cambiar_clave = false
     WHERE nombre_usuario = p_usuario;
END;
$$;

-- Alta (p_id NULL) o modificación de un usuario.  Si se indica p_clave se
-- restablece y el usuario deberá cambiarla en su próximo inicio de sesión.
CREATE PROCEDURE sp_guardar_usuario(
    p_id               integer,
    p_nombre_usuario   text,
    p_nombre_completo  text,
    p_rol              rol_usuario,
    p_almacen_id       integer,
    p_activo           boolean,
    p_clave            text DEFAULT NULL,
    INOUT p_usuario_id integer DEFAULT NULL)
LANGUAGE plpgsql AS $$
BEGIN
    IF p_id IS NULL THEN
        PERFORM fn_validar_clave(p_clave);
        INSERT INTO usuario (nombre_usuario, nombre_completo, rol, almacen_id, activo, hash_clave)
        VALUES (lower(trim(p_nombre_usuario)), trim(p_nombre_completo), p_rol, p_almacen_id,
                p_activo, crypt(p_clave, gen_salt('bf', 10)))
        RETURNING id INTO p_usuario_id;
    ELSE
        UPDATE usuario
           SET nombre_usuario  = lower(trim(p_nombre_usuario)),
               nombre_completo = trim(p_nombre_completo),
               rol             = p_rol,
               almacen_id      = p_almacen_id,
               activo          = p_activo
         WHERE id = p_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Usuario % no existe', p_id USING ERRCODE = 'no_data_found';
        END IF;
        IF p_clave IS NOT NULL AND p_clave <> '' THEN
            PERFORM fn_validar_clave(p_clave);
            UPDATE usuario
               SET hash_clave = crypt(p_clave, gen_salt('bf', 10)), debe_cambiar_clave = true,
                   intentos_fallidos = 0, bloqueado_hasta = NULL
             WHERE id = p_id;
        END IF;
        p_usuario_id := p_id;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM usuario WHERE rol = 'ADMINISTRADOR' AND activo) THEN
        RAISE EXCEPTION 'Debe existir al menos un administrador activo'
              USING ERRCODE = 'check_violation';
    END IF;
END;
$$;

-- Usuario inicial: admin / admin123 (se exige cambiarla en el primer ingreso).
INSERT INTO usuario (nombre_usuario, nombre_completo, rol, hash_clave)
VALUES ('admin', 'Administrador del sistema', 'ADMINISTRADOR', crypt('admin123', gen_salt('bf', 10)));

-- -----------------------------------------------------------------------------
--  Vistas para la aplicación
-- -----------------------------------------------------------------------------
CREATE VIEW v_ventas AS
SELECT d.id                               AS documento_id,
       d.fecha,
       d.referencia,
       d.usuario,
       a.codigo                           AS almacen,
       p.codigo                           AS producto,
       p.nombre                           AS descripcion,
       k.cantidad,
       k.precio_venta                     AS precio_unitario,
       round(k.cantidad * coalesce(k.precio_venta, 0), 2) AS importe,
       k.costo_total                      AS costo,
       round(k.cantidad * coalesce(k.precio_venta, 0) - k.costo_total, 2) AS margen
  FROM kardex k
  JOIN documento d ON d.id = k.documento_id
  JOIN almacen a   ON a.id = k.almacen_id
  JOIN producto p  ON p.id = k.producto_id
 WHERE d.tipo = 'VENTA' AND d.estado = 'PROCESADO';

CREATE VIEW v_documentos AS
SELECT d.id, d.fecha, d.tipo, d.estado,
       a.codigo  AS almacen,
       ad.codigo AS almacen_destino,
       d.referencia, d.observacion, d.usuario,
       count(k.id)                AS lineas,
       coalesce(sum(k.costo_total) FILTER (WHERE k.almacen_id = d.almacen_id), 0) AS costo_total
  FROM documento d
  JOIN almacen a       ON a.id = d.almacen_id
  LEFT JOIN almacen ad ON ad.id = d.almacen_destino_id
  LEFT JOIN kardex k   ON k.documento_id = d.id
 GROUP BY d.id, a.codigo, ad.codigo;

CREATE VIEW v_productos AS
SELECT p.id, p.codigo, p.nombre, c.nombre AS categoria, p.unidad_medida,
       p.metodo_valuacion, p.controla_lote, p.controla_vencimiento,
       p.dias_alerta_vencimiento, p.precio_venta, p.activo,
       coalesce(sum(s.cantidad), 0)    AS stock_total,
       coalesce(sum(s.valor_total), 0) AS valor_total
  FROM producto p
  LEFT JOIN categoria c ON c.id = p.categoria_id
  LEFT JOIN stock s     ON s.producto_id = p.id
 GROUP BY p.id, c.nombre;
