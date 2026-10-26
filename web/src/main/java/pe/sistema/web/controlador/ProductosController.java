package pe.sistema.web.controlador;

import java.math.BigDecimal;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import pe.sistema.web.datos.Bd;
import pe.sistema.web.datos.Maestros;
import pe.sistema.web.datos.Maestros.Producto;
import pe.sistema.web.datos.Operaciones;

/** Catálogo de productos con su stock total; alta, edición y stock mínimo por almacén. */
@Controller
public class ProductosController {

    static final String[] UNIDADES = {"UND", "CJA", "BOL", "BOT", "LAT", "KG", "LT", "PAQ"};

    private final Bd bd;
    private final Maestros maestros;
    private final Operaciones operaciones;

    public ProductosController(Bd bd, Maestros maestros, Operaciones operaciones) {
        this.bd = bd;
        this.maestros = maestros;
        this.operaciones = operaciones;
    }

    @GetMapping("/productos")
    public String lista(@RequestParam(defaultValue = "") String q, Model model) {
        String filtro = "%" + q.trim() + "%";
        model.addAttribute("q", q);
        model.addAttribute("productos", bd.tabla("""
                SELECT codigo, nombre, categoria, unidad_medida AS unidad,
                       metodo_valuacion::text AS valuacion, controla_lote AS lotes,
                       controla_vencimiento AS vence, precio_venta, stock_total, valor_total, activo
                  FROM v_productos
                 WHERE codigo ILIKE ? OR nombre ILIKE ? OR coalesce(categoria, '') ILIKE ?
                 ORDER BY codigo""", filtro, filtro, filtro));
        return "productos";
    }

    @GetMapping("/productos/nuevo")
    public String nuevo(Model model) {
        return formulario(Producto.nuevo(), model);
    }

    @GetMapping("/productos/editar")
    public String editar(@RequestParam String codigo, Model model) {
        return formulario(maestros.producto(codigo), model);
    }

    private String formulario(Producto p, Model model) {
        model.addAttribute("p", p);
        model.addAttribute("categorias", maestros.categorias());
        model.addAttribute("unidades", UNIDADES);
        return "producto-form";
    }

    @PostMapping("/productos/guardar")
    public String guardar(@RequestParam(required = false) Integer id, @RequestParam String codigo,
                          @RequestParam String nombre, @RequestParam(defaultValue = "") String categoria,
                          @RequestParam String unidad, @RequestParam String metodo,
                          @RequestParam(defaultValue = "false") boolean controlaLote,
                          @RequestParam(defaultValue = "false") boolean controlaVencimiento,
                          @RequestParam(defaultValue = "30") String diasAlerta,
                          @RequestParam(defaultValue = "0") String precioVenta,
                          @RequestParam(defaultValue = "false") boolean activo,
                          Model model, RedirectAttributes flash) {
        String error = null;
        BigDecimal precio = BigDecimal.ZERO;
        int dias = 30;
        try {
            precio = Numeros.decimal(precioVenta, "Precio de venta", BigDecimal.ZERO);
            dias = Numeros.decimal(diasAlerta, "Días de alerta", BigDecimal.valueOf(30)).intValueExact();
        } catch (IllegalArgumentException | ArithmeticException e) {
            error = e instanceof ArithmeticException ? "Los días de alerta deben ser un número entero." : e.getMessage();
        }
        if (!"PROMEDIO".equals(metodo) && !"PEPS".equals(metodo)) {
            metodo = "PROMEDIO";
        }
        // En la edición el código no cambia: se conserva el registrado.
        if (id != null) {
            codigo = maestros.producto(codigo).codigo();
        }
        Producto p = new Producto(id, codigo, nombre, categoria, unidad.trim().toUpperCase(), metodo,
                controlaLote || controlaVencimiento, controlaVencimiento, dias, precio, activo);
        if (error == null && (codigo.isBlank() || nombre.isBlank())) {
            error = "Código y nombre son obligatorios.";
        }
        if (error == null) {
            try {
                maestros.guardarProducto(p);
                flash.addFlashAttribute("ok", "Producto " + p.codigo().trim().toUpperCase() + " guardado.");
                return "redirect:/productos";
            } catch (DataAccessException e) {
                error = Bd.mensaje(e);
            }
        }
        model.addAttribute("error", error);
        return formulario(p, model);
    }

    @GetMapping("/productos/stock-minimo")
    public String stockMinimo(@RequestParam String codigo, Model model) {
        model.addAttribute("producto", maestros.producto(codigo));
        model.addAttribute("almacenes", maestros.almacenesActivos());
        model.addAttribute("minimos", bd.tabla("""
                SELECT almacen, cantidad, stock_minimo, bajo_minimo
                  FROM v_stock_valorizado WHERE producto = ? ORDER BY almacen""", codigo));
        return "stock-minimo";
    }

    @PostMapping("/productos/stock-minimo")
    public String guardarStockMinimo(@RequestParam String codigo, @RequestParam int almacen,
                                     @RequestParam String minimo, Model model, RedirectAttributes flash) {
        try {
            BigDecimal m = Numeros.decimal(minimo, "Stock mínimo", BigDecimal.ZERO);
            operaciones.configurarStockMinimo(almacen, codigo, m);
            flash.addFlashAttribute("ok", "Stock mínimo actualizado. Las alertas se recalcularon.");
            return "redirect:/productos/stock-minimo?codigo=" + java.net.URLEncoder.encode(codigo,
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
        } catch (DataAccessException e) {
            model.addAttribute("error", Bd.mensaje(e));
        }
        return stockMinimo(codigo, model);
    }
}
