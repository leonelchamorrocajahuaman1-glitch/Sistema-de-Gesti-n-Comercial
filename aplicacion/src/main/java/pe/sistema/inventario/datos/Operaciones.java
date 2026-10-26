package pe.sistema.inventario.datos;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Lógica de negocio del lado del cliente: invoca los procedimientos almacenados
 * del servidor, donde se validan el stock, se valoriza el kardex y se disparan
 * las alertas.  Cada llamada es atómica.
 */
public final class Operaciones {

    private Operaciones() {
    }

    // ------------------------------------------------------------ movimientos

    public static long registrarEntrada(String tipo, int almacenId, List<ItemDocumento> items,
                                        String referencia) throws SQLException {
        return llamarDocumento("CALL sp_registrar_entrada(?::tipo_documento, ?, ?::jsonb, ?, NULL, NULL)",
                tipo, almacenId, json(items), vacioANulo(referencia));
    }

    public static long registrarSalida(String tipo, int almacenId, List<ItemDocumento> items,
                                       String referencia) throws SQLException {
        return llamarDocumento("CALL sp_registrar_salida(?::tipo_documento, ?, ?::jsonb, ?, NULL, NULL)",
                tipo, almacenId, json(items), vacioANulo(referencia));
    }

    public static long transferir(int origenId, int destinoId, List<ItemDocumento> items,
                                  String referencia) throws SQLException {
        return llamarDocumento("CALL sp_transferir(?, ?, ?::jsonb, ?, NULL, NULL)",
                origenId, destinoId, json(items), vacioANulo(referencia));
    }

    public static long anularDocumento(long documentoId, String motivo) throws SQLException {
        return llamarDocumento("CALL sp_anular_documento(?, ?, NULL, NULL)", documentoId, motivo);
    }

    public static void configurarStockMinimo(int almacenId, String producto, BigDecimal minimo)
            throws SQLException {
        try (Connection cn = BaseDatos.abrir();
             PreparedStatement ps = Consultas.preparar(cn,
                     "CALL sp_configurar_stock_minimo(?, ?, ?)", almacenId, producto, minimo)) {
            ps.execute();
        }
    }

    public static void generarAlertasVencimiento() throws SQLException {
        try (Connection cn = BaseDatos.abrir();
             PreparedStatement ps = cn.prepareStatement("CALL sp_generar_alertas_vencimiento()")) {
            ps.execute();
        }
    }

    // ------------------------------------------------------------ usuarios

    public record ResultadoLogin(String estado, Sesion sesion, boolean debeCambiarClave) {
    }

    public static ResultadoLogin login(String usuario, String clave) throws SQLException {
        try (Connection cn = BaseDatos.abrir();
             PreparedStatement ps = Consultas.preparar(cn, "SELECT * FROM fn_login(?, ?)", usuario, clave);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            String estado = rs.getString("estado");
            if (!"OK".equals(estado)) {
                return new ResultadoLogin(estado, null, false);
            }
            Integer almacen = (Integer) rs.getObject("almacen_id");
            Sesion s = new Sesion(rs.getInt("id"), rs.getString("nombre_usuario"),
                    rs.getString("nombre_completo"), rs.getString("rol"), almacen);
            return new ResultadoLogin(estado, s, rs.getBoolean("debe_cambiar_clave"));
        }
    }

    public static void cambiarClave(String usuario, String actual, String nueva) throws SQLException {
        try (Connection cn = BaseDatos.abrir();
             PreparedStatement ps = Consultas.preparar(cn, "CALL sp_cambiar_clave(?, ?, ?)",
                     usuario, actual, nueva)) {
            ps.execute();
        }
    }

    public static void guardarUsuario(Integer id, String usuario, String nombre, String rol,
                                      Integer almacenId, boolean activo, String clave)
            throws SQLException {
        try (Connection cn = BaseDatos.abrir();
             PreparedStatement ps = cn.prepareStatement(
                     "CALL sp_guardar_usuario(?, ?, ?, ?::rol_usuario, ?, ?, ?, NULL)")) {
            ps.setObject(1, id, Types.INTEGER);
            ps.setString(2, usuario);
            ps.setString(3, nombre);
            ps.setString(4, rol);
            ps.setObject(5, almacenId, Types.INTEGER);
            ps.setBoolean(6, activo);
            ps.setString(7, vacioANulo(clave));
            ps.execute();
        }
    }

    // ------------------------------------------------------------ utilidades

    private static long llamarDocumento(String sql, Object... parametros) throws SQLException {
        try (Connection cn = BaseDatos.abrir();
             PreparedStatement ps = Consultas.preparar(cn, sql, parametros);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static String json(List<ItemDocumento> items) {
        return items.stream().map(ItemDocumento::json).collect(Collectors.joining(",", "[", "]"));
    }

    private static String vacioANulo(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
