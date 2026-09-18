package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.CountryCurrencyService;
import com.nexaplatform.dropshipping.application.service.PricingCountryHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    private final CountryCurrencyService countryCurrencyService;

    public GeoController(CountryCurrencyService countryCurrencyService) {
        this.countryCurrencyService = countryCurrencyService;
    }

    /** Respuesta de geolocalización. {@code country} puede ser {@code null} si el CDN no lo aporta. */
    public record GeoResponse(String country, String currency) {
    }

    @GetMapping
    @Operation(summary = "País efectivo del visitante y divisa recomendada (país soportado o USD)")
    public GeoResponse geo() {
        // Solo devolvemos un ISO-3166 alpha-2 válido: así NO reflejamos cabeceras X-Country arbitrarias
        // (cadenas largas / payloads) que un cliente pueda inyectar. Cualquier otra cosa → null → USD.
        String country = sanitizeCountry(PricingCountryHolder.get());
        return new GeoResponse(country, resolveCurrency(country));
    }

    /** Devuelve el código solo si es exactamente dos letras ASCII (ISO-2); si no, {@code null}. */
    private static String sanitizeCountry(String c) {
        return (c != null && c.length() == 2 && c.chars().allMatch(Character::isLetter)) ? c.toUpperCase() : null;
    }

    /**
     * Divisa del país. Delega en {@link CountryCurrencyService} porque los correos necesitan lo mismo y se
     * generan sin petición HTTP: tener la regla dentro de un controlador la dejaba fuera de su alcance, y
     * la campaña de novedades salía con los precios en dólares para todo el mundo.
     */
    private String resolveCurrency(String country) {
        return countryCurrencyService.forCountry(country);
    }
}
