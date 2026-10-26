package pe.sistema.web.controlador;

import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import pe.sistema.web.datos.Bd;
import pe.sistema.web.datos.Maestros;
import pe.sistema.web.datos.Operaciones;

/** Administración de usuarios (solo administradores, ver SeguridadConfig). */
@Controller
public class UsuariosController {

    static final List<String> ROLES = List.of("ADMINISTRADOR", "ALMACENERO", "VENDEDOR");

    private final Bd bd;
    private final Maestros maestros;
    private final Operaciones operaciones;

    public UsuariosController(Bd bd, Maestros maestros, Operaciones operaciones) {
        this.bd = bd;
        this.maestros = maestros;
        this.operaciones = operaciones;
    }

    /** Datos del formulario (la contraseña nunca se vuelve a mostrar). */
    public record FormUsuario(Integer id, String usuario, String nombre, String rol, Integer almacen,
                              boolean activo) {
    }

    @GetMapping("/usuarios")
    public String lista(Model model) {
        model.addAttribute("usuarios", bd.tabla("""
                SELECT id, nombre_usuario AS usuario, nombre_completo, rol::text AS rol, almacen,
                       activo, debe_cambiar_clave, ultimo_acceso
                  FROM v_usuarios ORDER BY nombre_usuario"""));
        return "usuarios";
    }

    @GetMapping("/usuarios/nuevo")
    public String nuevo(Model model) {
        return formulario(new FormUsuario(null, "", "", "VENDEDOR", null, true), model);
    }

    @GetMapping("/usuarios/editar")
    public String editar(@RequestParam int id, Model model) {
        FormUsuario f = bd.jdbc().queryForObject("""
                SELECT id, nombre_usuario, nombre_completo, rol::text, almacen_id, activo
                  FROM v_usuarios WHERE id = ?""",
                (rs, i) -> new FormUsuario(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        (Integer) rs.getObject(5), rs.getBoolean(6)),
                id);
        return formulario(f, model);
    }

    private String formulario(FormUsuario f, Model model) {
        model.addAttribute("f", f);
        model.addAttribute("roles", ROLES);
        model.addAttribute("almacenes", maestros.almacenesActivos());
        return "usuario-form";
    }

    @PostMapping("/usuarios/guardar")
    public String guardar(@RequestParam(required = false) Integer id, @RequestParam String usuario,
                          @RequestParam String nombre, @RequestParam String rol,
                          @RequestParam(required = false) Integer almacen,
                          @RequestParam(defaultValue = "false") boolean activo,
                          @RequestParam(defaultValue = "") String clave,
                          Model model, RedirectAttributes flash) {
        FormUsuario f = new FormUsuario(id, usuario, nombre, rol, almacen, activo);
        String error = null;
        if (!ROLES.contains(rol)) {
            error = "Rol no válido.";
        } else if (usuario.isBlank() || nombre.isBlank()) {
            error = "Usuario y nombre completo son obligatorios.";
        } else if (id == null && clave.isEmpty()) {
            error = "Escriba la contraseña inicial del nuevo usuario.";
        }
        if (error == null) {
            try {
                operaciones.guardarUsuario(id, usuario, nombre, rol, almacen, activo, clave);
                flash.addFlashAttribute("ok", "Usuario " + usuario.trim().toLowerCase() + " guardado."
                        + (clave.isEmpty() ? "" : " Deberá cambiar la contraseña en su próximo ingreso."));
                return "redirect:/usuarios";
            } catch (DataAccessException e) {
                error = Bd.mensaje(e);
                if (error.contains("nombre_usuario_check")) {
                    error = "El usuario debe tener de 3 a 30 caracteres: letras minúsculas, números, punto, guion o guion bajo.";
                }
            }
        }
        model.addAttribute("error", error);
        return formulario(f, model);
    }
}
