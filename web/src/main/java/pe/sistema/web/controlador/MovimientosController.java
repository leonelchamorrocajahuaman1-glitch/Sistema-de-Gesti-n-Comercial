package pe.sistema.web.controlador;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.dao.DataAccessException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import pe.sistema.web.datos.Bd;
import pe.sistema.web.datos.ItemDocumento;
import pe.sistema.web.datos.Maestros;
import pe.sistema.web.datos.Opcion;
import pe.sistema.web.datos.Operaciones;
import pe.sistema.web.seguridad.UsuarioWeb;

/**
 * Registro de documentos de inventario: ventas y otras salidas, compras y otras
 * entradas, y transferencias entre almacenes.  La validación definitiva (stock,
 * lotes vencidos, valorización) la hace el servidor PostgreSQL al registrar.
 */
@Controller
public class MovimientosController {

    /** Configuración de cada pantalla. */
    public enum Modo {
        SALIDA("ventas", "Ventas y salidas", "Precio unit.", "Comprobante", "Registrar venta / salida"),
        ENTRADA("compras", "Compras y entradas", "Costo unit.", "Factura / guía", "Registrar entrada"),
        TRANSFERENCIA("transferencias", "Transferencias entre almacenes", null, "Guía de remisión",
                "Registrar transferencia");

        public final String ruta;
        public final String titulo;
        public final String etiquetaImporte;
        public final String etiquetaReferencia;
        public final String boton;

        Modo(String ruta, String titulo, String etiquetaImporte, String etiquetaReferencia, String boton) {
            this.ruta = ruta;
            this.titulo = titulo;
            this.etiquetaImporte = etiquetaImporte;
            this.etiquetaReferencia = etiquetaReferencia;
            this.boton = boton;
        }

        public String getRuta() { return ruta; }
        public String getTitulo() { return titulo; }
        public String getEtiquetaImporte() { return etiquetaImporte; }
        public String getEtiquetaReferencia() { return etiquetaReferencia; }
        public String getBoton() { return boton; }
    }

    private final Maestros maestros;
    private final Operaciones operaciones;

    public MovimientosController(Maestros maestros, Operaciones operaciones) {
        this.maestros = maestros;
        this.operaciones = operaciones;
    }

    /** Tipos de documento que cada rol puede registrar en cada pantalla. */
    static List<String> tipos(Modo modo, UsuarioWeb u) {
        return switch (modo) {
            case ENTRADA -> List.of("COMPRA", "INVENTARIO_INICIAL", "DEVOLUCION_CLIENTE", "AJUSTE_POSITIVO");
            case SALIDA -> u.gestionaAlmacen()
                    ? List.of("VENTA", "CONSUMO", "MERMA", "DEVOLUCION_PROVEEDOR", "AJUSTE_NEGATIVO")
                    : List.of("VENTA");
            case TRANSFERENCIA -> List.of("TRANSFERENCIA");
        };
    }

    // ------------------------------------------------------------ pantallas

    @GetMapping("/ventas")
    public String ventas(@RequestParam(required = false) Integer almacen, @AuthenticationPrincipal UsuarioWeb u,
                         Model model) {
        return mostrar(Modo.SALIDA, nuevo(almacen, u), u, model);
    }

    @GetMapping("/compras")
    public String compras(@RequestParam(required = false) Integer almacen, @AuthenticationPrincipal UsuarioWeb u,
                          Model model) {
        return mostrar(Modo.ENTRADA, nuevo(almacen, u), u, model);
    }

    @GetMapping("/transferencias")
    public String transferencias(@RequestParam(required = false) Integer almacen,
                                 @AuthenticationPrincipal UsuarioWeb u, Model model) {
        return mostrar(Modo.TRANSFERENCIA, nuevo(almacen, u), u, model);
    }

    @PostMapping("/ventas")
    public String registrarVenta(@ModelAttribute("f") FormDocumento f, @AuthenticationPrincipal UsuarioWeb u,
                                 Model model, RedirectAttributes flash) {
        return registrar(Modo.SALIDA, f, u, model, flash);
    }

    @PostMapping("/compras")
    public String registrarCompra(@ModelAttribute("f") FormDocumento f, @AuthenticationPrincipal UsuarioWeb u,
                                  Model model, RedirectAttributes flash) {
        return registrar(Modo.ENTRADA, f, u, model, flash);
    }

    @PostMapping("/transferencias")
    public String registrarTransferencia(@ModelAttribute("f") FormDocumento f,
                                         @AuthenticationPrincipal UsuarioWeb u,
                                         Model model, RedirectAttributes flash) {
        return registrar(Modo.TRANSFERENCIA, f, u, model, flash);
    }

    // ------------------------------------------------------------ lógica

    private static FormDocumento nuevo(Integer almacen, UsuarioWeb u) {
        FormDocumento f = new FormDocumento();
        f.setAlmacen(almacen != null ? almacen : u.getAlmacenId());
        return f;
    }

    private String mostrar(Modo modo, FormDocumento f, UsuarioWeb u, Model model) {
        List<Opcion> almacenes = maestros.almacenesActivos();
        if (f.getAlmacen() == null && !almacenes.isEmpty()) {
            f.setAlmacen(almacenes.get(0).idEntero());
        }
        if (modo == Modo.TRANSFERENCIA && f.getDestino() == null) {
            almacenes.stream().map(Opcion::idEntero).filter(id -> !id.equals(f.getAlmacen()))
                    .findFirst().ifPresent(f::setDestino);
        }
        model.addAttribute("modo", modo);
        model.addAttribute("f", f);
        model.addAttribute("tipos", tipos(modo, u));
        model.addAttribute("almacenes", almacenes);
        model.addAttribute("productos", maestros.productosActivos());
        return "movimiento";
    }

    private String registrar(Modo modo, FormDocumento f, UsuarioWeb u, Model model, RedirectAttributes flash) {
        if (modo == Modo.TRANSFERENCIA) {
            f.setTipo("TRANSFERENCIA");
        }
        f.getLineas().removeIf(l -> l == null || l.getProducto() == null || l.getProducto().isBlank());
        String error = null;
        List<ItemDocumento> items = new ArrayList<>();
        if (!tipos(modo, u).contains(f.getTipo())) {
            error = "Tipo de documento no permitido para su usuario.";
        } else if (f.getAlmacen() == null) {
            error = "Seleccione el almacén.";
        } else if (modo == Modo.TRANSFERENCIA
                && (f.getDestino() == null || Objects.equals(f.getAlmacen(), f.getDestino()))) {
            error = "El almacén de origen y el de destino deben ser distintos.";
        } else if (f.getLineas().isEmpty()) {
            error = "Agregue al menos un producto.";
        } else {
            try {
                for (FormDocumento.Linea l : f.getLineas()) {
                    items.add(item(modo, f.getTipo(), l));
                }
            } catch (IllegalArgumentException e) {
                error = e.getMessage();
            }
        }
        if (error == null) {
            try {
                long id = switch (modo) {
                    case ENTRADA -> operaciones.registrarEntrada(u.getUsuario(), f.getTipo(), f.getAlmacen(),
                            items, f.getReferencia());
                    case SALIDA -> operaciones.registrarSalida(u.getUsuario(), f.getTipo(), f.getAlmacen(),
                            items, f.getReferencia());
                    case TRANSFERENCIA -> operaciones.transferir(u.getUsuario(), f.getAlmacen(), f.getDestino(),
                            items, f.getReferencia());
                };
                flash.addFlashAttribute("ok", "Documento N° " + id + " registrado correctamente. "
                        + "El kardex, el stock y las alertas se actualizaron en el servidor.");
                return "redirect:/" + modo.ruta + "?almacen=" + f.getAlmacen();
            } catch (DataAccessException e) {
                error = Bd.mensaje(e);
            }
        }
        model.addAttribute("error", error);
        return mostrar(modo, f, u, model);
    }

    private static ItemDocumento item(Modo modo, String tipo, FormDocumento.Linea l) {
        String producto = l.getProducto().trim();
        BigDecimal cantidad = Numeros.decimal(l.getCantidad(), "Cantidad de " + producto, null);
        if (cantidad == null || cantidad.signum() <= 0) {
            throw new IllegalArgumentException("La cantidad de " + producto + " debe ser mayor que cero.");
        }
        BigDecimal importe = Numeros.decimal(l.getImporte(), "Importe de " + producto, null);
        String lote = l.getLote() == null || l.getLote().isBlank() ? null : l.getLote().trim();
        return switch (modo) {
            case ENTRADA -> {
                if (importe == null) {
                    throw new IllegalArgumentException("Ingrese el costo unitario de " + producto + ".");
                }
                LocalDate vence = Numeros.fecha(l.getVencimiento(), "Vencimiento de " + producto);
                yield new ItemDocumento(producto, cantidad, importe, null, lote, vence);
            }
            case SALIDA -> new ItemDocumento(producto, cantidad, null, "VENTA".equals(tipo) ? importe : null,
                    lote, null);
            case TRANSFERENCIA -> new ItemDocumento(producto, cantidad, null, null, lote, null);
        };
    }
}
