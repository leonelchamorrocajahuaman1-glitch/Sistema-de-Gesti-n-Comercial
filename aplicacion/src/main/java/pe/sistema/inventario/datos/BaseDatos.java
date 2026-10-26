package pe.sistema.inventario.datos;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;

/**
 * Servicio de acceso a datos (JDBC).  Abre una conexión por operación contra el
 * servidor configurado; así un cliente en la LAN tolera cortes momentáneos de red
 * sin dejar conexiones colgadas.
 */
public final class BaseDatos {

    private static ConfigConexion config = ConfigConexion.cargar();
    private static volatile String usuarioAplicacion;

    private BaseDatos() {
    }

    public static ConfigConexion config() {
        return config;
    }

    public static void configurar(ConfigConexion nueva) {
        config = nueva;
    }

    /** Usuario de la aplicación que queda registrado en cada documento. */
    public static void setUsuarioAplicacion(String usuario) {
        usuarioAplicacion = usuario;
    }

    public static Connection abrir() throws SQLException {
        return abrir(config);
    }

    public static Connection abrir(ConfigConexion c) throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", c.getUsuario());
        p.setProperty("password", c.getClave());
        p.setProperty("connectTimeout", "5");
        p.setProperty("socketTimeout", "120");
        p.setProperty("ApplicationName", "SistemaGestion@" + nombreEquipo());
        p.setProperty("options", "-c search_path=inventario,public");
        Connection cn = DriverManager.getConnection(c.url(), p);
        String usuario = usuarioAplicacion;
        if (usuario != null) {
            try (PreparedStatement ps = cn.prepareStatement(
                    "SELECT set_config('inventario.usuario', ?, false)")) {
                ps.setString(1, usuario);
                ps.execute();
            }
        }
        return cn;
    }

    /** Verifica la conexión y que el esquema del sistema esté instalado. */
    public static String probar(ConfigConexion c) throws SQLException {
        try (Connection cn = abrir(c);
             PreparedStatement ps = cn.prepareStatement(
                     "SELECT version(), (SELECT count(*) FROM inventario.almacen)");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1).split(",")[0];
        }
    }

    /** Traduce una excepción a un mensaje entendible para el usuario. */
    public static String mensaje(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && !(t instanceof SQLException)) {
            t = t.getCause();
        }
        if (t instanceof PSQLException pe) {
            ServerErrorMessage sem = pe.getServerErrorMessage();
            String estado = pe.getSQLState();
            if (PSQLState.CONNECTION_UNABLE_TO_CONNECT.getState().equals(estado)
                    || pe.getCause() instanceof ConnectException) {
                return "No se pudo conectar al servidor " + config.getHost() + ":" + config.getPuerto()
                        + ".\nVerifique que la computadora servidor esté encendida, en la misma red"
                        + " y con PostgreSQL aceptando conexiones.";
            }
            if ("28P01".equals(estado) || "28000".equals(estado)) {
                return "El servidor rechazó el usuario o la contraseña de la base de datos.";
            }
            if ("3D000".equals(estado)) {
                return "La base de datos \"" + config.getBaseDatos() + "\" no existe en el servidor.";
            }
            if ("42501".equals(estado) && sem != null && sem.getMessage().startsWith("permission denied")) {
                return "Permiso denegado por el servidor: " + sem.getMessage();
            }
            if (sem != null) {
                return sem.getMessage();
            }
        }
        if (t instanceof UnknownHostException) {
            return "No se encuentra el servidor \"" + t.getMessage() + "\".";
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    private static String nombreEquipo() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "desconocido";
        }
    }
}
