package pe.sistema.inventario.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;

import pe.sistema.inventario.datos.Consultas;
import pe.sistema.inventario.datos.Maestros;
import pe.sistema.inventario.datos.Opcion;
import pe.sistema.inventario.datos.Operaciones;
import pe.sistema.inventario.datos.Sesion;

/** Reportes: kardex valorizado, stock, lotes, ventas, documentos y alertas. */
final class PanelReportes extends JPanel implements VentanaPrincipal.Seccion {

    private enum Reporte {
        STOCK("Stock valorizado"),
        LOTES("Stock por lote y vencimiento"),
        KARDEX("Kardex valorizado"),
        VENTAS("Ventas y márgenes"),
        DOCUMENTOS("Documentos registrados"),
        ALERTAS("Alertas activas"),
        INTEGRIDAD("Verificación de integridad");

        final String titulo;

        Reporte(String titulo) {
            this.titulo = titulo;
        }

        @Override
        public String toString() {
            return titulo;
        }
    }

    private final Sesion sesion;
    private final JComboBox<Reporte> reporte = new JComboBox<>(Reporte.values());
    private final JComboBox<Opcion> almacen = new JComboBox<>();
    private final JComboBox<Opcion> producto = new JComboBox<>();
    private final JTextField desde = new JTextField(9);
    private final JTextField hasta = new JTextField(9);
    private final JButton anular;
    private final Ui.ModeloResultado modelo = new Ui.ModeloResultado();
    private final JTable tabla = Ui.tabla(modelo);

    PanelReportes(Sesion sesion) {
        super(new BorderLayout(0, 10));
        this.sesion = sesion;
        add(Ui.tituloPanel("Reportes"), BorderLayout.NORTH);

        LocalDate hoy = LocalDate.now();
        desde.setText(Ui.formato(hoy.withDayOfMonth(1)));
        hasta.setText(Ui.formato(hoy));

        almacen.setPrototypeDisplayValue(new Opcion(null, "XXXXXXXXXXXXXXXXXXXXX"));
        producto.setPrototypeDisplayValue(new Opcion(null, "XXXXXXXXXXXXXXXXXXXXXXX"));
        JPanel fila1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        fila1.add(new JLabel("Reporte:"));
        fila1.add(reporte);
        fila1.add(new JLabel("Almacén:"));
        fila1.add(almacen);
        fila1.add(new JLabel("Producto:"));
        fila1.add(producto);
        JPanel filtros = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filtros.add(new JLabel("Desde:"));
        filtros.add(desde);
        filtros.add(new JLabel("Hasta:"));
        filtros.add(hasta);
        filtros.add(Ui.botonPrincipal("Generar", this::generar));
        filtros.add(Ui.boton("Exportar CSV", () ->
                Ui.exportarCsv(this, modelo.datos(), reporte.getSelectedItem().toString())));
        anular = Ui.boton("Anular documento", this::anular);
        anular.setVisible(false);
        filtros.add(anular);
        reporte.addActionListener(e -> {
            anular.setVisible(reporte.getSelectedItem() == Reporte.DOCUMENTOS && sesion.esAdministrador());
            generar();
        });

        JPanel barra = new JPanel(new java.awt.GridLayout(2, 1, 0, 6));
        barra.add(fila1);
        barra.add(filtros);
        JPanel centro = new JPanel(new BorderLayout(0, 8));
        centro.add(barra, BorderLayout.NORTH);
        centro.add(Ui.desplazable(tabla), BorderLayout.CENTER);
        add(centro, BorderLayout.CENTER);
    }

    @Override
    public void alMostrar() {
        Ui.tarea(this, () -> new Object[] {Maestros.almacenesActivos(), Maestros.productosActivos()}, r -> {
            @SuppressWarnings("unchecked") List<Opcion> almacenes = (List<Opcion>) r[0];
            @SuppressWarnings("unchecked") List<Opcion> productos = (List<Opcion>) r[1];
            almacen.removeAllItems();
            almacen.addItem(new Opcion(null, "(todos)"));
            almacenes.forEach(almacen::addItem);
            producto.removeAllItems();
            producto.addItem(new Opcion(null, "(todos)"));
            productos.forEach(producto::addItem);
            generar();
        });
    }

    private void generar() {
        Reporte r = (Reporte) reporte.getSelectedItem();
        Opcion a = (Opcion) almacen.getSelectedItem();
        Opcion p = (Opcion) producto.getSelectedItem();
        if (a == null || p == null) return;
        String codAlmacen = a.id() == null ? null : a.texto().split(" - ")[0];
        String codProducto = (String) p.id();
        OffsetDateTime ini;
        OffsetDateTime fin;
        try {
            LocalDate d = Ui.fecha(desde.getText(), "Desde");
            LocalDate h = Ui.fecha(hasta.getText(), "Hasta");
            ZoneId z = ZoneId.systemDefault();
            ini = d == null ? OffsetDateTime.parse("1900-01-01T00:00Z") : d.atStartOfDay(z).toOffsetDateTime();
            fin = h == null ? OffsetDateTime.parse("2999-12-31T00:00Z") : h.atTime(LocalTime.MAX).atZone(z).toOffsetDateTime();
        } catch (IllegalArgumentException e) {
            Ui.error(this, e.getMessage());
            return;
        }
        if (r == Reporte.KARDEX && (a.id() == null || codProducto == null)) {
            Ui.error(this, "Para el kardex seleccione un almacén y un producto.");
            return;
        }
        Ui.tarea(this, () -> switch (r) {
            case STOCK -> Consultas.tabla("""
                    SELECT almacen, producto, descripcion, unidad_medida AS unidad, metodo_valuacion::text AS metodo,
                           cantidad, costo_unitario, valor_total, stock_minimo, bajo_minimo
                      FROM v_stock_valorizado
                     WHERE (?::text IS NULL OR almacen = ?) AND (?::text IS NULL OR producto = ?)
                     ORDER BY almacen, producto""", codAlmacen, codAlmacen, codProducto, codProducto);
            case LOTES -> Consultas.tabla("""
                    SELECT almacen, producto, lote, fecha_vencimiento, dias_para_vencer, cantidad, valor, estado
                      FROM v_stock_por_lote
                     WHERE (?::text IS NULL OR almacen = ?) AND (?::text IS NULL OR producto = ?)
                     ORDER BY fecha_vencimiento NULLS LAST, almacen, producto""",
                    codAlmacen, codAlmacen, codProducto, codProducto);
            case KARDEX -> Consultas.tabla("SELECT * FROM fn_reporte_kardex(?, ?, ?, ?)",
                    codProducto, a.idEntero(), ini, fin);
            case VENTAS -> Consultas.tabla("""
                    SELECT fecha, documento_id AS documento, referencia AS comprobante, usuario, almacen,
                           producto, descripcion, cantidad, precio_unitario, importe, costo, margen
                      FROM v_ventas
                     WHERE fecha BETWEEN ? AND ?
                       AND (?::text IS NULL OR almacen = ?) AND (?::text IS NULL OR producto = ?)
                     ORDER BY fecha""", ini, fin, codAlmacen, codAlmacen, codProducto, codProducto);
            case DOCUMENTOS -> Consultas.tabla("""
                    SELECT id AS documento, fecha, tipo::text AS tipo, estado::text AS estado, almacen,
                           almacen_destino AS destino, referencia, usuario, lineas, costo_total, observacion
                      FROM v_documentos
                     WHERE fecha BETWEEN ? AND ?
                       AND (?::text IS NULL OR almacen = ? OR almacen_destino = ?)
                     ORDER BY id DESC""", ini, fin, codAlmacen, codAlmacen, codAlmacen);
            case ALERTAS -> Consultas.tabla("""
                    SELECT tipo::text AS tipo, almacen, producto, lote, mensaje, creada_en
                      FROM v_alertas_activas
                     WHERE (?::text IS NULL OR almacen = ?) AND (?::text IS NULL OR producto = ?)
                     ORDER BY tipo, almacen""", codAlmacen, codAlmacen, codProducto, codProducto);
            case INTEGRIDAD -> Consultas.tabla("SELECT * FROM fn_verificar_integridad()");
        }, res -> {
            modelo.cargar(res);
            if (r == Reporte.INTEGRIDAD && res.filas().isEmpty()) {
                Ui.info(this, "Sin inconsistencias: stock, capas y kardex cuadran.");
            }
        });
    }

    private void anular() {
        int f = tabla.getSelectedRow();
        if (f < 0) {
            Ui.error(this, "Seleccione un documento.");
            return;
        }
        int m = tabla.convertRowIndexToModel(f);
        long id = ((Number) modelo.valor(m, "documento")).longValue();
        String motivo = JOptionPane.showInputDialog(this,
                "Motivo de la anulación del documento N° " + id + " (" + modelo.valor(m, "tipo") + "):",
                "Anular documento", JOptionPane.WARNING_MESSAGE);
        if (motivo == null || motivo.isBlank()) return;
        Ui.tarea(this, () -> Operaciones.anularDocumento(id, motivo), anulacion -> {
            Ui.info(this, "Documento anulado. Se generó el documento de reversión N° " + anulacion + ".");
            generar();
        });
    }
}
