package pe.sistema.web.seguridad;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import pe.sistema.web.datos.Operaciones;

/**
 * Seguridad de la aplicación web:
 * <ul>
 *   <li>inicio de sesión con fn_login (contraseñas bcrypt dentro de PostgreSQL);</li>
 *   <li>permisos por rol en cada dirección (no solo ocultando botones);</li>
 *   <li>protección CSRF en todos los formularios;</li>
 *   <li>sesión que expira tras 30 minutos sin actividad (application.properties);</li>
 *   <li>cabeceras que impiden incrustar la página o cargar código de otros sitios.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SeguridadConfig {

    private static final String ADMIN = "ADMINISTRADOR";
    private static final String ALMACENERO = "ALMACENERO";

    @Bean
    public SecurityFilterChain cadena(HttpSecurity http, Operaciones operaciones) throws Exception {
        http
            .authenticationProvider(new ProveedorLogin(operaciones))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/login", "/css/**", "/js/**", "/webjars/**", "/favicon.svg", "/error")
                    .permitAll()
                .requestMatchers("/usuarios/**", "/almacenes/**", "/reportes/anular").hasRole(ADMIN)
                .requestMatchers("/compras/**", "/transferencias/**",
                                 "/productos/nuevo", "/productos/editar", "/productos/guardar",
                                 "/productos/stock-minimo").hasAnyRole(ADMIN, ALMACENERO)
                .anyRequest().authenticated())
            .formLogin(f -> f
                .loginPage("/login")
                .usernameParameter("usuario")
                .passwordParameter("clave")
                .defaultSuccessUrl("/", true)
                .failureHandler((req, res, e) -> {
                    String codigo = e instanceof LockedException ? "bloqueado"
                                  : e instanceof DisabledException ? "inactivo"
                                  : e instanceof AuthenticationServiceException ? "conexion"
                                  : "credenciales";
                    res.sendRedirect(req.getContextPath() + "/login?error=" + codigo);
                })
                .permitAll())
            .logout(l -> l
                .logoutUrl("/salir")
                .logoutSuccessUrl("/login?salio")
                .deleteCookies("SGSESION"))
            .sessionManagement(s -> s.invalidSessionUrl("/login?expirada"))
            .exceptionHandling(e -> e.accessDeniedHandler((req, res, ex) -> {
                // Un formulario abierto más de 30 minutos lleva un código CSRF de una sesión vencida.
                if (ex instanceof CsrfException) {
                    res.sendRedirect(req.getContextPath() + "/login?expirada");
                } else {
                    res.sendError(403);
                }
            }))
            .headers(h -> h
                .contentSecurityPolicy(c -> c.policyDirectives(
                    "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; "
                    + "font-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; "
                    + "frame-ancestors 'none'"))
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)))
            .addFilterBefore(new FiltroSesion(operaciones), AuthorizationFilter.class);
        return http.build();
    }
}
