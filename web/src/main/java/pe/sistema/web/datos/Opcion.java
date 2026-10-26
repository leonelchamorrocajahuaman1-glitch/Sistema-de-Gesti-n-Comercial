package pe.sistema.web.datos;

/** Elemento genérico para listas desplegables (id + texto visible). */
public record Opcion(Object id, String texto) {

    public Integer idEntero() {
        return id == null ? null : ((Number) id).intValue();
    }

    /** Código antes del separador " - " (por ejemplo "ALM-LIM - Almacén Central" → "ALM-LIM"). */
    public String codigo() {
        return texto.split(" - ", 2)[0];
    }
}
