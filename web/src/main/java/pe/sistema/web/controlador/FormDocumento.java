package pe.sistema.web.controlador;

import java.util.ArrayList;
import java.util.List;

/**
 * Datos enviados por el formulario de ventas, compras o transferencias.
 * Las líneas llegan como lineas[0].producto, lineas[0].cantidad, ...
 */
public class FormDocumento {

    private String tipo;
    private Integer almacen;
    private Integer destino;
    private String referencia;
    private List<Linea> lineas = new ArrayList<>();

    public static class Linea {
        private String producto;
        private String descripcion;
        private String cantidad;
        private String importe;
        private String lote;
        private String loteTexto;
        private String vencimiento;

        public String getProducto() { return producto; }
        public void setProducto(String producto) { this.producto = producto; }
        public String getDescripcion() { return descripcion; }
        public void setDescripcion(String descripcion) { this.descripcion = descripcion; }
        public String getCantidad() { return cantidad; }
        public void setCantidad(String cantidad) { this.cantidad = cantidad; }
        public String getImporte() { return importe; }
        public void setImporte(String importe) { this.importe = importe; }
        public String getLote() { return lote; }
        public void setLote(String lote) { this.lote = lote; }
        public String getLoteTexto() { return loteTexto; }
        public void setLoteTexto(String loteTexto) { this.loteTexto = loteTexto; }
        public String getVencimiento() { return vencimiento; }
        public void setVencimiento(String vencimiento) { this.vencimiento = vencimiento; }
    }

    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }
    public Integer getAlmacen() { return almacen; }
    public void setAlmacen(Integer almacen) { this.almacen = almacen; }
    public Integer getDestino() { return destino; }
    public void setDestino(Integer destino) { this.destino = destino; }
    public String getReferencia() { return referencia; }
    public void setReferencia(String referencia) { this.referencia = referencia; }
    public List<Linea> getLineas() { return lineas; }
    public void setLineas(List<Linea> lineas) { this.lineas = lineas; }
}
