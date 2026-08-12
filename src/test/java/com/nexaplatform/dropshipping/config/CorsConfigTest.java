package com.nexaplatform.dropshipping.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    /**
     * Regresión: el SPA manda {@code X-Country} (país efectivo para el margen por país) en CADA petición.
     * Como el front vive en otro origen que la API (nx036.com → api.nx036.com), toda llamada dispara un
     * preflight CORS; si {@code X-Country} no está en los headers permitidos, el navegador BLOQUEA la
     * petición y el catálogo (y todo lo autenticado) queda vacío. Este test evita que se pierda de la lista.
     */
    @Test
    void allowedHeaders_includeXCountryAndTheHeadersTheSpaSends() throws Exception {
        CorsFilter filter = new CorsConfig().corsFilter("https://nx036.com");

        Field field = CorsFilter.class.getDeclaredField("configSource");
        field.setAccessible(true);
        CorsConfigurationSource source = (CorsConfigurationSource) field.get(filter);
        CorsConfiguration cfg = ((UrlBasedCorsConfigurationSource) source).getCorsConfigurations().get("/**");

        assertThat(cfg).isNotNull();
        assertThat(cfg.getAllowedHeaders())
                .contains("Authorization", "X-Currency", "X-Country", "X-Lang", "Idempotency-Key");
    }
}
