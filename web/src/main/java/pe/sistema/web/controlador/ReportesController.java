package pe.sistema.web.controlador;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import pe.sistema.web.datos.Bd;
import pe.sistema.web.datos.Bd.Resultado;
import pe.sistema.web.datos.Formato;
import pe.sistema.web.datos.Maestros;
import pe.sistema.web.datos.Opcion;
import pe.sistema.web.datos.Operaciones;
import pe.sistema.web.seguridad.UsuarioWeb;

/** Reportes: kardex valorizado, stock, lotes, ventas, documentos, alertas e integridad. */
@Controller
public class ReportesController {

    public enum Reporte {
        STOCK("Stock valorizado"),
        LOTES("Stock por lote y vencimiento"),
        KARDEX("Kardex valorizado"),
        VENTAS("Ventas y márgenes"),
        DOCUMENTOS("Documentos registrados"),
        ALERTAS("Alertas activas"),
        INTEGRIDAD("Verificación de integridad");

        public final String titulo;

        Reporte(String titulo) {
            this.titulo = titulo;
        }

        public String getTitulo() {
            return titulo;
        }
    }

    /** Filtros elegidos en la pantalla. */
    public record Filtros(Reporte tipo, Integer almacen, String producto, LocalDate desde, LocalDate hasta) {
    }

    private final Bd bd;
    private final Maestros maestros;
    private final Operaciones operaciones;
    private final Formato fmt;

    public ReportesController(Bd bd, Maestros maestros, Operaciones operaciones, Formato fmt) {
        this.bd = bd;
        this.maestros = maestros;
        this.operaciones = operaciones;
        this.fmt = fmt;
    }

    @GetMapping("/reportes")
    public String reportes(@RequestParam(defaultValue = "STOCK") Reporte tipo,
                           @RequestParam(required = false) Integer almacen,
                           @RequestParam(required = false) String producto,
                           @RequestParam(required = false) LocalDate desde,
                           @RequestParam(required = false) LocalDate hasta,
                           Model model) {
        LocalDate hoy = LocalDate.now();
        Filtros f = new Filtros(tipo, almacen, producto == null || producto.isBlank() ? null : producto,
                desde == null ? hoy.withDayOfMonth(1) : desde, hasta == null ? hoy : hasta);
        List<Opcion> almacenes = maestros.almacenesActivos();
        model.addAttribute("f", f);
        model.addAttribute("reportes", Reporte.values());
        model.addAttribute("almacenes", almacenes);
        model.addAttribute("productos", maestros.productosActivos());
        if (tipo == Reporte.KARDEX && (f.almacen() == null || f.producto() == null)) {
            model.addAttribute("aviso", "Para el kardex seleccione un almacén y un producto, y pulse Generar.");
        } else {
            Resultado r = consultar(f, almacenes);
            model.addAttribute("resultado", r);
            if (tipo == Reporte.INTEGRIDAD && r.filas().isEmpty()) {
                model.addAttribute("ok", "Sin inconsistencias: stock, capas y kardex cuadran.");
            }
        }
        return "reportes";
    }

    @GetMapping("/reportes/csv")
    public ResponseEntity<byte[]> csv(@RequestParam(defaultValue = "STOCK") Reporte tipo,
                                      @RequestParam(required = false) Integer almacen,
                                      @RequestParam(required = false) String producto,
                                      @RequestParam(required = false) LocalDate desde,
                                      @RequestParam(required = false) LocalDate hasta) throws IOException {
        LocalDate hoy = LocalDate.now();
        Filtros f = new Filtros(tipo, almacen, producto == null || producto.isBlank() ? null : producto,
                desde == null ? hoy.withDayOfMonth(1) : desde, hasta == null ? hoy : hasta);
        if (tipo == Reporte.KARDEX && (f.almacen() == null || f.producto() == null)) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body("Para el kardex seleccione un almacén y un producto.".getBytes(StandardCharsets.UTF_8));
        }
        Resultado r = consultar(f, maestros.almacenesActivos());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Writer w = new OutputStreamWriter(bytes, StandardCharsets.UTF_8)) {
            w.write('﻿'); // BOM: Excel reconoce UTF-8 (tildes y ñ)
            w.write(String.join(";", r.columnas().stream().map(c -> celda(fmt.titulo(c))).toList()));
            w.write("\r\n");
            for (Object[] fila : r.filas()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < fila.length; i++) {
                    if (i > 0) sb.append(';');
                    sb.append(celda(fmt.csv(fila[i])));
                }
                w.write(sb.append("\r\n").toString());
            }
        }
        String nombre = tipo.titulo + " " + hoy + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(nombre, StandardCharsets.UTF_8).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(bytes.toByteArray());
    }

    /** Celda CSV entre comillas; se neutralizan fórmulas para que Excel no las ejecute. */
    private static String celda(String v) {
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 && !v.matches("-?[0-9.]+")) {
            v = "'" + v;
        }
        return '"' + v.replace("\"", "\"\"") + '"';
    }

    @PostMapping("/reportes/anular")
    public String anular(@RequestParam long documento, @RequestParam String motivo,
                         @RequestParam(required = false) Integer almacen,
                         @RequestParam(required = false) LocalDate desde,
                         @RequestParam(required = false) LocalDate hasta,
                         @AuthenticationPrincipal UsuarioWeb u, RedirectAttributes flash) {
        if (motivo.isBlank()) {
            flash.addFlashAttribute("error", "Escriba el motivo de la anulación.");
        } else {
            try {
                long reversion = operaciones.anularDocumento(u.getUsuario(), documento, motivo);
                flash.addFlashAttribute("ok", "Documento N° " + documento + " anulado. Se generó el documento "
                        + "de reversión N° " + reversion + ".");
            } catch (DataAccessException e) {
                flash.addFlashAttribute("error", Bd.mensaje(e));
            }
        }
        return "redirect:" + UriComponentsBuilder.fromPath("/reportes").queryParam("tipo", "DOCUMENTOS")
                .queryParamIfPresent("almacen", java.util.Optional.ofNullable(almacen))
                .queryParamIfPresent("desde", java.util.Optional.ofNullable(desde))
                .queryParamIfPresent("hasta", java.util.Optional.ofNullable(hasta))
                .build().toUriString();
    }

    // ------------------------------------------------------------ consultas

    private Resultado consultar(Filtros f, List<Opcion> almacenes) {
        String codAlmacen = almacenes.stream().filter(o -> o.idEntero().equals(f.almacen()))
                .map(Opcion::codigo).findFirst().orElse(null);
        Object alm = Bd.texto(codAlmacen);
        Object prod = Bd.texto(f.producto());
        ZoneId z = ZoneId.systemDefault();
        OffsetDateTime ini = f.desde().atStartOfDay(z).toOffsetDateTime();
        OffsetDateTime fin = f.hasta().atTime(LocalTime.MAX).atZone(z).toOffsetDateTime();
        return switch (f.tipo()) {
            case STOCK -> bd.tabla("""
                    SELECT almacen, producto, descripcion, unidad_medida AS unidad, metodo_valuacion::text AS metodo,
                           cantidad, costo_unitario, valor_total, stock_minimo, bajo_minimo
                      FROM v_stock_valorizado
                     WHERE (?::text IS NULL OR almacen = ?) AND (?::text IS NULL OR producto = ?)
                     ORDER BY almacen, producto""", alm, alm, prod, prod);
            case LOTES -> bd.tabla("""
                    SELECT almacen, producto, lote, fecha_vencimiento, dias_para_vencer, cantidad, valor, estado
                      FROM v_stock_por_lote
                     WHERE (?::text IS NULL OR almacen = ?) AND (?::text IS NULL OR producto = ?)
                     ORDER BY fecha_vencimiento NULLS LAST, almacen, producto""", alm, alm, prod, prod);
            case KARDEX -> bd.tabla("SELECT * FROM fn_reporte_kardex(?, ?, ?, ?)",
                    f.producto(), f.almacen(), ini, fin);
            case VENTAS -> bd.tabla("""
                    SELECT fecha, documento_id AS documento, referencia AS comprobante, usuario, almacen,
                           producto, descripcion, cantidad, precio_unitario, importe, costo, margen
                      FROM v_ventas
                     WHERE fecha BETWEEN ? AND ?
                       AND (?::text IS NULL OR almacen = ?) AND (?::text IS NULL OR producto = ?)
                     ORDER BY fecha""", ini, fin, alm, alm, prod, prod);
            case DOCUMENTOS -> bd.tabla("""
                    SELECT id AS documento, fecha, tipo::text AS tipo, estado::text AS estado, almacen,
                           almacen_destino AS destino, referencia, usuario, lineas, costo_total, observacion
                      FROM v_documentos
                     WHERE fecha BETWEEN ? AND ?
                       AND (?::text IS NULL OR almacen = ? OR almacen_destino = ?)
                     ORDER BY id DESC""", ini, fin, alm, alm, alm);
            case ALERTAS -> bd.tabla("""
                    SELECT tipo::text AS tipo, almacen, producto, lote, mensaje, creada_en
                      FROM v_alertas_activas
                     WHERE (?::text IS NULL OR almacen = ?) AND (?::text IS NULL OR producto = ?)
                     ORDER BY tipo, almacen""", alm, alm, prod, prod);
            case INTEGRIDAD -> bd.tabla("SELECT * FROM fn_verificar_integridad()");
        };
    }
}
