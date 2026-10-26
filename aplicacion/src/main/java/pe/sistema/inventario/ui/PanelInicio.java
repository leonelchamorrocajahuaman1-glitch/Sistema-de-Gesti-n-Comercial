package pe.sistema.inventario.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;
import java.math.BigDecimal;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;

import pe.sistema.inventario.datos.Consultas;

/** Tablero de inicio: indicadores y alertas activas. */
final class PanelInicio extends JPanel implements VentanaPrincipal.Seccion {

    private final JLabel valor = new JLabel("-");
    private final JLabel productos = new JLabel("-");
    private final JLabel alertas = new JLabel("-");
    private final JLabel ventas = new JLabel("-");
    private final Ui.ModeloResultado modeloAlertas = new Ui.ModeloResultado();
    private final Ui.ModeloResultado modeloAlmacenes = new Ui.ModeloResultado();

    PanelInicio() {
        super(new BorderLayout(0, 14));
        JPanel cab = new JPanel(new BorderLayout());
        cab.add(Ui.tituloPanel("Inicio"), BorderLayout.WEST);
        cab.add(Ui.boton("Actualizar", this::alMostrar), BorderLayout.EAST);

        JPanel tarjetas = new JPanel(new GridLayout(1, 4, 14, 0));
        tarjetas.add(tarjeta("Valor del inventario (S/)", valor, Ui.AZUL));
        tarjetas.add(tarjeta("Productos con stock", productos, Ui.VERDE));
        tarjetas.add(tarjeta("Alertas activas", alertas, Ui.ROJO));
        tarjetas.add(tarjeta("Ventas de hoy (S/)", ventas, Ui.AMBAR));

        JPanel norte = new JPanel(new BorderLayout(0, 4));
        norte.add(cab, BorderLayout.NORTH);
        norte.add(tarjetas, BorderLayout.CENTER);
        add(norte, BorderLayout.NORTH);

        JTable tAlertas = Ui.tabla(modeloAlertas);
        JTable tAlmacenes = Ui.tabla(modeloAlmacenes);
        JPanel centro = new JPanel(new GridLayout(1, 2, 14, 0));
        centro.add(seccion("Alertas activas", Ui.desplazable(tAlertas)));
        centro.add(seccion("Inventario por almacén", Ui.desplazable(tAlmacenes)));
        add(centro, BorderLayout.CENTER);
    }

    private static JPanel tarjeta(String titulo, JLabel valor, Color color) {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBackground(Color.WHITE);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 4, 0, 0, color),
                BorderFactory.createEmptyBorder(14, 16, 14, 16)));
        JLabel t = new JLabel(titulo);
        t.setForeground(Color.GRAY);
        valor.setFont(valor.getFont().deriveFont(Font.BOLD, 24f));
        valor.setForeground(color);
        p.add(t, BorderLayout.NORTH);
        p.add(valor, BorderLayout.CENTER);
        return p;
    }

    private static JPanel seccion(String titulo, java.awt.Component c) {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        JLabel l = new JLabel(titulo);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 15f));
        p.add(l, BorderLayout.NORTH);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    @Override
    public void alMostrar() {
        record Datos(Object[] indicadores, Consultas.Resultado alertas, Consultas.Resultado almacenes) {
        }
        Ui.tarea(this, () -> new Datos(
                Consultas.tabla("""
                        SELECT (SELECT coalesce(sum(valor_total), 0) FROM stock),
                               (SELECT count(DISTINCT producto_id) FROM stock WHERE cantidad > 0),
                               (SELECT count(*) FROM alerta WHERE NOT resuelta),
                               (SELECT coalesce(sum(importe), 0) FROM v_ventas WHERE fecha::date = current_date)
                        """).filas().get(0),
                Consultas.tabla("SELECT tipo, mensaje FROM v_alertas_activas "
                        + "ORDER BY tipo, almacen, producto"),
                Consultas.tabla("SELECT almacen, nombre, productos_con_stock, valor_total "
                        + "FROM v_valor_inventario_almacen ORDER BY almacen")),
            d -> {
                valor.setText(Ui.formato(((BigDecimal) d.indicadores()[0]).setScale(2, java.math.RoundingMode.HALF_UP)));
                productos.setText(String.valueOf(d.indicadores()[1]));
                alertas.setText(String.valueOf(d.indicadores()[2]));
                ventas.setText(Ui.formato(((BigDecimal) d.indicadores()[3]).setScale(2, java.math.RoundingMode.HALF_UP)));
                modeloAlertas.cargar(d.alertas());
                modeloAlmacenes.cargar(d.almacenes());
            });
    }
}
