package pe.sistema.inventario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import pe.sistema.inventario.datos.BaseDatos;
import pe.sistema.inventario.datos.ConfigConexion;
import pe.sistema.inventario.datos.Consultas;
import pe.sistema.inventario.datos.ItemDocumento;
import pe.sistema.inventario.datos.Maestros;
import pe.sistema.inventario.datos.Operaciones;
import pe.sistema.inventario.datos.Sesion;

/**
 * Pruebas de integración: se conectan por TCP con el rol de red app_inventario,
 * igual que una computadora cliente.  Requieren una base instalada con datos de
 * demostración y las variables INV_HOST, INV_DB, INV_CLAVE (se omiten si faltan).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperacionesIT {

    @BeforeAll
    static void conectar() {
        assumeTrue(System.getenv("INV_DB") != null, "INV_DB no definida: se omiten pruebas de integración");
        ConfigConexion c = new ConfigConexion();
        c.setHost(System.getenv().getOrDefault("INV_HOST", "localhost"));
        c.setBaseDatos(System.getenv("INV_DB"));
        c.setUsuario("app_inventario");
        c.setClave(System.getenv().getOrDefault("INV_CLAVE", ""));
        BaseDatos.configurar(c);
    }

    private static BigDecimal stock(int almacen, String producto) throws SQLException {
        return Maestros.stockYPrecio(almacen, producto)[0];
    }

    @Test
    @Order(1)
    void loginValidaCredencialesYExigeCambioDeClave() throws SQLException {
        assertEquals("CREDENCIALES_INVALIDAS", Operaciones.login("jperez", "incorrecta").estado());
        Operaciones.ResultadoLogin r = Operaciones.login("jperez", "demo123");
        assertEquals("OK", r.estado());
        assertTrue(r.debeCambiarClave());
        assertEquals("VENDEDOR", r.sesion().rol());
        Operaciones.cambiarClave("jperez", "demo123", "nueva123");
        assertFalse(Operaciones.login("jperez", "nueva123").debeCambiarClave());
        Sesion.iniciar(r.sesion());
    }

    @Test
    @Order(2)
    void ventaDescuentaStockYRegistraUsuarioYPrecio() throws SQLException {
        BigDecimal antes = stock(1, "ARR-001");
        long doc = Operaciones.registrarSalida("VENTA", 1, List.of(new ItemDocumento("ARR-001", "",
                new BigDecimal("2"), null, new BigDecimal("26.50"), null, null)), "B001-9001");
        assertEquals(0, antes.subtract(new BigDecimal("2")).compareTo(stock(1, "ARR-001")));
        Object[] fila = Consultas.tabla("SELECT usuario, precio_unitario, importe FROM v_ventas WHERE documento_id = ?",
                doc).filas().get(0);
        assertEquals("jperez", fila[0]);
        assertEquals(0, new BigDecimal("53.00").compareTo((BigDecimal) fila[2]));
    }

    @Test
    @Order(3)
    void sobreventaEsRechazadaPorElServidor() {
        SQLException e = assertThrows(SQLException.class, () -> Operaciones.registrarSalida("VENTA", 1,
                List.of(new ItemDocumento("ARR-001", "", new BigDecimal("100000"), null, null, null, null)), null));
        assertTrue(BaseDatos.mensaje(e).startsWith("Stock insuficiente"), BaseDatos.mensaje(e));
    }

    @Test
    @Order(4)
    void compraConLoteYCaracteresEspecialesEnJson() throws SQLException {
        long doc = Operaciones.registrarEntrada("COMPRA", 1, List.of(new ItemDocumento("LEC-001", "",
                new BigDecimal("12"), new BigDecimal("3.30"), null, "L\"01\\ñ", LocalDate.now().plusDays(200))),
                "F001-\"x\"");
        assertTrue(doc > 0);
        assertEquals(1L, ((Number) Consultas.valor(
                "SELECT count(*) FROM lote WHERE codigo = ?", "L\"01\\ñ")).longValue());
    }

    @Test
    @Order(5)
    void transferenciaYAnulacion() throws SQLException {
        BigDecimal origen = stock(1, "TOR-001");
        long doc = Operaciones.transferir(1, 3, List.of(new ItemDocumento("TOR-001", "",
                new BigDecimal("5"), null, null, null, null)), "GR-9001");
        assertEquals(0, new BigDecimal("5").compareTo(stock(3, "TOR-001")));
        Operaciones.anularDocumento(doc, "prueba");
        assertEquals(0, origen.compareTo(stock(1, "TOR-001")));
        assertEquals(0, BigDecimal.ZERO.compareTo(stock(3, "TOR-001")));
    }

    @Test
    @Order(6)
    void elRolDeRedNoPuedeAlterarElKardexNiLeerContrasenas() {
        SQLException e1 = assertThrows(SQLException.class, () -> Consultas.ejecutar(
                "UPDATE stock SET cantidad = 999999"));
        assertTrue(BaseDatos.mensaje(e1).contains("Permiso denegado"), BaseDatos.mensaje(e1));
        assertThrows(SQLException.class, () -> Consultas.tabla("SELECT hash_clave FROM usuario"));
    }

    @Test
    @Order(7)
    void integridadDespuesDeTodasLasOperaciones() throws SQLException {
        assertTrue(Consultas.tabla("SELECT * FROM fn_verificar_integridad()").filas().isEmpty());
    }
}
