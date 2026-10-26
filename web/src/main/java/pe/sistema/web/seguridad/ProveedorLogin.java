package pe.sistema.web.seguridad;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import pe.sistema.web.datos.Operaciones;

/**
 * Inicio de sesión contra la función fn_login de PostgreSQL: allí se comparan los
 * hashes bcrypt y se bloquea al usuario 5 minutos tras 5 intentos fallidos.
 */
public class ProveedorLogin implements AuthenticationProvider {

    private static final Logger LOG = LoggerFactory.getLogger(ProveedorLogin.class);

    private final Operaciones operaciones;

    public ProveedorLogin(Operaciones operaciones) {
        this.operaciones = operaciones;
    }

    @Override
    public Authentication authenticate(Authentication auth) {
        String usuario = auth.getName() == null ? "" : auth.getName().trim();
        String clave = auth.getCredentials() == null ? "" : auth.getCredentials().toString();
        if (usuario.isEmpty() || clave.isEmpty()) {
            throw new BadCredentialsException("credenciales");
        }
        Operaciones.ResultadoLogin r;
        try {
            r = operaciones.login(usuario, clave);
        } catch (DataAccessException e) {
            LOG.error("No se pudo verificar el inicio de sesión de {}", usuario, e);
            throw new AuthenticationServiceException("conexion", e);
        }
        switch (r.estado()) {
            case "OK" -> {
                LOG.info("Inicio de sesión: {} ({})", r.usuario(), r.rol());
                UsuarioWeb u = new UsuarioWeb(r.id(), r.usuario(), r.nombre(), r.rol(), r.almacenId(),
                        r.debeCambiarClave());
                return UsernamePasswordAuthenticationToken.authenticated(u, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + r.rol())));
            }
            case "USUARIO_INACTIVO" -> throw new DisabledException("inactivo");
            case "USUARIO_BLOQUEADO" -> throw new LockedException("bloqueado");
            default -> {
                LOG.warn("Inicio de sesión fallido para \"{}\"", usuario);
                throw new BadCredentialsException("credenciales");
            }
        }
    }

    @Override
    public boolean supports(Class<?> tipo) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(tipo);
    }
}
