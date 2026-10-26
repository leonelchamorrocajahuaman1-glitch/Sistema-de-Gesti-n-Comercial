package pe.sistema.inventario;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatLightLaf;

import pe.sistema.inventario.datos.BaseDatos;
import pe.sistema.inventario.datos.ConfigConexion;
import pe.sistema.inventario.datos.Sesion;
import pe.sistema.inventario.ui.Dialogos;
import pe.sistema.inventario.ui.VentanaPrincipal;

/**
 * Aplicación de escritorio del Sistema de Gestión Comercial.
 * <p>
 * El mismo programa se instala en el servidor y en los clientes; solo cambia la
 * dirección del servidor en la configuración de conexión.
 */
public final class App {

    private App() {
    }

    public static void main(String[] args) {
        // javaw no tiene consola: cualquier error inesperado se muestra y se registra.
        Thread.setDefaultUncaughtExceptionHandler((hilo, error) -> informarError(error));
        FlatLightLaf.setup();
        UIManager.put("Button.arc", 8);
        UIManager.put("Component.arc", 8);
        UIManager.put("TextComponent.arc", 8);
        SwingUtilities.invokeLater(App::iniciar);
    }

    private static void informarError(Throwable error) {
        java.nio.file.Path log = ConfigConexion.archivo().resolveSibling("error.log");
        try {
            java.nio.file.Files.createDirectories(log.getParent());
            java.io.StringWriter sw = new java.io.StringWriter();
            error.printStackTrace(new java.io.PrintWriter(sw));
            java.nio.file.Files.writeString(log, java.time.LocalDateTime.now() + "\n" + sw + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException ignorado) {
            // sin registro en disco; el mensaje en pantalla basta
        }
        javax.swing.JOptionPane.showMessageDialog(null,
                "Ocurrió un error inesperado:\n" + BaseDatos.mensaje(error) + "\n\nDetalle guardado en:\n" + log,
                "Sistema de Gestión", javax.swing.JOptionPane.ERROR_MESSAGE);
    }

    static void iniciar() {
        if (!ConfigConexion.existe() && !Dialogos.configurarConexion(null)) {
            System.exit(0);
        }
        BaseDatos.configurar(ConfigConexion.cargar());
        Sesion s = Dialogos.login(null);
        if (s == null) {
            System.exit(0);
        }
        new VentanaPrincipal(s, () -> {
            Sesion.iniciar(null);
            iniciar();
        }).setVisible(true);
    }
}
