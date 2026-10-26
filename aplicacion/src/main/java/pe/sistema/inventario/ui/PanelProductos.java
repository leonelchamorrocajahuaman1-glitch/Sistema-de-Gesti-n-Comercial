package pe.sistema.inventario.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.math.BigDecimal;
import java.util.List;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

import pe.sistema.inventario.datos.Consultas;
import pe.sistema.inventario.datos.Maestros;
import pe.sistema.inventario.datos.Opcion;
import pe.sistema.inventario.datos.Operaciones;
import pe.sistema.inventario.datos.Sesion;

/** Catálogo de productos con su stock total. */
final class PanelProductos extends JPanel implements VentanaPrincipal.Seccion {

    private final Ui.ModeloResultado modelo = new Ui.ModeloResultado();
    private final JTable tabla = Ui.tabla(modelo);
    private final JTextField buscar = new JTextField(20);

    PanelProductos(Sesion s) {
        super(new BorderLayout(0, 10));
        JPanel cab = new JPanel(new BorderLayout());
        cab.add(Ui.tituloPanel("Productos"), BorderLayout.WEST);
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        botones.add(new JLabel("Buscar:"));
        botones.add(buscar);
        buscar.addActionListener(e -> alMostrar());
        botones.add(Ui.boton("Buscar", this::alMostrar));
        if (s.gestionaAlmacen()) {
            botones.add(Ui.botonPrincipal("Nuevo producto", () -> editar(null)));
            botones.add(Ui.boton("Editar", () -> {
                String c = seleccionado();
                if (c != null) editar(c);
            }));
            botones.add(Ui.boton("Stock mínimo", this::stockMinimo));
        }
        cab.add(botones, BorderLayout.EAST);
        add(cab, BorderLayout.NORTH);
        add(Ui.desplazable(tabla), BorderLayout.CENTER);
    }

    @Override
    public void alMostrar() {
        String filtro = "%" + buscar.getText().trim() + "%";
        Ui.tarea(this, () -> Consultas.tabla("""
                SELECT codigo, nombre, categoria, unidad_medida AS unidad,
                       metodo_valuacion::text AS valuacion, controla_lote AS lotes,
                       controla_vencimiento AS vence, precio_venta, stock_total, valor_total, activo
                  FROM v_productos
                 WHERE codigo ILIKE ? OR nombre ILIKE ? OR coalesce(categoria, '') ILIKE ?
                 ORDER BY codigo""", filtro, filtro, filtro), modelo::cargar);
    }

    private String seleccionado() {
        int f = tabla.getSelectedRow();
        if (f < 0) {
            Ui.error(this, "Seleccione un producto.");
            return null;
        }
        return (String) modelo.valor(tabla.convertRowIndexToModel(f), "codigo");
    }

    private void editar(String codigo) {
        Ui.tarea(this, () -> new Object[] {
                codigo == null ? null : Maestros.producto(codigo), Maestros.categorias()},
            r -> dialogo((Maestros.Producto) r[0], (List<?>) r[1]));
    }

    private void dialogo(Maestros.Producto p, List<?> categorias) {
        boolean nuevo = p == null;
        JTextField codigo = new JTextField(nuevo ? "" : p.codigo(), 14);
        codigo.setEditable(nuevo);
        JTextField nombre = new JTextField(nuevo ? "" : p.nombre(), 28);
        JComboBox<Object> categoria = new JComboBox<>(categorias.toArray());
        categoria.setEditable(true);
        categoria.setSelectedItem(nuevo ? "" : p.categoria());
        JComboBox<String> unidad = new JComboBox<>(new String[] {"UND", "CJA", "BOL", "BOT", "LAT", "KG", "LT", "PAQ"});
        unidad.setEditable(true);
        unidad.setSelectedItem(nuevo ? "UND" : p.unidad());
        JComboBox<String> metodo = new JComboBox<>(new String[] {"PROMEDIO", "PEPS"});
        if (!nuevo) metodo.setSelectedItem(p.metodo());
        JCheckBox lote = new JCheckBox("Controla lotes", !nuevo && p.controlaLote());
        JCheckBox vence = new JCheckBox("Controla vencimiento (requiere lotes)", !nuevo && p.controlaVencimiento());
        vence.addActionListener(e -> { if (vence.isSelected()) lote.setSelected(true); });
        lote.addActionListener(e -> { if (!lote.isSelected()) vence.setSelected(false); });
        JSpinner dias = new JSpinner(new SpinnerNumberModel(nuevo ? 30 : p.diasAlerta(), 0, 3650, 1));
        JTextField precio = new JTextField(nuevo ? "0.00" : p.precioVenta().toPlainString());
        JCheckBox activo = new JCheckBox("Activo", nuevo || p.activo());

        Ui.Formulario f = new Ui.Formulario()
                .campo("Código:", codigo)
                .campo("Nombre:", nombre)
                .campo("Categoría:", categoria)
                .campo("Unidad:", unidad)
                .campo("Método de valuación:", metodo)
                .campo("", lote)
                .campo("", vence)
                .campo("Días de alerta antes del vencimiento:", dias)
                .campo("Precio de venta (S/):", precio)
                .campo("", activo);
        int r = JOptionPane.showConfirmDialog(this, f, nuevo ? "Nuevo producto" : "Editar producto",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        try {
            BigDecimal pv = Ui.numero(precio.getText(), "Precio de venta");
            Object cat = categoria.getSelectedItem();
            Maestros.Producto datos = new Maestros.Producto(nuevo ? null : p.id(), codigo.getText(),
                    nombre.getText(), cat == null ? null : cat.toString(), (String) unidad.getSelectedItem(),
                    (String) metodo.getSelectedItem(), lote.isSelected(), vence.isSelected(),
                    (Integer) dias.getValue(), pv == null ? BigDecimal.ZERO : pv, activo.isSelected());
            if (datos.codigo().isBlank() || datos.nombre().isBlank()) {
                Ui.error(this, "Código y nombre son obligatorios.");
                return;
            }
            Ui.tarea(this, () -> {
                Maestros.guardarProducto(datos);
                return null;
            }, x -> alMostrar());
        } catch (IllegalArgumentException e) {
            Ui.error(this, e.getMessage());
        }
    }

    private void stockMinimo() {
        String codigo = seleccionado();
        if (codigo == null) return;
        Ui.tarea(this, Maestros::almacenesActivos, almacenes -> {
            JComboBox<Opcion> almacen = new JComboBox<>(almacenes.toArray(Opcion[]::new));
            JTextField minimo = new JTextField(10);
            Ui.Formulario f = new Ui.Formulario()
                    .campo("Producto:", new JLabel(codigo))
                    .campo("Almacén:", almacen)
                    .campo("Stock mínimo:", minimo);
            int r = JOptionPane.showConfirmDialog(this, f, "Stock mínimo", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return;
            try {
                BigDecimal m = Ui.numero(minimo.getText(), "Stock mínimo");
                if (m == null) m = BigDecimal.ZERO;
                BigDecimal valor = m;
                int almacenId = ((Opcion) almacen.getSelectedItem()).idEntero();
                Ui.tarea(this, () -> {
                    Operaciones.configurarStockMinimo(almacenId, codigo, valor);
                    return null;
                }, x -> Ui.info(this, "Stock mínimo actualizado. Las alertas se recalcularon."));
            } catch (IllegalArgumentException e) {
                Ui.error(this, e.getMessage());
            }
        });
    }
}
