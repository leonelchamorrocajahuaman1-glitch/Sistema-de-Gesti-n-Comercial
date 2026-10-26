package pe.sistema.web.controlador;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Conversión de lo escrito en los formularios, con mensajes claros si algo no es válido. */
final class Numeros {

    private Numeros() {
    }

    /** Convierte texto a número aceptando coma o punto decimal. Devuelve {@code siVacio} si está vacío. */
    static BigDecimal decimal(String texto, String campo, BigDecimal siVacio) {
        if (texto == null || texto.isBlank()) return siVacio;
        try {
            BigDecimal b = new BigDecimal(texto.trim().replace(",", "."));
            if (b.signum() < 0) {
                throw new IllegalArgumentException("El campo \"" + campo + "\" no puede ser negativo.");
            }
            return b;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("El campo \"" + campo + "\" debe ser numérico.");
        }
    }

    /** Fecha en formato AAAA-MM-DD (el que envían los campos de fecha del navegador). */
    static LocalDate fecha(String texto, String campo) {
        if (texto == null || texto.isBlank()) return null;
        try {
            return LocalDate.parse(texto.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("El campo \"" + campo + "\" no es una fecha válida.");
        }
    }
}
