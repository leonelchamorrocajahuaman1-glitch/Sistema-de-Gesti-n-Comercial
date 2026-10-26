package pe.sistema.web.seguridad;

import java.io.IOException;
import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import pe.sistema.web.datos.Operaciones;

/**
 * En cada página pedida por un usuario con sesión:
 * <ul>
 *   <li>si un administrador lo desactivó o le cambió el rol, cierra su sesión;</li>
 *   <li>si debe cambiar la contraseña inicial, solo le permite hacer eso (o salir).</li>
 * </ul>
 */
public class FiltroSesion extends OncePerRequestFilter {

    private final Operaciones operaciones;

    public FiltroSesion(Operaciones operaciones) {
        this.operaciones = operaciones;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String ruta = request.getServletPath();
        return ruta.startsWith("/css/") || ruta.startsWith("/js/") || ruta.startsWith("/webjars/")
                || ruta.equals("/favicon.svg") || ruta.equals("/favicon.ico") || ruta.equals("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UsuarioWeb u) {
            Optional<Operaciones.EstadoUsuario> estado = operaciones.estadoUsuario(u.getId());
            if (estado.isEmpty() || !estado.get().activo() || !estado.get().rol().equals(u.getRol())) {
                SecurityContextHolder.clearContext();
                HttpSession sesion = request.getSession(false);
                if (sesion != null) {
                    sesion.invalidate();
                }
                response.sendRedirect(request.getContextPath() + "/login?cambio");
                return;
            }
            String ruta = request.getServletPath();
            if (u.isDebeCambiarClave() && !ruta.equals("/cambiar-clave") && !ruta.equals("/salir")) {
                response.sendRedirect(request.getContextPath() + "/cambiar-clave");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
