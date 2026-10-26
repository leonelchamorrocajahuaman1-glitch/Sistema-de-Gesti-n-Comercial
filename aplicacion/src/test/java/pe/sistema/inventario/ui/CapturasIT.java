package pe.sistema.inventario.ui;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import com.formdev.flatlaf.FlatLightLaf;

import pe.sistema.inventario.datos.BaseDatos;
import pe.sistema.inventario.datos.ConfigConexion;
import pe.sistema.inventario.datos.Sesion;

/** Genera capturas de cada sección (requiere pantalla, p. ej. Xvfb, y -Dcapturas=dir). */
class CapturasIT {

    @Test
    void capturarSecciones() throws Exception {
        String dir = System.getProperty("capturas");
        assumeTrue(dir != null && System.getenv("INV_DB") != null);
        ConfigConexion c = new ConfigConexion();
        c.setHost("localhost");
        c.setBaseDatos(System.getenv("INV_DB"));
        c.setClave(System.getenv().getOrDefault("INV_CLAVE", ""));
        BaseDatos.configurar(c);
        Sesion.iniciar(new Sesion(1, "admin", "Administrador del sistema", "ADMINISTRADOR", 1));
        Files.createDirectories(Path.of(dir));

        FlatLightLaf.setup();
        VentanaPrincipal[] v = new VentanaPrincipal[1];
        SwingUtilities.invokeAndWait(() -> {
            v[0] = new VentanaPrincipal(Sesion.actual(), () -> { });
            v[0].setSize(1366, 800);
            v[0].setVisible(true);
        });
        for (String seccion : v[0].secciones()) {
            SwingUtilities.invokeAndWait(() -> v[0].mostrar(seccion));
            Thread.sleep(2500);
            BufferedImage img = new BufferedImage(1366, 800, BufferedImage.TYPE_INT_RGB);
            SwingUtilities.invokeAndWait(() -> v[0].getContentPane().paint(img.getGraphics()));
            ImageIO.write(img, "png", new File(dir, seccion.toLowerCase() + ".png"));
        }
        SwingUtilities.invokeAndWait(v[0]::dispose);
    }
}
