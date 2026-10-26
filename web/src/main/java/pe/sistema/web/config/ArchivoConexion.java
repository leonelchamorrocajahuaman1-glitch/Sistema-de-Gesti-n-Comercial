package pe.sistema.web.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.springframework.boot.system.ApplicationHome;

import pe.sistema.web.SistemaWebApplication;

/**
 * Datos de conexión a PostgreSQL, leídos del mismo {@code conexion.properties}
 * que genera el instalador de la base de datos (nunca se guardan en el código).
 * <p>
 * Se busca, en este orden:
 * <ol>
 *   <li>la ruta indicada en la variable de entorno {@code SISTEMA_CONEXION};</li>
 *   <li>junto al archivo {@code sistema-web.jar};</li>
 *   <li>en la carpeta actual y en la carpeta superior (desarrollo: {@code web/..});</li>
 *   <li>en {@code ~/.sistema-gestion/conexion.properties}.</li>
 * </ol>
 */
public record ArchivoConexion(Path origen, String host, int puerto, String baseDatos,
                              String usuario, String clave) {

    public static ArchivoConexion cargar() {
        List<Path> candidatos = new ArrayList<>();
        String variable = System.getenv("SISTEMA_CONEXION");
        if (variable != null && !variable.isBlank()) {
            candidatos.add(Path.of(variable.trim()));
        }
        candidatos.add(new ApplicationHome(SistemaWebApplication.class).getDir().toPath()
                .resolve("conexion.properties"));
        Path actual = Path.of("").toAbsolutePath();
        candidatos.add(actual.resolve("conexion.properties"));
        if (actual.getParent() != null) {
            candidatos.add(actual.getParent().resolve("conexion.properties"));
        }
        candidatos.add(Path.of(System.getProperty("user.home"), ".sistema-gestion", "conexion.properties"));

        for (Path p : candidatos) {
            if (Files.isRegularFile(p)) {
                return leer(p);
            }
        }
        throw new IllegalStateException("No se encontró conexion.properties. Ejecute primero el instalador "
                + "de la base de datos (instalacion\\windows\\INSTALAR.bat) o copie ese archivo junto a "
                + "sistema-web.jar. Rutas revisadas: " + candidatos);
    }

    private static ArchivoConexion leer(Path archivo) {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(archivo)) {
            p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer " + archivo + ": " + e.getMessage(), e);
        }
        return new ArchivoConexion(archivo,
                p.getProperty("host", "localhost").trim(),
                Integer.parseInt(p.getProperty("puerto", "5432").trim()),
                p.getProperty("base_datos", "inventario").trim(),
                p.getProperty("usuario", "app_inventario").trim(),
                p.getProperty("clave", ""));
    }

    public String url() {
        return "jdbc:postgresql://" + host + ":" + puerto + "/" + baseDatos;
    }

    /** Nunca se muestra la contraseña (por ejemplo, en el registro de eventos). */
    @Override
    public String toString() {
        return usuario + "@" + host + ":" + puerto + "/" + baseDatos + " (desde " + origen + ")";
    }
}
