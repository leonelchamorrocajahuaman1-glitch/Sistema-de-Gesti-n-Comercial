package pe.sistema.inventario.datos;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Línea de un documento (compra, venta, transferencia). Campos opcionales en null. */
public record ItemDocumento(String producto, String descripcion, BigDecimal cantidad,
                            BigDecimal costoUnitario, BigDecimal precioUnitario,
                            String lote, LocalDate vencimiento) {

    String json() {
        StringBuilder sb = new StringBuilder("{");
        campo(sb, "producto", texto(producto));
        campo(sb, "cantidad", cantidad.toPlainString());
        if (costoUnitario != null) campo(sb, "costo_unitario", costoUnitario.toPlainString());
        if (precioUnitario != null) campo(sb, "precio_unitario", precioUnitario.toPlainString());
        if (lote != null && !lote.isBlank()) campo(sb, "lote", texto(lote.trim()));
        if (vencimiento != null) campo(sb, "fecha_vencimiento", texto(vencimiento.toString()));
        sb.setLength(sb.length() - 1);
        return sb.append('}').toString();
    }

    private static void campo(StringBuilder sb, String nombre, String valor) {
        sb.append('"').append(nombre).append("\":").append(valor).append(',');
    }

    static String texto(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
