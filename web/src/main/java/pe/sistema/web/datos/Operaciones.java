package pe.sistema.web.datos;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Lógica de negocio: invoca los procedimientos almacenados del servidor, donde se
 * validan el stock, se valoriza el kardex y se disparan las alertas.  Cada
 * llamada es atómica y queda registrada a nombre del usuario que la hizo.
 */
@Component
public class Operaciones {

    private final Bd bd;
    private final ObjectMapper json;

    public Operaciones(Bd bd, ObjectMapper json) {
        this.bd = bd;
        this.json = json;
    }

    // ------------------------------------------------------------ movimientos

    public long registrarEntrada(String usuario, String tipo, int almacenId, List<ItemDocumento> items,
                                 String referencia) {
        return documento(usuario, "CALL sp_registrar_entrada(?::tipo_documento, ?, ?::jsonb, ?, NULL, NULL)",
                tipo, almacenId, json(items), Bd.texto(referencia));
    }

    public long registrarSalida(String usuario, String tipo, int almacenId, List<ItemDocumento> items,
                                String referencia) {
        return documento(usuario, "CALL sp_registrar_salida(?::tipo_documento, ?, ?::jsonb, ?, NULL, NULL)",
                tipo, almacenId, json(items), Bd.texto(referencia));
    }

    public long transferir(String usuario, int origenId, int destinoId, List<ItemDocumento> items,
                           String referencia) {
        return documento(usuario, "CALL sp_transferir(?, ?, ?::jsonb, ?, NULL, NULL)",
                origenId, destinoId, json(items), Bd.texto(referencia));
    }

    public long anularDocumento(String usuario, long documentoId, String motivo) {
        return documento(usuario, "CALL sp_anular_documento(?, ?, NULL, NULL)", documentoId, motivo.trim());
    }

    public void configurarStockMinimo(int almacenId, String producto, BigDecimal minimo) {
        bd.jdbc().update("CALL sp_configurar_stock_minimo(?, ?, ?)", almacenId, producto, minimo);
    }

    public void generarAlertasVencimiento() {
        bd.jdbc().execute("CALL sp_generar_alertas_vencimiento()");
    }

    // ------------------------------------------------------------ usuarios

    public record ResultadoLogin(String estado, int id, String usuario, String nombre, String rol,
                                 Integer almacenId, boolean debeCambiarClave) {
    }

    /** Llama a fn_login: la contraseña se verifica con bcrypt dentro de PostgreSQL. */
    public ResultadoLogin login(String usuario, String clave) {
        return bd.jdbc().queryForObject("SELECT * FROM fn_login(?, ?)", (rs, i) -> new ResultadoLogin(
                rs.getString("estado"), rs.getInt("id"), rs.getString("nombre_usuario"),
                rs.getString("nombre_completo"), rs.getString("rol"),
                (Integer) rs.getObject("almacen_id"), rs.getBoolean("debe_cambiar_clave")),
                usuario, clave);
    }

    public void cambiarClave(String usuario, String actual, String nueva) {
        bd.jdbc().update("CALL sp_cambiar_clave(?, ?, ?)", usuario, actual, nueva);
    }

    public void guardarUsuario(Integer id, String usuario, String nombre, String rol,
                               Integer almacenId, boolean activo, String clave) {
        bd.jdbc().update("CALL sp_guardar_usuario(?, ?, ?, ?::rol_usuario, ?, ?, ?, NULL)",
                Bd.entero(id), usuario, nombre, rol, Bd.entero(almacenId), activo,
                // La contraseña no se recorta: los espacios forman parte de ella.
                new SqlParameterValue(Types.VARCHAR, clave == null || clave.isEmpty() ? null : clave));
    }

    /** Estado actual del usuario en la base (para cerrar la sesión si fue desactivado o cambió de rol). */
    public record EstadoUsuario(boolean activo, String rol) {
    }

    public Optional<EstadoUsuario> estadoUsuario(int id) {
        return bd.jdbc().query("SELECT activo, rol::text FROM v_usuarios WHERE id = ?",
                (rs, i) -> new EstadoUsuario(rs.getBoolean(1), rs.getString(2)), id).stream().findFirst();
    }

    // ------------------------------------------------------------ utilidades

    private long documento(String usuario, String sql, Object... parametros) {
        return bd.comoUsuario(usuario, () -> bd.jdbc().queryForObject(sql, Long.class, parametros));
    }

    private String json(List<ItemDocumento> items) {
        try {
            return json.writeValueAsString(items.stream().map(ItemDocumento::json).toList());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
