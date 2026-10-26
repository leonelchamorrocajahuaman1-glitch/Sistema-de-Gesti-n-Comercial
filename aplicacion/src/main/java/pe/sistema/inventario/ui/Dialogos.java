package pe.sistema.inventario.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

import pe.sistema.inventario.datos.BaseDatos;
import pe.sistema.inventario.datos.ConfigConexion;
import pe.sistema.inventario.datos.Operaciones;
import pe.sistema.inventario.datos.Sesion;

/** Ventanas de arranque: conexión al servidor, inicio de sesión y cambio de contraseña. */
public final class Dialogos {

    private Dialogos() {
    }

    private static JDialog dialogo(Window padre, String titulo) {
        JDialog d = new JDialog(padre, titulo, JDialog.ModalityType.APPLICATION_MODAL);
        d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        return d;
    }

    private static JPanel contenido(String titulo, String subtitulo) {
        JPanel p = new JPanel(new BorderLayout(0, 12));
        p.setBorder(BorderFactory.createEmptyBorder(20, 24, 16, 24));
        JPanel cab = new JPanel(new BorderLayout());
        JLabel t = new JLabel(titulo);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 20f));
        t.setForeground(Ui.AZUL_OSCURO);
        cab.add(t, BorderLayout.NORTH);
        if (subtitulo != null) {
            JLabel s = new JLabel("<html><div style='width:320px'>" + subtitulo + "</div></html>");
            s.setForeground(java.awt.Color.GRAY);
            cab.add(s, BorderLayout.CENTER);
        }
        p.add(cab, BorderLayout.NORTH);
        return p;
    }

    // ------------------------------------------------------------ conexión

    /** Configura el servidor. Devuelve true si se guardó una conexión válida. */
    public static boolean configurarConexion(Window padre) {
        ConfigConexion c = ConfigConexion.cargar();
        JDialog d = dialogo(padre, "Conexión al servidor");
        JTextField host = new JTextField(c.getHost(), 20);
        JSpinner puerto = new JSpinner(new SpinnerNumberModel(c.getPuerto(), 1, 65535, 1));
        puerto.setEditor(new JSpinner.NumberEditor(puerto, "#"));
        JTextField base = new JTextField(c.getBaseDatos());
        JTextField usuario = new JTextField(c.getUsuario());
        JPasswordField clave = new JPasswordField(c.getClave());
        JLabel estado = new JLabel(" ");

        JPanel p = contenido("Conexión al servidor",
                "En la computadora servidor use <b>localhost</b>. En las computadoras cliente escriba "
                + "la IP del servidor en la red local (por ejemplo 192.168.1.10).");
        p.add(new Ui.Formulario()
                .campo("Servidor (IP o nombre):", host)
                .campo("Puerto:", puerto)
                .campo("Base de datos:", base)
                .campo("Usuario de red:", usuario)
                .campo("Contraseña de red:", clave), BorderLayout.CENTER);

        boolean[] guardado = {false};
        Runnable leer = () -> {
            c.setHost(host.getText());
            c.setPuerto((Integer) puerto.getValue());
            c.setBaseDatos(base.getText());
            c.setUsuario(usuario.getText());
            c.setClave(new String(clave.getPassword()));
        };
        JButton probar = Ui.boton("Probar conexión", () -> {
            leer.run();
            estado.setText("Conectando con " + c.getHost() + "...");
            estado.setForeground(java.awt.Color.GRAY);
            Ui.tarea(d, () -> BaseDatos.probar(c), version -> {
                estado.setText("✔ Conectado: " + version);
                estado.setForeground(Ui.VERDE);
            });
        });
        JButton guardar = Ui.botonPrincipal("Guardar", () -> {
            leer.run();
            Ui.tarea(d, () -> {
                BaseDatos.probar(c);
                c.guardar();
                return null;
            }, r -> {
                BaseDatos.configurar(c);
                guardado[0] = true;
                d.dispose();
            });
        });
        JPanel sur = new JPanel(new BorderLayout());
        sur.add(estado, BorderLayout.NORTH);
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        botones.add(probar);
        botones.add(guardar);
        sur.add(botones, BorderLayout.SOUTH);
        p.add(sur, BorderLayout.SOUTH);

        d.setContentPane(p);
        d.getRootPane().setDefaultButton(guardar);
        d.pack();
        d.setLocationRelativeTo(padre);
        d.setVisible(true);
        return guardado[0];
    }

    // ------------------------------------------------------------ login

    /** Muestra el inicio de sesión. Devuelve la sesión o null si se canceló. */
    public static Sesion login(Window padre) {
        JDialog d = dialogo(padre, "Sistema de Gestión - Iniciar sesión");
        JTextField usuario = new JTextField(18);
        JPasswordField clave = new JPasswordField(18);
        JLabel servidor = new JLabel();
        Runnable mostrarServidor = () -> servidor.setText("Servidor: " + BaseDatos.config().getHost()
                + ":" + BaseDatos.config().getPuerto() + " / " + BaseDatos.config().getBaseDatos());
        mostrarServidor.run();
        servidor.setForeground(java.awt.Color.GRAY);

        JPanel p = contenido("Sistema de Gestión", "Stock e inventario multialmacén");
        p.add(new Ui.Formulario().campo("Usuario:", usuario).campo("Contraseña:", clave),
                BorderLayout.CENTER);

        Sesion[] resultado = {null};
        JButton ingresar = Ui.botonPrincipal("Ingresar", () -> {
            String u = usuario.getText().trim();
            String c = new String(clave.getPassword());
            if (u.isEmpty() || c.isEmpty()) {
                Ui.error(d, "Ingrese usuario y contraseña.");
                return;
            }
            Ui.tarea(d, () -> Operaciones.login(u, c), r -> {
                switch (r.estado()) {
                    case "OK" -> {
                        Sesion.iniciar(r.sesion());
                        if (r.debeCambiarClave() && !cambiarClave(d, r.sesion().usuario(), c, true)) {
                            Sesion.iniciar(null);
                            return;
                        }
                        resultado[0] = r.sesion();
                        d.dispose();
                    }
                    case "USUARIO_BLOQUEADO" -> Ui.error(d,
                            "Usuario bloqueado por intentos fallidos. Intente en 5 minutos.");
                    case "USUARIO_INACTIVO" -> Ui.error(d, "El usuario está desactivado.");
                    default -> {
                        Ui.error(d, "Usuario o contraseña incorrectos.");
                        clave.setText("");
                        clave.requestFocusInWindow();
                    }
                }
            });
        });
        JButton conexion = Ui.boton("Configurar conexión...", () -> {
            configurarConexion(d);
            mostrarServidor.run();
        });
        JPanel sur = new JPanel(new BorderLayout(0, 8));
        sur.add(servidor, BorderLayout.NORTH);
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        botones.add(conexion);
        botones.add(ingresar);
        sur.add(botones, BorderLayout.SOUTH);
        p.add(sur, BorderLayout.SOUTH);

        d.setContentPane(p);
        d.getRootPane().setDefaultButton(ingresar);
        d.pack();
        d.setLocationRelativeTo(null);
        d.setVisible(true);
        return resultado[0];
    }

    // ------------------------------------------------------------ contraseña

    public static boolean cambiarClave(Window padre, String usuario, String claveActual, boolean obligatorio) {
        JDialog d = dialogo(padre, "Cambiar contraseña");
        JPasswordField actual = new JPasswordField(claveActual == null ? "" : claveActual, 18);
        JPasswordField nueva = new JPasswordField(18);
        JPasswordField repetir = new JPasswordField(18);
        JPanel p = contenido("Cambiar contraseña", obligatorio
                ? "Por seguridad debe definir una nueva contraseña antes de continuar."
                : null);
        Ui.Formulario f = new Ui.Formulario();
        if (claveActual == null) f.campo("Contraseña actual:", actual);
        f.campo("Nueva contraseña:", nueva).campo("Repetir contraseña:", repetir);
        p.add(f, BorderLayout.CENTER);

        boolean[] ok = {false};
        JButton guardar = Ui.botonPrincipal("Guardar", () -> {
            String n = new String(nueva.getPassword());
            if (!n.equals(new String(repetir.getPassword()))) {
                Ui.error(d, "Las contraseñas no coinciden.");
                return;
            }
            String a = new String(actual.getPassword());
            Ui.tarea(d, () -> {
                Operaciones.cambiarClave(usuario, a, n);
                return null;
            }, r -> {
                ok[0] = true;
                Ui.info(d, "Contraseña actualizada.");
                d.dispose();
            });
        });
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        botones.add(guardar);
        p.add(botones, BorderLayout.SOUTH);
        d.setContentPane(p);
        d.getRootPane().setDefaultButton(guardar);
        d.pack();
        d.setLocationRelativeTo(padre);
        d.setVisible(true);
        return ok[0];
    }
}
