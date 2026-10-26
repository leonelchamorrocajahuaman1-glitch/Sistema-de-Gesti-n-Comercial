package pe.sistema.web.datos;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;

/** Mantenimiento de almacenes, categorías y productos. */
@Component
public class Maestros {

    private final Bd bd;

    public Maestros(Bd bd) {
        this.bd = bd;
    }

    public record Producto(Integer id, String codigo, String nombre, String categoria,
                           String unidad, String metodo, boolean controlaLote,
                           boolean controlaVencimiento, int diasAlerta, BigDecimal precioVenta,
                           boolean activo) {

        public static Producto nuevo() {
            return new Producto(null, "", "", "", "UND", "PROMEDIO", false, false, 30, BigDecimal.ZERO, true);
        }
    }

    public record Almacen(Integer id, String codigo, String nombre, String direccion, boolean activo) {

        public static Almacen nuevo() {
            return new Almacen(null, "", "", "", true);
        }
    }

    /** Stock disponible, precio de venta y costo promedio de un producto en un almacén. */
    public record StockPrecio(BigDecimal stock, BigDecimal precio, BigDecimal costo) {
    }

    public List<Opcion> almacenesActivos() {
        return bd.opciones("SELECT id, codigo || ' - ' || nombre FROM almacen WHERE activo ORDER BY codigo");
    }

    /** Productos activos: id = código, texto = "código - nombre". */
    public List<Opcion> productosActivos() {
        return bd.opciones("SELECT codigo, codigo || ' - ' || nombre FROM producto WHERE activo ORDER BY codigo");
    }

    public List<String> categorias() {
        return bd.jdbc().queryForList("SELECT nombre FROM categoria ORDER BY nombre", String.class);
    }

    public Producto producto(String codigo) {
        return bd.jdbc().queryForObject("""
                SELECT p.id, p.codigo, p.nombre, c.nombre, p.unidad_medida, p.metodo_valuacion::text,
                       p.controla_lote, p.controla_vencimiento, p.dias_alerta_vencimiento,
                       p.precio_venta, p.activo
                  FROM producto p LEFT JOIN categoria c ON c.id = p.categoria_id
                 WHERE p.codigo = ?""",
                (rs, i) -> new Producto(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getBoolean(8),
                        rs.getInt(9), rs.getBigDecimal(10), rs.getBoolean(11)),
                codigo);
    }

    public void guardarProducto(Producto p) {
        Integer categoriaId = null;
        if (p.categoria() != null && !p.categoria().isBlank()) {
            bd.jdbc().update("INSERT INTO categoria (nombre) VALUES (?) ON CONFLICT (nombre) DO NOTHING",
                    p.categoria().trim());
            categoriaId = bd.jdbc().queryForObject("SELECT id FROM categoria WHERE nombre = ?", Integer.class,
                    p.categoria().trim());
        }
        if (p.id() == null) {
            bd.jdbc().update("""
                    INSERT INTO producto (codigo, nombre, categoria_id, unidad_medida, metodo_valuacion,
                                          controla_lote, controla_vencimiento, dias_alerta_vencimiento,
                                          precio_venta, activo)
                    VALUES (?, ?, ?, ?, ?::metodo_valuacion, ?, ?, ?, ?, ?)""",
                    p.codigo().trim().toUpperCase(), p.nombre().trim(), Bd.entero(categoriaId), p.unidad(),
                    p.metodo(), p.controlaLote(), p.controlaVencimiento(), p.diasAlerta(),
                    p.precioVenta(), p.activo());
        } else {
            bd.jdbc().update("""
                    UPDATE producto
                       SET nombre = ?, categoria_id = ?, unidad_medida = ?,
                           metodo_valuacion = ?::metodo_valuacion, controla_lote = ?,
                           controla_vencimiento = ?, dias_alerta_vencimiento = ?,
                           precio_venta = ?, activo = ?
                     WHERE id = ?""",
                    p.nombre().trim(), Bd.entero(categoriaId), p.unidad(), p.metodo(), p.controlaLote(),
                    p.controlaVencimiento(), p.diasAlerta(), p.precioVenta(), p.activo(), p.id());
        }
    }

    public Almacen almacen(int id) {
        return bd.jdbc().queryForObject("SELECT id, codigo, nombre, direccion, activo FROM almacen WHERE id = ?",
                (rs, i) -> new Almacen(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5)),
                id);
    }

    public void guardarAlmacen(Almacen a) {
        if (a.id() == null) {
            bd.jdbc().update("INSERT INTO almacen (codigo, nombre, direccion, activo) VALUES (?, ?, ?, ?)",
                    a.codigo().trim().toUpperCase(), a.nombre().trim(), Bd.texto(a.direccion()), a.activo());
        } else {
            bd.jdbc().update("UPDATE almacen SET codigo = ?, nombre = ?, direccion = ?, activo = ? WHERE id = ?",
                    a.codigo().trim().toUpperCase(), a.nombre().trim(), Bd.texto(a.direccion()), a.activo(), a.id());
        }
    }

    public StockPrecio stockYPrecio(int almacenId, String producto) {
        return bd.jdbc().queryForObject("""
                SELECT coalesce(s.cantidad, 0), p.precio_venta, coalesce(s.costo_promedio, 0)
                  FROM producto p
                  LEFT JOIN stock s ON s.producto_id = p.id AND s.almacen_id = ?
                 WHERE p.codigo = ?""",
                (rs, i) -> new StockPrecio(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)),
                almacenId, producto);
    }

    /** Lotes con existencias de un producto en un almacén (código, "código (vence ...) disp. N"). */
    public List<Opcion> lotesDisponibles(int almacenId, String producto) {
        return bd.opciones("""
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
