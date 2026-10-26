-- =============================================================================
--  07. Seguridad para el acceso desde la red local (LAN)
--
--  Las computadoras cliente se conectan con el rol app_inventario, que:
--    * puede LEER tablas y vistas (excepto los hashes de contraseñas);
--    * puede mantener maestros: almacenes, categorías y productos;
--    * NO puede escribir directamente en documento, kardex, stock, capas,
--      lotes ni alertas: todo movimiento pasa por los procedimientos sp_*,
--      que se ejecutan con SECURITY DEFINER.
--  El rol se crea sin LOGIN; install.sh / instalar_servidor.bat le asignan
--  la contraseña de red.
-- =============================================================================
SET search_path = inventario, public;

-- Cada rutina resuelve nombres en su propio esquema, independientemente del
-- search_path de la sesión (obligatorio para SECURITY DEFINER).
-- fn_autorizar_motor se excluye: una cláusula SET revertiría su set_config.
DO $$
DECLARE
    v_rutina record;
BEGIN
    FOR v_rutina IN
        SELECT p.oid::regprocedure AS firma, p.prokind, p.proname
          FROM pg_proc p
          LEFT JOIN pg_depend d ON d.objid = p.oid AND d.deptype = 'e'
         WHERE p.pronamespace = 'inventario'::regnamespace
           AND d.objid IS NULL                     -- excluye funciones de pgcrypto
           AND p.proname <> 'fn_autorizar_motor'
    LOOP
        EXECUTE format('ALTER %s %s SET search_path = inventario, pg_temp',
                       CASE v_rutina.prokind WHEN 'p' THEN 'PROCEDURE' ELSE 'FUNCTION' END,
                       v_rutina.firma);

        IF v_rutina.proname LIKE 'sp\_%' OR v_rutina.proname = 'fn_login' THEN
            EXECUTE format('ALTER %s %s SECURITY DEFINER',
                           CASE v_rutina.prokind WHEN 'p' THEN 'PROCEDURE' ELSE 'FUNCTION' END,
                           v_rutina.firma);
        END IF;
    END LOOP;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_inventario') THEN
        CREATE ROLE app_inventario NOLOGIN;
    END IF;
END;
$$;

REVOKE ALL ON SCHEMA inventario FROM PUBLIC;
REVOKE EXECUTE ON ALL ROUTINES IN SCHEMA inventario FROM PUBLIC;

GRANT USAGE ON SCHEMA inventario TO app_inventario;
GRANT SELECT ON ALL TABLES IN SCHEMA inventario TO app_inventario;
REVOKE SELECT ON usuario FROM app_inventario;
GRANT INSERT, UPDATE ON almacen, categoria, producto TO app_inventario;
GRANT EXECUTE ON ALL ROUTINES IN SCHEMA inventario TO app_inventario;
