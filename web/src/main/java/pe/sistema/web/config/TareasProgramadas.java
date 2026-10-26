package pe.sistema.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import pe.sistema.web.datos.Operaciones;

/**
 * Revisa los lotes por vencer o vencidos al arrancar y cada madrugada, para que
 * las alertas de vencimiento estén al día aunque nadie registre movimientos.
 */
@Component
public class TareasProgramadas {

    private static final Logger LOG = LoggerFactory.getLogger(TareasProgramadas.class);

    private final Operaciones operaciones;

    public TareasProgramadas(Operaciones operaciones) {
        this.operaciones = operaciones;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        LOG.info("Sistema web listo en el puerto {}", System.getProperty("server.port",
                System.getenv().getOrDefault("PUERTO", "8080")));
        alertasVencimiento();
    }

    @Scheduled(cron = "0 5 0 * * *")
    public void alertasVencimiento() {
        try {
            operaciones.generarAlertasVencimiento();
            LOG.info("Alertas de vencimiento actualizadas");
        } catch (RuntimeException e) {
            LOG.warn("No se pudieron actualizar las alertas de vencimiento: {}", e.getMessage());
        }
    }
}
