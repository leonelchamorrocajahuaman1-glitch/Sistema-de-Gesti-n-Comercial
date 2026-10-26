package pe.sistema.inventario.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;

import pe.sistema.inventario.datos.ItemDocumento;
import pe.sistema.inventario.datos.Maestros;
import pe.sistema.inventario.datos.Opcion;
import pe.sistema.inventario.datos.Operaciones;
import pe.sistema.inventario.datos.Sesion;

/**
 * Registro de documentos de inventario: ventas y otras salidas, compras y otras
 * entradas, y transferencias entre almacenes.  La validación definitiva (stock,
 * lotes vencidos, valorización) la hace el servidor al registrar.
 */
final class PanelMovimiento extends JPanel implements VentanaPrincipal.Seccion {

    enum Modo { ENTRADA, SALIDA, TRANSFERENCIA }

    private static final Opcion LOTE_AUTOMATICO = new Opcion(null, "Automático (FEFO / PEPS)");

    private final Modo modo;
    private final Sesion sesion;

    private final JComboBox<String> tipo = new JComboBox<>();
    private final JComboBox<Opcion> almacen = new JComboBox<>();
    private final JComboBox<Opcion> destino = new JComboBox<>();
    private final JTextField referencia = new JTextField(14);
    private final JComboBox<Opcion> producto = new JComboBox<>();
    private final JLabel infoProducto = new JLabel(" ");
    private final JTextField cantidad = new JTextField(7);
    private final JTextField importeUnitario = new JTextField(8);
    private final JTextField loteTexto = new JTextField(10);
    private final JComboBox<Opcion> loteCombo = new JComboBox<>();
    private final JTextField vencimiento = new JTextField(9);
    private final JLabel total = new JLabel("Total: S/ 0.00");
    private final List<ItemDocumento> items = new ArrayList<>();
    private final DefaultTableModel modeloItems;
    private boolean cargando;

    PanelMovimiento(Modo modo, Sesion sesion) {
        super(new BorderLayout(0, 12));
        this.modo = modo;
        this.sesion = sesion;

        String titulo;
        String etiquetaImporte;
        switch (modo) {
            case ENTRADA -> {
                titulo = "Compras y entradas";
                etiquetaImporte = "Costo unit.";
                for (String t : new String[] {"COMPRA", "INVENTARIO_INICIAL", "DEVOLUCION_CLIENTE", "AJUSTE_POSITIVO"}) {
                    tipo.addItem(t);
                }
            }
            case SALIDA -> {
                titulo = "Ventas y salidas";
                etiquetaImporte = "Precio unit.";
                tipo.addItem("VENTA");
                if (sesion.gestionaAlmacen()) {
                    for (String t : new String[] {"CONSUMO", "MERMA", "DEVOLUCION_PROVEEDOR", "AJUSTE_NEGATIVO"}) {
                        tipo.addItem(t);
                    }
                }
            }
            default -> {
                titulo = "Transferencias entre almacenes";
                etiquetaImporte = null;
                tipo.addItem("TRANSFERENCIA");
            }
        }
        modeloItems = new DefaultTableModel(new Object[] {"Producto", "Descripción", "Cantidad",
                etiquetaImporte == null ? "-" : etiquetaImporte, "Lote", "Vencimiento", "Importe"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int c) {
                return c == 2 || c == 3 || c == 6 ? BigDecimal.class : Object.class;
            }
        };

        add(Ui.tituloPanel(titulo), BorderLayout.NORTH);

        // --- cabecera del documento
        JPanel documento = fila();
        if (modo != Modo.TRANSFERENCIA) agregar(documento, "Tipo", tipo);
        agregar(documento, modo == Modo.TRANSFERENCIA ? "Almacén origen" : "Almacén", almacen);
        if (modo == Modo.TRANSFERENCIA) agregar(documento, "Almacén destino", destino);
        agregar(documento, modo == Modo.SALIDA ? "Comprobante" : modo == Modo.ENTRADA ? "Factura / guía" : "Guía de remisión",
                referencia);
        rellenar(documento);
        documento.setBorder(BorderFactory.createTitledBorder("Documento"));

        // --- ingreso de líneas
        JPanel linea = fila();
        producto.setPrototypeDisplayValue(new Opcion("", "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"));
        agregar(linea, "Producto", producto);
        agregar(linea, "Cantidad", cantidad);
        if (etiquetaImporte != null) agregar(linea, etiquetaImporte, importeUnitario);
        if (modo == Modo.ENTRADA) {
            agregar(linea, "Lote", loteTexto);
            agregar(linea, "Vence (DD/MM/AAAA)", vencimiento);
        } else {
            loteCombo.setPrototypeDisplayValue(new Opcion("", "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"));
            agregar(linea, "Lote", loteCombo);
        }
        agregar(linea, " ", Ui.botonPrincipal("Agregar", this::agregarItem));
        rellenar(linea);
        infoProducto.setForeground(Ui.AZUL_OSCURO);
        JPanel bloqueLinea = new JPanel(new BorderLayout(0, 4));
        bloqueLinea.add(linea, BorderLayout.NORTH);
        bloqueLinea.add(infoProducto, BorderLayout.SOUTH);
        bloqueLinea.setBorder(BorderFactory.createTitledBorder("Agregar producto"));

        JPanel formulario = new JPanel(new BorderLayout(0, 8));
        formulario.add(documento, BorderLayout.NORTH);
        formulario.add(bloqueLinea, BorderLayout.SOUTH);

        JTable tabla = Ui.tabla(modeloItems);
        JPanel centro = new JPanel(new BorderLayout(0, 8));
        centro.add(formulario, BorderLayout.NORTH);
        centro.add(Ui.desplazable(tabla), BorderLayout.CENTER);
        add(centro, BorderLayout.CENTER);

        // --- pie
        JPanel pie = new JPanel(new BorderLayout());
        JPanel izquierda = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        izquierda.add(Ui.boton("Quitar línea", () -> {
            int f = tabla.getSelectedRow();
            if (f >= 0) {
                int m = tabla.convertRowIndexToModel(f);
                items.remove(m);
                modeloItems.removeRow(m);
                recalcular();
            }
        }));
        izquierda.add(Ui.boton("Limpiar", this::limpiar));
        pie.add(izquierda, BorderLayout.WEST);
        JPanel derecha = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        total.setFont(total.getFont().deriveFont(Font.BOLD, 18f));
        if (modo != Modo.TRANSFERENCIA) derecha.add(total);
        derecha.add(Ui.botonPrincipal(modo == Modo.SALIDA ? "Registrar venta / salida"
                : modo == Modo.ENTRADA ? "Registrar entrada" : "Registrar transferencia", this::registrar));
        pie.add(derecha, BorderLayout.EAST);
        add(pie, BorderLayout.SOUTH);

        almacen.addActionListener(e -> actualizarProducto());
        producto.addActionListener(e -> actualizarProducto());
        tipo.addActionListener(e -> importeUnitario.setEnabled(modo == Modo.ENTRADA || "VENTA".equals(tipo.getSelectedItem())));
    }

    private static JPanel fila() {
        return new JPanel(new GridBagLayout());
    }

    /** Ocupa el espacio sobrante a la derecha para que los campos queden alineados a la izquierda. */
    private static void rellenar(JPanel p) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 99;
        g.weightx = 1;
        p.add(new JLabel(), g);
    }

    private static void agregar(JPanel p, String etiqueta, JComponent c) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = p.getComponentCount() / 2;
        g.gridy = 0;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(2, 0, 0, 12);
        p.add(new JLabel(etiqueta), g);
        g.gridy = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        p.add(c, g);
    }

    @Override
    public void alMostrar() {
        Ui.tarea(this, () -> new Object[] {Maestros.almacenesActivos(), Maestros.productosActivos()}, r -> {
            @SuppressWarnings("unchecked") List<Opcion> almacenes = (List<Opcion>) r[0];
            @SuppressWarnings("unchecked") List<Opcion> productos = (List<Opcion>) r[1];
            cargando = true;
            Object almacenPrevio = almacen.getSelectedItem();
            Object productoPrevio = producto.getSelectedItem();
            almacen.removeAllItems();
            destino.removeAllItems();
            for (Opcion o : almacenes) {
                almacen.addItem(o);
                destino.addItem(o);
            }
            producto.removeAllItems();
            productos.forEach(producto::addItem);
            seleccionar(almacen, almacenPrevio, sesion.almacenId());
            seleccionar(producto, productoPrevio, null);
            if (destino.getItemCount() > 1 && destino.getSelectedItem().equals(almacen.getSelectedItem())) {
                destino.setSelectedIndex(almacen.getSelectedIndex() == 0 ? 1 : 0);
            }
            cargando = false;
            actualizarProducto();
        });
    }

    private static void seleccionar(JComboBox<Opcion> combo, Object previo, Object idPorDefecto) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            Opcion o = combo.getItemAt(i);
            if (previo != null ? o.equals(previo) : idPorDefecto != null && idPorDefecto.equals(o.id())) {
                combo.setSelectedIndex(i);
                return;
            }
        }
    }

    /** Muestra stock, precio y lotes disponibles del producto en el almacén elegido. */
    private void actualizarProducto() {
        if (cargando) return;
        Opcion a = (Opcion) almacen.getSelectedItem();
        Opcion p = (Opcion) producto.getSelectedItem();
        if (a == null || p == null) return;
        String codigo = (String) p.id();
        Ui.tarea(this, () -> new Object[] {
                Maestros.stockYPrecio(a.idEntero(), codigo),
                modo == Modo.ENTRADA ? List.of() : Maestros.lotesDisponibles(a.idEntero(), codigo)},
            r -> {
                BigDecimal[] sp = (BigDecimal[]) r[0];
                infoProducto.setText("Stock disponible en " + a.texto() + ": " + Ui.formato(sp[0])
                        + "     Costo promedio: S/ " + Ui.formato(sp[2])
                        + (modo == Modo.SALIDA ? "     Precio de venta: S/ " + Ui.formato(sp[1]) : ""));
                if (modo == Modo.SALIDA) {
                    importeUnitario.setText(sp[1].setScale(2, RoundingMode.HALF_UP).toPlainString());
                } else if (modo == Modo.ENTRADA && importeUnitario.getText().isBlank() && sp[2].signum() > 0) {
                    importeUnitario.setText(sp[2].setScale(2, RoundingMode.HALF_UP).toPlainString());
                }
                if (modo != Modo.ENTRADA) {
                    loteCombo.removeAllItems();
                    loteCombo.addItem(LOTE_AUTOMATICO);
                    @SuppressWarnings("unchecked") List<Opcion> lotes = (List<Opcion>) r[1];
                    lotes.forEach(loteCombo::addItem);
                }
            });
    }

    private void agregarItem() {
        Opcion p = (Opcion) producto.getSelectedItem();
        if (p == null) {
            Ui.error(this, "Seleccione un producto.");
            return;
        }
        try {
            BigDecimal cant = Ui.numero(cantidad.getText(), "Cantidad");
            if (cant == null || cant.signum() <= 0) {
                Ui.error(this, "Ingrese una cantidad mayor que cero.");
                return;
            }
            BigDecimal importe = Ui.numero(importeUnitario.getText(), "Importe unitario");
            String lote = null;
            LocalDate vence = null;
            if (modo == Modo.ENTRADA) {
                if (importe == null || importe.signum() < 0) {
                    Ui.error(this, "Ingrese el costo unitario.");
                    return;
                }
                lote = loteTexto.getText().isBlank() ? null : loteTexto.getText().trim();
                vence = Ui.fecha(vencimiento.getText(), "Vencimiento");
            } else {
                Opcion l = (Opcion) loteCombo.getSelectedItem();
                lote = l == null ? null : (String) l.id();
            }
            boolean venta = modo == Modo.SALIDA && "VENTA".equals(tipo.getSelectedItem());
            String[] partes = p.texto().split(" - ", 2);
            ItemDocumento item = new ItemDocumento((String) p.id(), partes.length > 1 ? partes[1] : "", cant,
                    modo == Modo.ENTRADA ? importe : null, venta ? importe : null, lote, vence);
            items.add(item);
            BigDecimal unitario = modo == Modo.ENTRADA ? importe : venta ? importe : null;
            modeloItems.addRow(new Object[] {item.producto(), item.descripcion(), cant, unitario,
                    lote == null ? (modo == Modo.ENTRADA ? "" : "Automático") : lote,
                    vence == null ? "" : Ui.formato(vence),
                    unitario == null ? null : unitario.multiply(cant).setScale(2, RoundingMode.HALF_UP)});
            recalcular();
            cantidad.setText("");
            loteTexto.setText("");
            vencimiento.setText("");
            if (modo == Modo.ENTRADA) importeUnitario.setText("");
            cantidad.requestFocusInWindow();
        } catch (IllegalArgumentException e) {
            Ui.error(this, e.getMessage());
        }
    }

    private void recalcular() {
        BigDecimal t = BigDecimal.ZERO;
        for (int i = 0; i < modeloItems.getRowCount(); i++) {
            Object v = modeloItems.getValueAt(i, 6);
            if (v != null) t = t.add((BigDecimal) v);
        }
        total.setText("Total: S/ " + Ui.formato(t.setScale(2, RoundingMode.HALF_UP)));
    }

    private void limpiar() {
        items.clear();
        modeloItems.setRowCount(0);
        referencia.setText("");
        recalcular();
    }

    private void registrar() {
        if (items.isEmpty()) {
            Ui.error(this, "Agregue al menos un producto.");
            return;
        }
        Opcion a = (Opcion) almacen.getSelectedItem();
        Opcion d = (Opcion) destino.getSelectedItem();
        String t = (String) tipo.getSelectedItem();
        String ref = referencia.getText();
        List<ItemDocumento> lineas = List.copyOf(items);
        if (modo == Modo.TRANSFERENCIA && a.equals(d)) {
            Ui.error(this, "El almacén de origen y el de destino deben ser distintos.");
            return;
        }
        Ui.tarea(this, () -> switch (modo) {
            case ENTRADA -> Operaciones.registrarEntrada(t, a.idEntero(), lineas, ref);
            case SALIDA -> Operaciones.registrarSalida(t, a.idEntero(), lineas, ref);
            case TRANSFERENCIA -> Operaciones.transferir(a.idEntero(), d.idEntero(), lineas, ref);
        }, id -> {
            Ui.info(this, "Documento N° " + id + " registrado correctamente.\n"
                    + "El kardex, el stock y las alertas se actualizaron en el servidor.");
            limpiar();
            actualizarProducto();
        });
    }
}
