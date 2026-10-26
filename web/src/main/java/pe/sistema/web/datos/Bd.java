package pe.sistema.web.datos;

import java.net.ConnectException;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Acceso a PostgreSQL: consultas tabulares genéricas y ejecución a nombre de un usuario. */
@Component
public class Bd {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public Bd(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** Resultado tabular listo para mostrarse en una tabla HTML o exportarse a CSV. */
    public record Resultado(List<String> columnas, List<Object[]> filas) {

        public int indice(String columna) {
            return columnas.indexOf(columna);
        }

        public Object valor(Object[] fila, String columna) {
            int i = indice(columna);
            return i < 0 ? null : fila[i];
        }
    }

    public Resultado tabla(String sql, Object... parametros) {
        return jdbc.query(sql, rs -> {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            List<String> columnas = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                columnas.add(md.getColumnLabel(i));
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
            return new Resultado(columnas, filas);
        }, parametros);
    }

    /** Lista (id, texto) para desplegables. La consulta debe devolver dos columnas. */
    public List<Opcion> opciones(String sql, Object... parametros) {
        return jdbc.query(sql, (rs, i) -> new Opcion(rs.getObject(1), rs.getString(2)), parametros);
    }

    /**
     * Ejecuta una operación en una transacción registrando al usuario de la aplicación:
     * los documentos guardan quién los creó (columna documento.usuario).
     */
    public <T> T comoUsuario(String usuario, Supplier<T> accion) {
        return tx.execute(estado -> {
            jdbc.queryForObject("SELECT set_config('inventario.usuario', ?, true)", String.class, usuario);
            return accion.get();
        });
    }

    /** Parámetro entero que puede ser nulo (PostgreSQL necesita conocer su tipo). */
    public static SqlParameterValue entero(Integer valor) {
        return new SqlParameterValue(Types.INTEGER, valor);
    }

    public static SqlParameterValue texto(String valor) {
        return new SqlParameterValue(Types.VARCHAR, valor == null || valor.isBlank() ? null : valor.trim());
    }

    /** Traduce una excepción a un mensaje entendible para el usuario. */
    public static String mensaje(Throwable e) {
        if (e instanceof CannotGetJdbcConnectionException || e instanceof DataAccessResourceFailureException) {
            return "No se pudo conectar con la base de datos. Verifique que PostgreSQL esté encendido en el servidor.";
        }
        Throwable t = e;
        while (t.getCause() != null && !(t instanceof SQLException)) {
            t = t.getCause();
        }
        if (t instanceof PSQLException pe) {
            ServerErrorMessage sem = pe.getServerErrorMessage();
            String estado = pe.getSQLState();
            if (pe.getCause() instanceof ConnectException || (estado != null && estado.startsWith("08"))) {
                return "No se pudo conectar con la base de datos. Verifique que PostgreSQL esté encendido en el servidor.";
            }
            if ("23505".equals(estado)) {
                return "Ya existe un registro con ese código o nombre.";
            }
            if ("42501".equals(estado) && sem != null && sem.getMessage().startsWith("permission denied")) {
                return "Permiso denegado por el servidor: " + sem.getMessage();
            }
            if (sem != null) {
                return sem.getMessage();
            }
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }
}
