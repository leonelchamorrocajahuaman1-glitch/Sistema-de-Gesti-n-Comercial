package pe.sistema.web.controlador;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import pe.sistema.web.datos.Bd;
import pe.sistema.web.datos.Maestros;
import pe.sistema.web.datos.Maestros.Almacen;

/** Mantenimiento de almacenes (solo administradores, ver SeguridadConfig). */
@Controller
public class AlmacenesController {

    private final Bd bd;
    private final Maestros maestros;

    public AlmacenesController(Bd bd, Maestros maestros) {
        this.bd = bd;
        this.maestros = maestros;
    }

    @GetMapping("/almacenes")
    public String lista(Model model) {
        model.addAttribute("almacenes", bd.tabla("""
                SELECT a.id, a.codigo, a.nombre, a.direccion, a.activo,
                       v.productos_con_stock, v.valor_total
                  FROM almacen a JOIN v_valor_inventario_almacen v ON v.almacen = a.codigo
                 ORDER BY a.codigo"""));
        return "almacenes";
    }

    @GetMapping("/almacenes/nuevo")
    public String nuevo(Model model) {
        model.addAttribute("a", Almacen.nuevo());
        return "almacen-form";
    }

    @GetMapping("/almacenes/editar")
    public String editar(@RequestParam int id, Model model) {
        model.addAttribute("a", maestros.almacen(id));
        return "almacen-form";
    }

    @PostMapping("/almacenes/guardar")
    public String guardar(@RequestParam(required = false) Integer id, @RequestParam String codigo,
                          @RequestParam String nombre, @RequestParam(defaultValue = "") String direccion,
                          @RequestParam(defaultValue = "false") boolean activo,
                          Model model, RedirectAttributes flash) {
        Almacen a = new Almacen(id, codigo, nombre, direccion, activo);
        if (codigo.isBlank() || nombre.isBlank()) {
            model.addAttribute("error", "Código y nombre son obligatorios.");
        } else {
            try {
                maestros.guardarAlmacen(a);
                flash.addFlashAttribute("ok", "Almacén " + codigo.trim().toUpperCase() + " guardado.");
                return "redirect:/almacenes";
            } catch (DataAccessException e) {
                model.addAttribute("error", Bd.mensaje(e));
            }
        }
        model.addAttribute("a", a);
        return "almacen-form";
    }
}
