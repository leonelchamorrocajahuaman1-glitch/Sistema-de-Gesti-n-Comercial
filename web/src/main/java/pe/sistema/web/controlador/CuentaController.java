package pe.sistema.web.controlador;

import org.springframework.dao.DataAccessException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import pe.sistema.web.datos.Bd;
import pe.sistema.web.datos.Operaciones;
import pe.sistema.web.seguridad.UsuarioWeb;

/** Inicio de sesión y cambio de contraseña. */
@Controller
public class CuentaController {

    private final Operaciones operaciones;

    public CuentaController(Operaciones operaciones) {
        this.operaciones = operaciones;
    }

    @GetMapping("/login")
    public String login(@AuthenticationPrincipal UsuarioWeb u) {
        return u != null ? "redirect:/" : "login";
    }

    @GetMapping("/cambiar-clave")
    public String cambiarClave() {
        return "cambiar-clave";
    }

    @PostMapping("/cambiar-clave")
    public String cambiarClave(@AuthenticationPrincipal UsuarioWeb u,
                               @RequestParam String actual, @RequestParam String nueva,
                               @RequestParam String confirmar, Model model, RedirectAttributes flash) {
        if (!nueva.equals(confirmar)) {
            model.addAttribute("error", "La nueva contraseña y su confirmación no coinciden.");
            return "cambiar-clave";
        }
        try {
            operaciones.cambiarClave(u.getUsuario(), actual, nueva);
        } catch (DataAccessException e) {
            model.addAttribute("error", Bd.mensaje(e));
            return "cambiar-clave";
        }
        u.claveCambiada();
        flash.addFlashAttribute("ok", "Contraseña cambiada correctamente.");
        return "redirect:/";
    }
}
