package com.nexaplatform.dropshipping.infrastructure.campaign;

import com.nexaplatform.dropshipping.application.service.ProductViewHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara el recordatorio «lo que has estado mirando» y la purga del historial.
 *
 * <p>El barrido es DIARIO aunque el correo sea cada tres días, y quien decide si a un usuario le toca es
 * {@link ViewedProductsDigestService}, mirando si ya recibió uno dentro de la ventana. Se hace así por dos
 * motivos. Un cron «cada tres días» de verdad —el paso de tres en el campo del día del mes— no reparte de
 * tres en tres: al cambiar de mes salta del 31 al 1 y ese recordatorio llega al día siguiente del
 * anterior, con lo que el usuario recibe dos correos seguidos cada pocos meses. Y aunque
 * repartiera bien, mandaría a TODO el mundo el mismo día en vez de a cada uno tres días después del suyo,
 * de modo que quien visitó algo ayer esperaría hasta la fecha común. Con el barrido diario, cada usuario
 * recibe su recordatorio en cuanto pasan tres días desde el que se le mandó.
 */
@Component
public class ViewedProductsDigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(ViewedProductsDigestScheduler.class);

    private final ViewedProductsDigestService digestService;
    private final ProductViewHistoryService historyService;

    public ViewedProductsDigestScheduler(ViewedProductsDigestService digestService,
            ProductViewHistoryService historyService) {
        this.digestService = digestService;
        this.historyService = historyService;
    }

    /** Todos los días a las 10:00 UTC: hora de oficina en Europa y madrugada en América, no de noche. */
    @Scheduled(cron = "0 0 10 * * *", zone = "UTC")
    public void runDaily() {
        try {
            digestService.sendDigests();
        } catch (RuntimeException ex) {
            log.warn("Recordatorio de visitas fallido: {}", ex.getMessage());
        }
    }

    /**
     * Purga del historial pasada la retención, de madrugada. Va aparte del envío a propósito: si la purga
     * falla, el recordatorio del día tiene que salir igual — y al revés.
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    public void purgeDaily() {
        try {
            historyService.purgeExpired();
        } catch (RuntimeException ex) {
            log.warn("Purga del historial de visitas fallida: {}", ex.getMessage());
        }
    }
}
