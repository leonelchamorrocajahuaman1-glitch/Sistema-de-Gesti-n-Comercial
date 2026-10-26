package pe.sistema.inventario.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import pe.sistema.inventario.datos.BaseDatos;
import pe.sistema.inventario.datos.Sesion;

/** Ventana principal: menú lateral (según el rol) y área de trabajo. */
public final class VentanaPrincipal extends JFrame {

    /** Sección del menú que se refresca cada vez que se muestra. */
    public interface Seccion {
        void alMostrar();
    }

    private final CardLayout tarjetas = new CardLayout();
    private final JPanel area = new JPanel(tarjetas);
    private final List<JButton> botonesMenu = new ArrayList<>();
    private final JPanel menu = new JPanel(new GridLayout(0, 1, 0, 4));

    public VentanaPrincipal(Sesion s, Runnable alCerrarSesion) {
        super("Sistema de Gestión");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1100, 680));
        setSize(1280, 780);
        setLocationRelativeTo(null);

        JPanel lateral = new JPanel(new BorderLayout());
        lateral.setBackground(Ui.AZUL_OSCURO);
        lateral.setPreferredSize(new Dimension(210, 0));
        JLabel marca = new JLabel("Sistema de Gestión");
        marca.setForeground(Color.WHITE);
        marca.setFont(marca.getFont().deriveFont(Font.BOLD, 17f));
        marca.setBorder(BorderFactory.createEmptyBorder(22, 20, 22, 10));
        lateral.add(marca, BorderLayout.NORTH);
        menu.setOpaque(false);
        menu.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        JPanel envoltura = new JPanel(new BorderLayout());
        envoltura.setOpaque(false);
        envoltura.add(menu, BorderLayout.NORTH);
        lateral.add(envoltura, BorderLayout.CENTER);

        agregar("Inicio", new PanelInicio());
        if (s.esAdministrador()) agregar("Usuarios", new PanelUsuarios());
        agregar("Productos", new PanelProductos(s));
        agregar("Ventas", new PanelMovimiento(PanelMovimiento.Modo.SALIDA, s));
        if (s.gestionaAlmacen()) {
            agregar("Compras", new PanelMovimiento(PanelMovimiento.Modo.ENTRADA, s));
            agregar("Transferencias", new PanelMovimiento(PanelMovimiento.Modo.TRANSFERENCIA, s));
        }
        agregar("Reportes", new PanelReportes(s));
        if (s.esAdministrador()) agregar("Almacenes", new PanelAlmacenes());

        JPanel superior = new JPanel(new BorderLayout());
        superior.setBackground(Color.WHITE);
        superior.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xD0, 0xD7, 0xDE)),
                BorderFactory.createEmptyBorder(8, 16, 8, 12)));
        JLabel servidor = new JLabel("● Conectado a " + BaseDatos.config().getHost()
                + ("localhost".equals(BaseDatos.config().getHost()) ? "  (este equipo es el servidor)" : ""));
        servidor.setForeground(Ui.VERDE);
        superior.add(servidor, BorderLayout.WEST);
        JPanel usuario = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        usuario.setOpaque(false);
        usuario.add(new JLabel(s.nombre() + "  ·  " + s.rol()));
        usuario.add(Ui.boton("Cambiar contraseña", () -> Dialogos.cambiarClave(this, s.usuario(), null, false)));
        usuario.add(Ui.boton("Cerrar sesión", () -> {
            dispose();
            alCerrarSesion.run();
        }));
        superior.add(usuario, BorderLayout.EAST);

        JPanel derecha = new JPanel(new BorderLayout());
        derecha.add(superior, BorderLayout.NORTH);
        area.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
        derecha.add(area, BorderLayout.CENTER);

        getContentPane().add(lateral, BorderLayout.WEST);
        getContentPane().add(derecha, BorderLayout.CENTER);
        mostrar("Inicio");
    }

    private void agregar(String nombre, JComponent panel) {
        area.add(panel, nombre);
        JButton b = new JButton(nombre);
        b.setName(nombre);
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setForeground(Color.WHITE);
        b.setFont(b.getFont().deriveFont(14f));
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setOpaque(true);
        b.setBackground(Ui.AZUL_OSCURO);
        b.setBorder(BorderFactory.createEmptyBorder(9, 14, 9, 14));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(e -> mostrar(nombre));
        b.putClientProperty("panel", panel);
        botonesMenu.add(b);
        menu.add(b);
    }

    /** Muestra una sección del menú (también usado por las capturas de pantalla). */
    public void mostrar(String nombre) {
        for (JButton b : botonesMenu) {
            boolean activo = b.getName().equals(nombre);
            b.setBackground(activo ? Ui.AZUL : Ui.AZUL_OSCURO);
            if (activo && b.getClientProperty("panel") instanceof Seccion sec) {
                sec.alMostrar();
            }
        }
        tarjetas.show(area, nombre);
    }

    public List<String> secciones() {
        return botonesMenu.stream().map(JButton::getName).toList();
    }
}
