package pe.sistema.web.datos;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

/** Formato de valores para las pantallas (disponible en las plantillas como {@code @fmt}). */
@Component("fmt")
public class Formato {

    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final Map<String, String> TILDES = Map.ofEntries(
            Map.entry("almacen", "almacén"), Map.entry("codigo", "código"),
            Map.entry("descripcion", "descripción"), Map.entry("categoria", "categoría"),
            Map.entry("minimo", "mínimo"), Map.entry("valuacion", "valuación"),
            Map.entry("metodo", "método"), Map.entry("ultimo", "último"),
            Map.entry("dias", "días"), Map.entry("lineas", "líneas"),
            Map.entry("observacion", "observación"), Map.entry("clave", "contraseña"));

    private static final Map<String, String> TIPOS = Map.ofEntries(
            Map.entry("COMPRA", "Compra"), Map.entry("INVENTARIO_INICIAL", "Inventario inicial"),
            Map.entry("DEVOLUCION_CLIENTE", "Devolución de cliente"),
            Map.entry("AJUSTE_POSITIVO", "Ajuste positivo"), Map.entry("VENTA", "Venta"),
            Map.entry("CONSUMO", "Consumo interno"), Map.entry("MERMA", "Merma"),
            Map.entry("DEVOLUCION_PROVEEDOR", "Devolución a proveedor"),
            Map.entry("AJUSTE_NEGATIVO", "Ajuste negativo"), Map.entry("TRANSFERENCIA", "Transferencia"),
            Map.entry("INVENTARIO_FISICO", "Inventario físico"), Map.entry("ANULACION", "Anulación"),
            Map.entry("STOCK_MINIMO", "Stock mínimo"), Map.entry("STOCK_AGOTADO", "Stock agotado"),
            Map.entry("LOTE_POR_VENCER", "Lote por vencer"), Map.entry("LOTE_VENCIDO", "Lote vencido"),
            Map.entry("ADMINISTRADOR", "Administrador"), Map.entry("ALMACENERO", "Almacenero"),
            Map.entry("VENDEDOR", "Vendedor"));

    private static DecimalFormat numero() {
        // DecimalFormat no es seguro entre hilos: se crea uno por uso.
        return new DecimalFormat("#,##0.00##", DecimalFormatSymbols.getInstance(Locale.US));
    }

    public String valor(Object v) {
        if (v == null) return "";
        if (v instanceof BigDecimal b) return numero().format(b);
        if (v instanceof Double d) return numero().format(d);
        if (v instanceof LocalDateTime t) return FECHA_HORA.format(t);
        if (v instanceof OffsetDateTime t) return FECHA_HORA.format(t.toLocalDateTime());
        if (v instanceof LocalDate d) return FECHA.format(d);
        if (v instanceof Boolean b) return b ? "Sí" : "No";
        return v.toString();
    }

    /** Importe con dos decimales: 1,234.50 */
    public String dinero(Object v) {
        if (v == null) return "0.00";
        BigDecimal b = v instanceof BigDecimal x ? x : new BigDecimal(v.toString());
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US)).format(b);
    }

    public boolean esNumero(Object v) {
        return v instanceof Number;
    }

    /** "stock_minimo" → "Stock mínimo" */
    public String titulo(String columna) {
        StringBuilder sb = new StringBuilder();
        for (String palabra : columna.split("_")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(TILDES.getOrDefault(palabra, palabra));
        }
        String t = sb.toString();
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** Nombre legible de un tipo de documento, alerta o rol: "AJUSTE_POSITIVO" → "Ajuste positivo". */
    public String tipo(Object codigo) {
        if (codigo == null) return "";
        return TIPOS.getOrDefault(codigo.toString(), codigo.toString());
    }

    /** Valor para CSV: números sin separador de miles para que Excel los reconozca. */
    public String csv(Object v) {
        return v instanceof BigDecimal b ? b.toPlainString() : valor(v);
    }
}
