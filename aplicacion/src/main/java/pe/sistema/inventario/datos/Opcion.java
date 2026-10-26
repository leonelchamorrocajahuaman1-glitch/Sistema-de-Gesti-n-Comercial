package pe.sistema.inventario.datos;

/** Elemento genérico para listas desplegables (id + texto visible). */
public record Opcion(Object id, String texto) {

    @Override
    public String toString() {
        return texto;
    }

    public Integer idEntero() {
        return id == null ? null : ((Number) id).intValue();
    }
}
