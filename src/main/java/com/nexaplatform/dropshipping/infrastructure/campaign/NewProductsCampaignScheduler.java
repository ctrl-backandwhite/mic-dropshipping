package com.nexaplatform.dropshipping.infrastructure.campaign;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Dispara la campaña de novedades una vez por hora: en cada tope de hora busca los países cuya hora local
 * es 18:00 y les envía el correo (si hoy hubo productos nuevos). Así cada país recibe la campaña a una hora
 * conveniente (18:00 local) sin depender de la zona del servidor.
 */
@Component
public class NewProductsCampaignScheduler {

    private static final Logger log = LoggerFactory.getLogger(NewProductsCampaignScheduler.class);
    private static final int LOCAL_HOUR = 18;

    private final NewProductsCampaignService campaignService;

    public NewProductsCampaignScheduler(NewProductsCampaignService campaignService) {
        this.campaignService = campaignService;
    }

    /** Cada hora en punto (UTC). */
    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    public void runHourly() {
        Set<String> countries = CountryTimeZones.countriesAtLocalHour(LOCAL_HOUR);
        if (countries.isEmpty()) {
            return;
        }
        try {
            campaignService.sendForCountries(countries);
        } catch (RuntimeException ex) {
            log.warn("New-products campaign failed for countries {}: {}", countries, ex.getMessage());
        }
    }
}
