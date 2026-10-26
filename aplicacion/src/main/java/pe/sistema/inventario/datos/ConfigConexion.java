package pe.sistema.inventario.datos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Datos de conexión al servidor de base de datos.
 * <p>
 * En la computadora servidor el host es {@code localhost}; en cada cliente es
 * la IP del servidor en la red local (por ejemplo {@code 192.168.1.10}).
 * Se guarda en {@code ~/.sistema-gestion/conexion.properties}; si ese archivo no
 * existe se usa el {@code conexion.properties} que el instalador deja junto al .jar.
 */
public final class ConfigConexion {

    private static final Path ARCHIVO =
            Path.of(System.getProperty("user.home"), ".sistema-gestion", "conexion.properties");

    private String host = "localhost";
    private int puerto = 5432;
    private String baseDatos = "inventario";
    private String usuario = "app_inventario";
    private String clave = "";

    public static boolean existe() {
        return origen() != null;
    }

    /** Archivo del usuario o, en su defecto, el que acompaña al ejecutable. */
    private static Path origen() {
        if (Files.exists(ARCHIVO)) {
            return ARCHIVO;
        }
        try {
            Path jar = Path.of(ConfigConexion.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path junto = (Files.isDirectory(jar) ? jar : jar.getParent()).resolve("conexion.properties");
            return Files.exists(junto) ? junto : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static Path archivo() {
        return ARCHIVO;
    }

    public static ConfigConexion cargar() {
        ConfigConexion c = new ConfigConexion();
        Path archivo = origen();
        if (archivo == null) {
            return c;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(archivo)) {
            p.load(in);
        } catch (IOException e) {
            return c;
        }
        c.host = p.getProperty("host", c.host);
        c.puerto = Integer.parseInt(p.getProperty("puerto", String.valueOf(c.puerto)));
        c.baseDatos = p.getProperty("base_datos", c.baseDatos);
        c.usuario = p.getProperty("usuario", c.usuario);
        c.clave = p.getProperty("clave", c.clave);
        return c;
    }

    public void guardar() throws IOException {
        Properties p = new Properties();
        p.setProperty("host", host);
        p.setProperty("puerto", String.valueOf(puerto));
        p.setProperty("base_datos", baseDatos);
        p.setProperty("usuario", usuario);
        p.setProperty("clave", clave);
        Files.createDirectories(ARCHIVO.getParent());
        try (OutputStream out = Files.newOutputStream(ARCHIVO)) {
            p.store(out, "Sistema de Gestion Comercial - conexion al servidor");
        }
    }

    public String url() {
        return "jdbc:postgresql://" + host + ":" + puerto + "/" + baseDatos;
    }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host.trim(); }
    public int getPuerto() { return puerto; }
    public void setPuerto(int puerto) { this.puerto = puerto; }
    public String getBaseDatos() { return baseDatos; }
    public void setBaseDatos(String baseDatos) { this.baseDatos = baseDatos.trim(); }
    public String getUsuario() { return usuario; }
    public void setUsuario(String usuario) { this.usuario = usuario.trim(); }
    public String getClave() { return clave; }
    public void setClave(String clave) { this.clave = clave; }
}
