package pe.sistema.inventario.datos;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

/** Mantenimiento de almacenes, categorías y productos. */
public final class Maestros {

    private Maestros() {
    }

    public record Producto(Integer id, String codigo, String nombre, String categoria,
                           String unidad, String metodo, boolean controlaLote,
                           boolean controlaVencimiento, int diasAlerta, BigDecimal precioVenta,
                           boolean activo) {
    }

    public record Almacen(Integer id, String codigo, String nombre, String direccion, boolean activo) {
    }

    public static List<Opcion> almacenesActivos() throws SQLException {
        return Consultas.opciones(
                "SELECT id, codigo || ' - ' || nombre FROM almacen WHERE activo ORDER BY codigo");
    }

    /** Productos activos: id = código, texto = "código - nombre". */
    public static List<Opcion> productosActivos() throws SQLException {
        return Consultas.opciones(
                "SELECT codigo, codigo || ' - ' || nombre FROM producto WHERE activo ORDER BY codigo");
    }

    public static List<Opcion> categorias() throws SQLException {
        return Consultas.opciones("SELECT id, nombre FROM categoria ORDER BY nombre");
    }

    public static Producto producto(String codigo) throws SQLException {
        Object[] f = Consultas.tabla("""
                SELECT p.id, p.codigo, p.nombre, c.nombre, p.unidad_medida, p.metodo_valuacion::text,
                       p.controla_lote, p.controla_vencimiento, p.dias_alerta_vencimiento,
                       p.precio_venta, p.activo
                  FROM producto p LEFT JOIN categoria c ON c.id = p.categoria_id
                 WHERE p.codigo = ?""", codigo).filas().get(0);
        return new Producto(((Number) f[0]).intValue(), (String) f[1], (String) f[2], (String) f[3],
                (String) f[4], (String) f[5], (Boolean) f[6], (Boolean) f[7],
                ((Number) f[8]).intValue(), (BigDecimal) f[9], (Boolean) f[10]);
    }

    public static void guardarProducto(Producto p) throws SQLException {
        Integer categoriaId = null;
        if (p.categoria() != null && !p.categoria().isBlank()) {
            Consultas.ejecutar("INSERT INTO categoria (nombre) VALUES (?) ON CONFLICT (nombre) DO NOTHING",
                    p.categoria().trim());
            categoriaId = ((Number) Consultas.valor("SELECT id FROM categoria WHERE nombre = ?",
                    p.categoria().trim())).intValue();
        }
        if (p.id() == null) {
            Consultas.ejecutar("""
                    INSERT INTO producto (codigo, nombre, categoria_id, unidad_medida, metodo_valuacion,
                                          controla_lote, controla_vencimiento, dias_alerta_vencimiento,
                                          precio_venta, activo)
                    VALUES (?, ?, ?, ?, ?::metodo_valuacion, ?, ?, ?, ?, ?)""",
                    p.codigo().trim().toUpperCase(), p.nombre().trim(), categoriaId, p.unidad(),
                    p.metodo(), p.controlaLote(), p.controlaVencimiento(), p.diasAlerta(),
                    p.precioVenta(), p.activo());
        } else {
            Consultas.ejecutar("""
                    UPDATE producto
                       SET nombre = ?, categoria_id = ?, unidad_medida = ?,
                           metodo_valuacion = ?::metodo_valuacion, controla_lote = ?,
                           controla_vencimiento = ?, dias_alerta_vencimiento = ?,
                           precio_venta = ?, activo = ?
                     WHERE id = ?""",
                    p.nombre().trim(), categoriaId, p.unidad(), p.metodo(), p.controlaLote(),
                    p.controlaVencimiento(), p.diasAlerta(), p.precioVenta(), p.activo(), p.id());
        }
    }

    public static void guardarAlmacen(Almacen a) throws SQLException {
        if (a.id() == null) {
            Consultas.ejecutar("INSERT INTO almacen (codigo, nombre, direccion, activo) VALUES (?, ?, ?, ?)",
                    a.codigo().trim().toUpperCase(), a.nombre().trim(), a.direccion(), a.activo());
        } else {
            Consultas.ejecutar("UPDATE almacen SET codigo = ?, nombre = ?, direccion = ?, activo = ? WHERE id = ?",
                    a.codigo().trim().toUpperCase(), a.nombre().trim(), a.direccion(), a.activo(), a.id());
        }
    }

    /** Stock disponible y precio de venta de un producto en un almacén. */
    public static BigDecimal[] stockYPrecio(int almacenId, String producto) throws SQLException {
        Object[] f = Consultas.tabla("""
                SELECT coalesce(s.cantidad, 0), p.precio_venta, coalesce(s.costo_promedio, 0)
                  FROM producto p
                  LEFT JOIN stock s ON s.producto_id = p.id AND s.almacen_id = ?
                 WHERE p.codigo = ?""", almacenId, producto).filas().get(0);
        return new BigDecimal[] {(BigDecimal) f[0], (BigDecimal) f[1], (BigDecimal) f[2]};
    }

    /** Lotes con existencias de un producto en un almacén (código - vencimiento - cantidad). */
    public static List<Opcion> lotesDisponibles(int almacenId, String producto) throws SQLException {
        return Consultas.opciones("""
                SELECT l.codigo,
                       l.codigo || coalesce('  (vence ' || to_char(l.fecha_vencimiento, 'DD/MM/YYYY') || ')', '')
                       || '  disp. ' || trim(to_char(sum(c.cantidad_disponible), 'FM999G999G990D####'))
                  FROM capa_inventario c
                  JOIN lote l     ON l.id = c.lote_id
                  JOIN producto p ON p.id = c.producto_id
                 WHERE c.almacen_id = ? AND p.codigo = ? AND c.cantidad_disponible > 0
                 GROUP BY l.codigo, l.fecha_vencimiento
                 ORDER BY l.fecha_vencimiento NULLS LAST, l.codigo""", almacenId, producto);
    }
}
