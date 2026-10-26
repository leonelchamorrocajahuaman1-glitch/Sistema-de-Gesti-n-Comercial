package pe.sistema.web.seguridad;

import java.io.Serial;
import java.io.Serializable;

import org.springframework.security.core.AuthenticatedPrincipal;

/** Usuario que inició sesión en el navegador y permisos de su rol. Nunca guarda la contraseña. */
public final class UsuarioWeb implements AuthenticatedPrincipal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int id;
    private final String usuario;
    private final String nombre;
    private final String rol;
    private final Integer almacenId;
    private volatile boolean debeCambiarClave;

    public UsuarioWeb(int id, String usuario, String nombre, String rol, Integer almacenId,
                      boolean debeCambiarClave) {
        this.id = id;
        this.usuario = usuario;
        this.nombre = nombre;
        this.rol = rol;
        this.almacenId = almacenId;
        this.debeCambiarClave = debeCambiarClave;
    }

    @Override
    public String getName() {
        return usuario;
    }

    public int getId() { return id; }
    public String getUsuario() { return usuario; }
    public String getNombre() { return nombre; }
    public String getRol() { return rol; }
    public Integer getAlmacenId() { return almacenId; }
    public boolean isDebeCambiarClave() { return debeCambiarClave; }

    public void claveCambiada() {
        debeCambiarClave = false;
    }

    public boolean esAdministrador() {
        return "ADMINISTRADOR".equals(rol);
    }

    /** Puede registrar compras, transferencias, salidas internas y mantener productos. */
    public boolean gestionaAlmacen() {
        return esAdministrador() || "ALMACENERO".equals(rol);
    }

    @Override
    public String toString() {
        return usuario;
    }
}
