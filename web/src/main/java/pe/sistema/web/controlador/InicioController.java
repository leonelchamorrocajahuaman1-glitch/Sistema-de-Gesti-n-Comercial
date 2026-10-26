package pe.sistema.web.controlador;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import pe.sistema.web.datos.Bd;

/** Tablero de inicio: indicadores, alertas activas e inventario por almacén. */
@Controller
public class InicioController {

    private final Bd bd;

    public InicioController(Bd bd) {
        this.bd = bd;
    }

    @GetMapping("/")
    public String inicio(Model model) {
        model.addAttribute("indicadores", bd.tabla("""
                SELECT (SELECT coalesce(sum(valor_total), 0) FROM stock) AS valor,
                       (SELECT count(DISTINCT producto_id) FROM stock WHERE cantidad > 0) AS productos,
                       (SELECT count(*) FROM alerta WHERE NOT resuelta) AS alertas,
                       (SELECT coalesce(sum(importe), 0) FROM v_ventas WHERE fecha::date = current_date) AS ventas
                """).filas().get(0));
        model.addAttribute("alertas", bd.tabla("""
                SELECT tipo::text AS tipo, almacen, producto, mensaje
                  FROM v_alertas_activas ORDER BY tipo, almacen, producto"""));
        model.addAttribute("almacenes", bd.tabla("""
                SELECT almacen, nombre, productos_con_stock, valor_total
                  FROM v_valor_inventario_almacen ORDER BY almacen"""));
        return "inicio";
    }
}
