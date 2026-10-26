package pe.sistema.inventario.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;

import javax.swing.JCheckBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;

import pe.sistema.inventario.datos.Consultas;
import pe.sistema.inventario.datos.Maestros;

/** Mantenimiento de almacenes (solo administradores). */
final class PanelAlmacenes extends JPanel implements VentanaPrincipal.Seccion {

    private final Ui.ModeloResultado modelo = new Ui.ModeloResultado();
    private final JTable tabla = Ui.tabla(modelo);

    PanelAlmacenes() {
        super(new BorderLayout(0, 10));
        JPanel cab = new JPanel(new BorderLayout());
        cab.add(Ui.tituloPanel("Almacenes"), BorderLayout.WEST);
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        botones.add(Ui.botonPrincipal("Nuevo almacén", () -> editar(null)));
        botones.add(Ui.boton("Editar", () -> {
            int f = tabla.getSelectedRow();
            if (f < 0) {
                Ui.error(this, "Seleccione un almacén.");
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
                SELECT a.id, a.codigo, a.nombre, a.direccion, a.activo,
                       v.productos_con_stock, v.valor_total
                  FROM almacen a JOIN v_valor_inventario_almacen v ON v.almacen = a.codigo
                 ORDER BY a.codigo"""), modelo::cargar);
    }

    private void editar(Integer fila) {
        boolean nuevo = fila == null;
        JTextField codigo = new JTextField(nuevo ? "" : (String) modelo.valor(fila, "codigo"), 10);
        JTextField nombre = new JTextField(nuevo ? "" : (String) modelo.valor(fila, "nombre"), 26);
        JTextField direccion = new JTextField(nuevo ? "" : (String) modelo.valor(fila, "direccion"));
        JCheckBox activo = new JCheckBox("Activo", nuevo || (Boolean) modelo.valor(fila, "activo"));
        Ui.Formulario f = new Ui.Formulario()
                .campo("Código:", codigo)
                .campo("Nombre:", nombre)
                .campo("Dirección:", direccion)
                .campo("", activo);
        int r = JOptionPane.showConfirmDialog(this, f, nuevo ? "Nuevo almacén" : "Editar almacén",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        if (codigo.getText().isBlank() || nombre.getText().isBlank()) {
            Ui.error(this, "Código y nombre son obligatorios.");
            return;
        }
        Integer id = nuevo ? null : ((Number) modelo.valor(fila, "id")).intValue();
        Ui.tarea(this, () -> {
            Maestros.guardarAlmacen(new Maestros.Almacen(id, codigo.getText(), nombre.getText(),
                    direccion.getText(), activo.isSelected()));
            return null;
        }, x -> alMostrar());
    }
}
