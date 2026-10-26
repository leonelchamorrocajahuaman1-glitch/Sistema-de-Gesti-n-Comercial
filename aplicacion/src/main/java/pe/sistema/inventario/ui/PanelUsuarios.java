package pe.sistema.inventario.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTable;
import javax.swing.JTextField;

import pe.sistema.inventario.datos.Consultas;
import pe.sistema.inventario.datos.Maestros;
import pe.sistema.inventario.datos.Opcion;
import pe.sistema.inventario.datos.Operaciones;

/** Administración de usuarios (solo administradores). */
final class PanelUsuarios extends JPanel implements VentanaPrincipal.Seccion {

    private static final String[] ROLES = {"ADMINISTRADOR", "ALMACENERO", "VENDEDOR"};

    private final Ui.ModeloResultado modelo = new Ui.ModeloResultado();
    private final JTable tabla = Ui.tabla(modelo);

    PanelUsuarios() {
        super(new BorderLayout(0, 10));
        JPanel cab = new JPanel(new BorderLayout());
        cab.add(Ui.tituloPanel("Usuarios"), BorderLayout.WEST);
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        botones.add(Ui.botonPrincipal("Nuevo usuario", () -> editar(null)));
        botones.add(Ui.boton("Editar", () -> {
            int f = tabla.getSelectedRow();
            if (f < 0) {
                Ui.error(this, "Seleccione un usuario.");
                return;
            }
            editar(tabla.convertRowIndexToModel(f));
        }));
        cab.add(botones, BorderLayout.EAST);
        add(cab, BorderLayout.NORTH);
        add(Ui.desplazable(tabla), BorderLayout.CENTER);
    }

    @Override
    public void alMostrar() {
        Ui.tarea(this, () -> Consultas.tabla("""
                SELECT id, nombre_usuario AS usuario, nombre_completo, rol::text AS rol, almacen,
                       activo, debe_cambiar_clave, ultimo_acceso
                  FROM v_usuarios ORDER BY nombre_usuario"""), modelo::cargar);
    }

    private void editar(Integer fila) {
        Ui.tarea(this, Maestros::almacenesActivos, almacenes -> dialogo(fila, almacenes));
    }

    private void dialogo(Integer fila, List<Opcion> almacenes) {
        boolean nuevo = fila == null;
        JTextField usuario = new JTextField(nuevo ? "" : (String) modelo.valor(fila, "usuario"), 18);
        JTextField nombre = new JTextField(nuevo ? "" : (String) modelo.valor(fila, "nombre_completo"));
        JComboBox<String> rol = new JComboBox<>(ROLES);
        List<Opcion> opcionesAlmacen = new ArrayList<>();
        opcionesAlmacen.add(new Opcion(null, "(todos)"));
        opcionesAlmacen.addAll(almacenes);
        JComboBox<Opcion> almacen = new JComboBox<>(opcionesAlmacen.toArray(Opcion[]::new));
        JCheckBox activo = new JCheckBox("Activo", true);
        JPasswordField clave = new JPasswordField();
        if (!nuevo) {
            rol.setSelectedItem(modelo.valor(fila, "rol"));
            Object alm = modelo.valor(fila, "almacen");
            for (Opcion o : opcionesAlmacen) {
                if (alm != null && o.texto().startsWith(alm + " ")) almacen.setSelectedItem(o);
            }
            activo.setSelected((Boolean) modelo.valor(fila, "activo"));
        }
        Ui.Formulario f = new Ui.Formulario()
                .campo("Usuario:", usuario)
                .campo("Nombre completo:", nombre)
                .campo("Rol:", rol)
                .campo("Almacén por defecto:", almacen)
                .campo("", activo)
                .campo(nuevo ? "Contraseña inicial:" : "Restablecer contraseña:", clave);
        int r = JOptionPane.showConfirmDialog(this, f, nuevo ? "Nuevo usuario" : "Editar usuario",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;

        Integer id = nuevo ? null : ((Number) modelo.valor(fila, "id")).intValue();
        Integer almacenId = ((Opcion) almacen.getSelectedItem()).idEntero();
        String c = new String(clave.getPassword());
        Ui.tarea(this, () -> {
            Operaciones.guardarUsuario(id, usuario.getText(), nombre.getText(),
                    (String) rol.getSelectedItem(), almacenId, activo.isSelected(), c);
            return null;
        }, x -> {
            if (!c.isEmpty()) {
                Ui.info(this, "El usuario deberá cambiar la contraseña en su próximo ingreso.");
            }
            alMostrar();
        });
    }
}
