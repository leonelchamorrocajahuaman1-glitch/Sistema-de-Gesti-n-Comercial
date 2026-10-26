package pe.sistema.web.controlador;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import pe.sistema.web.datos.Maestros;

/** Datos que la pantalla de movimientos pide al elegir producto o almacén (sin recargar la página). */
@RestController
public class ApiController {

    private final Maestros maestros;

    public ApiController(Maestros maestros) {
        this.maestros = maestros;
    }

    @GetMapping("/api/producto")
    public Map<String, Object> producto(@RequestParam int almacen, @RequestParam String producto) {
        Maestros.StockPrecio sp = maestros.stockYPrecio(almacen, producto);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("stock", sp.stock());
        r.put("precio", sp.precio());
        r.put("costo", sp.costo());
        r.put("lotes", maestros.lotesDisponibles(almacen, producto).stream()
                .map(o -> Map.of("codigo", o.id(), "texto", o.texto())).toList());
        return r;
    }
}
