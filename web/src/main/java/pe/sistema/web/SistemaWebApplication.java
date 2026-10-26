package pe.sistema.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Sistema de Gestión Comercial (Stock e Inventario) - versión web.
 * <p>
 * La computadora servidor ejecuta este programa y PostgreSQL; las demás
 * computadoras y celulares de la red solo abren http://IP-DEL-SERVIDOR:8080.
 * Toda la lógica de inventario vive en la base de datos (procedimientos sp_*,
 * kardex, alertas); esta aplicación solo la presenta en el navegador.
 */
// Sin el usuario "user" que Spring Boot crea por defecto: solo valen los usuarios de la base (fn_login).
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class SistemaWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(SistemaWebApplication.class, args);
    }
}
