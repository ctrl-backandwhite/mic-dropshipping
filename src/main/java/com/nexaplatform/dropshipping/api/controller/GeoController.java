package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.PricingCountryHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CurrencyRateEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Currency;
import java.util.Locale;

/**
 * Geolocalización ligera para el SPA: resuelve el país efectivo del visitante y la divisa que debería ver.
 *
 * <p>El país sale de {@link PricingCountryHolder} (lo puebla {@code PricingCountryFilter} desde la cabecera
 * {@code X-Country} del front o, para invitados, de la cabecera de país por IP del CDN — {@code CF-IPCountry}
 * de Cloudflare). La divisa es la propia del país <b>si está soportada y activa</b> en la plataforma; si no,
 * dólar (USD), según la regla del negocio: «la moneda de ese país si existe, en caso contrario el dólar».
 *
 * <p>Público (sin login): lo usan el registro (para prerrellenar el país) y la portada (para fijar la divisa
 * del invitado). No expone datos sensibles: solo un código de país ISO-2 y un código de divisa ISO-4217.
 */
@RestController
@RequestMapping("/api/geo")
@Tag(name = "Geo")
public class GeoController {

    private final CurrencyRateService currencyRateService;

    public GeoController(CurrencyRateService currencyRateService) {
        this.currencyRateService = currencyRateService;
    }

    /** Respuesta de geolocalización. {@code country} puede ser {@code null} si el CDN no lo aporta. */
    public record GeoResponse(String country, String currency) {
    }

    @GetMapping
    @Operation(summary = "País efectivo del visitante y divisa recomendada (país soportado o USD)")
    public GeoResponse geo() {
        String country = PricingCountryHolder.get();
        return new GeoResponse(country, resolveCurrency(country));
    }

    /**
     * Divisa oficial del país (via {@link Currency#getInstance(Locale)}) si la plataforma la tiene activa;
     * en cualquier otro caso, USD. Nunca lanza: un país desconocido o sin divisa asociada cae en USD.
     */
    private String resolveCurrency(String country) {
        if (country == null || country.isBlank()) {
            return "USD";
        }
        try {
            Currency c = Currency.getInstance(Locale.of("", country.trim().toUpperCase()));
            String code = c.getCurrencyCode();
            boolean supported = currencyRateService.find(code)
                    .filter(CurrencyRateEntity::isActive).isPresent();
            return supported ? code : "USD";
        } catch (IllegalArgumentException ex) {
            return "USD";
        }
    }
}
