package pe.sistema.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import pe.sistema.web.datos.Formato;

/** Pruebas del formato de valores en pantalla y en CSV (no requieren base de datos). */
class FormatoTest {

    private final Formato fmt = new Formato();

    @Test
    void numerosConSeparadorDeMilesYDecimales() {
        assertEquals("1,234.50", fmt.valor(new BigDecimal("1234.5")));
        assertEquals("0.1235", fmt.valor(new BigDecimal("0.12346")));
        assertEquals("1,234.50", fmt.dinero(new BigDecimal("1234.5")));
        assertEquals("1234.5000", fmt.csv(new BigDecimal("1234.5000")));
    }

    @Test
    void fechasYBooleanos() {
        assertEquals("05/03/2026", fmt.valor(LocalDate.of(2026, 3, 5)));
        assertEquals("05/03/2026 14:07", fmt.valor(LocalDateTime.of(2026, 3, 5, 14, 7)));
        assertEquals("Sí", fmt.valor(true));
        assertEquals("", fmt.valor(null));
    }

    @Test
    void titulosYTipos() {
        assertEquals("Stock mínimo", fmt.titulo("stock_minimo"));
        assertEquals("Almacén destino", fmt.titulo("almacen_destino"));
        assertEquals("Ajuste positivo", fmt.tipo("AJUSTE_POSITIVO"));
        assertEquals("OTRO", fmt.tipo("OTRO"));
    }
}
