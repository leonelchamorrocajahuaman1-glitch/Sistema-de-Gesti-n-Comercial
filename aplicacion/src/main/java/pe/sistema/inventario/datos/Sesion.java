package pe.sistema.inventario.datos;

/** Usuario que inició sesión y permisos de su rol. */
public record Sesion(int id, String usuario, String nombre, String rol, Integer almacenId) {

    private static Sesion actual;

    public static Sesion actual() {
        return actual;
    }

    public static void iniciar(Sesion s) {
        actual = s;
        BaseDatos.setUsuarioAplicacion(s == null ? null : s.usuario());
    }

    public boolean esAdministrador() {
        return "ADMINISTRADOR".equals(rol);
    }

    public boolean esVendedor() {
        return "VENDEDOR".equals(rol);
    }

    /** Puede registrar compras, transferencias y mantener productos. */
    public boolean gestionaAlmacen() {
        return esAdministrador() || "ALMACENERO".equals(rol);
    }
}
