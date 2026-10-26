package pe.sistema.web.datos;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Línea de un documento (compra, venta, transferencia). Campos opcionales en null. */
public record ItemDocumento(String producto, BigDecimal cantidad, BigDecimal costoUnitario,
                            BigDecimal precioUnitario, String lote, LocalDate vencimiento) {

    /** Objeto JSON con las claves que esperan los procedimientos sp_registrar_* y sp_transferir. */
    Map<String, Object> json() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("producto", producto);
        m.put("cantidad", cantidad);
        if (costoUnitario != null) m.put("costo_unitario", costoUnitario);
        if (precioUnitario != null) m.put("precio_unitario", precioUnitario);
        if (lote != null && !lote.isBlank()) m.put("lote", lote.trim());
        if (vencimiento != null) m.put("fecha_vencimiento", vencimiento.toString());
        return m;
    }
}
