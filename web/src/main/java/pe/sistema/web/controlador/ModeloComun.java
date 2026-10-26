package pe.sistema.web.controlador;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.HttpServletRequest;
import pe.sistema.web.datos.Bd;
import pe.sistema.web.seguridad.UsuarioWeb;

/** Datos disponibles en todas las pantallas y manejo general de errores. */
@ControllerAdvice
public class ModeloComun {

    private static final Logger LOG = LoggerFactory.getLogger(ModeloComun.class);

    /** Usuario conectado (en las plantillas: ${u}). */
    @ModelAttribute("u")
    public UsuarioWeb usuario(@AuthenticationPrincipal UsuarioWeb u) {
        return u;
    }

    /** Dirección actual, para resaltar la opción del menú (en las plantillas: ${ruta}). */
    @ModelAttribute("ruta")
    public String ruta(HttpServletRequest request) {
        return request.getServletPath();
    }

    /** Error de base de datos no previsto: se muestra un mensaje entendible en vez de una traza. */
    @ExceptionHandler(DataAccessException.class)
    public ModelAndView errorBaseDatos(DataAccessException e, HttpServletRequest request) {
        LOG.error("Error de base de datos en {}", request.getRequestURI(), e);
        ModelAndView mv = new ModelAndView("error", HttpStatus.INTERNAL_SERVER_ERROR);
        mv.addObject("status", 500);
        mv.addObject("mensaje", Bd.mensaje(e));
        return mv;
    }
}
