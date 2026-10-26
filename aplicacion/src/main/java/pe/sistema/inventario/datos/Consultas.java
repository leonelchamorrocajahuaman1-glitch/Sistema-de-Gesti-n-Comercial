package pe.sistema.inventario.datos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Consultas de lectura genéricas sobre las vistas del sistema. */
public final class Consultas {

    private Consultas() {
    }

    /** Resultado tabular listo para mostrarse en una JTable. */
    public record Resultado(List<String> columnas, List<Class<?>> tipos, List<Object[]> filas) {
    }

    public static Resultado tabla(String sql, Object... parametros) throws SQLException {
        try (Connection cn = BaseDatos.abrir();
             PreparedStatement ps = preparar(cn, sql, parametros);
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            List<String> columnas = new ArrayList<>();
            List<Class<?>> tipos = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                columnas.add(md.getColumnLabel(i));
                tipos.add(clase(md.getColumnClassName(i)));
            }
            List<Object[]> filas = new ArrayList<>();
            while (rs.next()) {
                Object[] fila = new Object[n];
                for (int i = 1; i <= n; i++) {
                    Object v = rs.getObject(i);
                    fila[i - 1] = v instanceof java.sql.Timestamp ts ? ts.toLocalDateTime()
                                : v instanceof java.sql.Date d ? d.toLocalDate()
                                : v instanceof org.postgresql.util.PGobject pg ? pg.getValue()
                                : v;
                }
                filas.add(fila);
            }
            return new Resultado(columnas, tipos, filas);
        }
    }

    /** Lista (id, texto) para desplegables. La consulta debe devolver dos columnas. */
    public static List<Opcion> opciones(String sql, Object... parametros) throws SQLException {
        List<Opcion> lista = new ArrayList<>();
        for (Object[] f : tabla(sql, parametros).filas()) {
            lista.add(new Opcion(f[0], String.valueOf(f[1])));
        }
        return lista;
    }

    public static Object valor(String sql, Object... parametros) throws SQLException {
        List<Object[]> filas = tabla(sql, parametros).filas();
        return filas.isEmpty() ? null : filas.get(0)[0];
    }

    public static int ejecutar(String sql, Object... parametros) throws SQLException {
        try (Connection cn = BaseDatos.abrir();
             PreparedStatement ps = preparar(cn, sql, parametros)) {
            return ps.executeUpdate();
        }
    }

    static PreparedStatement preparar(Connection cn, String sql, Object... parametros)
            throws SQLException {
        PreparedStatement ps = cn.prepareStatement(sql);
        for (int i = 0; i < parametros.length; i++) {
            Object p = parametros[i];
            if (p instanceof java.time.LocalDate d) {
                ps.setObject(i + 1, d);
            } else {
                ps.setObject(i + 1, p);
            }
        }
        return ps;
    }

    private static Class<?> clase(String nombre) {
        return switch (nombre) {
            case "java.math.BigDecimal" -> java.math.BigDecimal.class;
            case "java.lang.Integer", "java.lang.Long", "java.lang.Short" -> Long.class;
            case "java.lang.Boolean" -> Boolean.class;
            case "java.sql.Timestamp" -> java.time.LocalDateTime.class;
            case "java.sql.Date" -> java.time.LocalDate.class;
            default -> Object.class;
        };
    }
}
