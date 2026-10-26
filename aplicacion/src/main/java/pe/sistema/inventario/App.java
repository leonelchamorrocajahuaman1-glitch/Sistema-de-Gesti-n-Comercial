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
        FlatLightLaf.setup();
        UIManager.put("Button.arc", 8);
        UIManager.put("Component.arc", 8);
        UIManager.put("TextComponent.arc", 8);
        SwingUtilities.invokeLater(App::iniciar);
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
