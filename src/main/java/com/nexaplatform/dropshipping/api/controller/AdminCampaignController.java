package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.infrastructure.campaign.CountryTimeZones;
import com.nexaplatform.dropshipping.infrastructure.campaign.NewProductsCampaignService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * Disparadores manuales de campañas para el admin. El cron horario envía por país a las 18:00 locales; estos
 * endpoints permiten (a) forzar el envío de novedades ahora a todos los países mapeados y (b) mandar un correo
 * de prueba de formato a una dirección concreta.
 */
@RestController
@RequestMapping("/api/admin/campaigns")
@RequiredArgsConstructor
public class AdminCampaignController {

    private final NewProductsCampaignService newProductsCampaign;

    /** Envía la campaña de novedades ahora (semántica real: solo productos ingeridos hoy) a todos los países. */
    @PostMapping("/new-products/run")
    public ResponseEntity<Map<String, Object>> runNewProducts() {
        Set<String> allCountries = CountryTimeZones.allCountries();
        int sent = newProductsCampaign.sendForCountries(allCountries);
        return ResponseEntity.ok(Map.of("sent", sent));
    }

    /** Envía un correo de prueba del formato de novedades a una dirección (con contenido de ejemplo). */
    @PostMapping("/new-products/test")
    public ResponseEntity<Map<String, Object>> testNewProducts(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String lang = body.getOrDefault("lang", "es");
        boolean queued = newProductsCampaign.sendTest(email, lang);
        return ResponseEntity.ok(Map.of("queued", queued, "email", email, "lang", lang));
    }
}
