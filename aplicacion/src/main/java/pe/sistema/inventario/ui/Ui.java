package pe.sistema.inventario.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import pe.sistema.inventario.datos.BaseDatos;
import pe.sistema.inventario.datos.Consultas;

/** Utilidades comunes de la interfaz. */
public final class Ui {

    public static final Color AZUL = new Color(0x1F, 0x6F, 0xEB);
    public static final Color AZUL_OSCURO = new Color(0x0B, 0x2A, 0x4A);
    public static final Color VERDE = new Color(0x1A, 0x7F, 0x37);
    public static final Color ROJO = new Color(0xC6, 0x28, 0x28);
    public static final Color AMBAR = new Color(0xB2, 0x6A, 0x00);

    private static final DecimalFormat NUMERO =
            new DecimalFormat("#,##0.00##", DecimalFormatSymbols.getInstance(Locale.US));
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private Ui() {
    }

    // ------------------------------------------------------------------ tareas

    /**
     * Ejecuta una operación de base de datos fuera del hilo de la interfaz y
     * muestra el resultado (o el error) al terminar.
     */
    public static <T> void tarea(Component origen, Callable<T> trabajo, Consumer<T> alTerminar) {
        Component raiz = origen == null ? null : SwingUtilities.getRoot(origen);
        if (raiz != null) raiz.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return trabajo.call();
            }

            @Override
            protected void done() {
                if (raiz != null) raiz.setCursor(Cursor.getDefaultCursor());
                try {
                    T r = get();
                    if (alTerminar != null) alTerminar.accept(r);
                } catch (java.util.concurrent.ExecutionException e) {
                    error(origen, e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    public static void error(Component c, Throwable e) {
        JOptionPane.showMessageDialog(c, BaseDatos.mensaje(e), "Error", JOptionPane.ERROR_MESSAGE);
    }

    public static void error(Component c, String mensaje) {
        JOptionPane.showMessageDialog(c, mensaje, "Atención", JOptionPane.WARNING_MESSAGE);
    }

    public static void info(Component c, String mensaje) {
        JOptionPane.showMessageDialog(c, mensaje, "Sistema de Gestión", JOptionPane.INFORMATION_MESSAGE);
    }

    public static boolean confirmar(Component c, String mensaje) {
        return JOptionPane.showConfirmDialog(c, mensaje, "Confirmar", JOptionPane.YES_NO_OPTION)
                == JOptionPane.YES_OPTION;
    }

    // ------------------------------------------------------------------ formato

    public static String formato(Object v) {
        if (v == null) return "";
        if (v instanceof BigDecimal b) return NUMERO.format(b);
        if (v instanceof Double d) return NUMERO.format(d);
        if (v instanceof LocalDateTime t) return FECHA_HORA.format(t);
        if (v instanceof java.time.OffsetDateTime t) return FECHA_HORA.format(t.toLocalDateTime());
        if (v instanceof LocalDate d) return FECHA.format(d);
        if (v instanceof Boolean b) return b ? "Sí" : "No";
        return v.toString();
    }

    /** Convierte texto a número aceptando coma o punto decimal. Null si vacío. */
    public static BigDecimal numero(String texto, String campo) {
        if (texto == null || texto.isBlank()) return null;
        try {
            return new BigDecimal(texto.trim().replace(",", "."));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("El campo \"" + campo + "\" debe ser numérico.");
        }
    }

    public static LocalDate fecha(String texto, String campo) {
        if (texto == null || texto.isBlank()) return null;
        String t = texto.trim();
        try {
            if (t.matches("\\d{2}/\\d{2}/\\d{4}")) return LocalDate.parse(t, FECHA);
            return LocalDate.parse(t);
        } catch (Exception e) {
            throw new IllegalArgumentException("El campo \"" + campo + "\" debe tener el formato DD/MM/AAAA.");
        }
    }

    // ------------------------------------------------------------------ tablas

    /** Modelo de tabla de solo lectura alimentado por una consulta. */
    public static final class ModeloResultado extends AbstractTableModel {
        private Consultas.Resultado datos =
                new Consultas.Resultado(java.util.List.of(), java.util.List.of(), java.util.List.of());

        public void cargar(Consultas.Resultado r) {
            datos = r;
            fireTableStructureChanged();
        }

        public Consultas.Resultado datos() {
            return datos;
        }

        public Object valor(int fila, String columna) {
            int c = datos.columnas().indexOf(columna);
            return c < 0 ? null : datos.filas().get(fila)[c];
        }

        @Override public int getRowCount() { return datos.filas().size(); }
        @Override public int getColumnCount() { return datos.columnas().size(); }
        @Override public String getColumnName(int c) { return titulo(datos.columnas().get(c)); }
        @Override public Class<?> getColumnClass(int c) { return datos.tipos().get(c); }
        @Override public Object getValueAt(int f, int c) { return datos.filas().get(f)[c]; }
    }

    private static final java.util.Map<String, String> TILDES = java.util.Map.ofEntries(
            java.util.Map.entry("almacen", "almacén"), java.util.Map.entry("codigo", "código"),
            java.util.Map.entry("descripcion", "descripción"), java.util.Map.entry("categoria", "categoría"),
            java.util.Map.entry("minimo", "mínimo"), java.util.Map.entry("valuacion", "valuación"),
            java.util.Map.entry("metodo", "método"), java.util.Map.entry("ultimo", "último"),
            java.util.Map.entry("dias", "días"), java.util.Map.entry("lineas", "líneas"),
            java.util.Map.entry("observacion", "observación"));

    /** "saldo_costo_unitario" → "Saldo costo unitario" (con tildes). */
    static String titulo(String columna) {
        StringBuilder sb = new StringBuilder();
        for (String palabra : columna.split("_")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(TILDES.getOrDefault(palabra, palabra));
        }
        String t = sb.toString();
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** Ajusta el ancho de cada columna a su contenido (muestra de hasta 200 filas). */
    static void ajustarColumnas(JTable t) {
        java.awt.FontMetrics fm = t.getFontMetrics(t.getFont());
        int total = 0;
        for (int c = 0; c < t.getColumnCount(); c++) {
            int ancho = fm.stringWidth(t.getColumnName(c)) + 24;
            for (int f = 0; f < Math.min(t.getRowCount(), 200); f++) {
                ancho = Math.max(ancho, fm.stringWidth(formato(t.getValueAt(f, c))) + 16);
            }
            ancho = Math.min(ancho, 420);
            t.getColumnModel().getColumn(c).setPreferredWidth(ancho);
            total += ancho;
        }
        // Si las columnas no caben se habilita el desplazamiento horizontal en vez de recortarlas.
        int visible = t.getParent() == null ? 0 : t.getParent().getWidth();
        t.setAutoResizeMode(visible > 0 && total > visible ? JTable.AUTO_RESIZE_OFF
                                                           : JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
    }

    public static JTable tabla(javax.swing.table.TableModel modelo) {
        JTable t = new JTable(modelo);
        t.setAutoCreateRowSorter(true);
        t.setFillsViewportHeight(true);
        t.setRowHeight(26);
        t.setShowHorizontalLines(true);
        t.getTableHeader().setReorderingAllowed(false);
        DefaultTableCellRenderer texto = new DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object v) {
                setText(formato(v));
            }
        };
        DefaultTableCellRenderer numero = new DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object v) {
                setText(formato(v));
            }
        };
        numero.setHorizontalAlignment(SwingConstants.RIGHT);
        t.setDefaultRenderer(Object.class, texto);
        t.setDefaultRenderer(LocalDateTime.class, texto);
        t.setDefaultRenderer(LocalDate.class, texto);
        t.setDefaultRenderer(BigDecimal.class, numero);
        t.setDefaultRenderer(Long.class, numero);
        modelo.addTableModelListener(e -> SwingUtilities.invokeLater(() -> ajustarColumnas(t)));
        return t;
    }

    public static JScrollPane desplazable(JComponent c) {
        JScrollPane s = new JScrollPane(c);
        s.setBorder(BorderFactory.createLineBorder(new Color(0xD0, 0xD7, 0xDE)));
        return s;
    }

    public static void exportarCsv(Component c, Consultas.Resultado r, String nombre) {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File(nombre + ".csv"));
        fc.setFileFilter(new FileNameExtensionFilter("CSV (Excel)", "csv"));
        if (fc.showSaveDialog(c) != JFileChooser.APPROVE_OPTION) return;
        Path destino = fc.getSelectedFile().toPath();
        try (Writer w = Files.newBufferedWriter(destino, StandardCharsets.UTF_8)) {
            w.write('﻿'); // BOM: Excel reconoce UTF-8 (tildes y ñ)
            w.write(String.join(";", r.columnas().stream().map(Ui::titulo).toList()));
            w.write("\r\n");
            for (Object[] f : r.filas()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < f.length; i++) {
                    if (i > 0) sb.append(';');
                    String v = f[i] instanceof BigDecimal b ? b.toPlainString() : formato(f[i]);
                    sb.append('"').append(v.replace("\"", "\"\"")).append('"');
                }
                w.write(sb.append("\r\n").toString());
            }
            info(c, "Archivo exportado:\n" + destino);
        } catch (IOException e) {
            error(c, "No se pudo guardar el archivo: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ componentes

    public static JLabel tituloPanel(String texto) {
        JLabel l = new JLabel(texto);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 22f));
        l.setForeground(AZUL_OSCURO);
        l.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        return l;
    }

    public static JButton boton(String texto, Runnable accion) {
        JButton b = new JButton(texto);
        b.addActionListener(e -> accion.run());
        return b;
    }

    public static JButton botonPrincipal(String texto, Runnable accion) {
        JButton b = boton(texto, accion);
        b.putClientProperty("JButton.buttonType", "default");
        b.setBackground(AZUL);
        b.setForeground(Color.WHITE);
        return b;
    }

    /** Formulario de dos columnas (etiqueta / campo). */
    public static final class Formulario extends JPanel {
        private int fila;

        public Formulario() {
            super(new GridBagLayout());
        }

        public Formulario campo(String etiqueta, Component c) {
            GridBagConstraints g = new GridBagConstraints();
            g.gridy = fila++;
            g.insets = new Insets(4, 0, 4, 10);
            g.anchor = GridBagConstraints.WEST;
            add(new JLabel(etiqueta), g);
            g.gridx = 1;
            g.insets = new Insets(4, 0, 4, 0);
            g.fill = GridBagConstraints.HORIZONTAL;
            g.weightx = 1;
            add(c, g);
            return this;
        }
    }
}
