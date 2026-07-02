package com.nexaplatform.dropshipping.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter(@Value("${nexadrop.cors.allowed-origins}") String allowed) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(Arrays.stream(allowed.split(",")).map(String::trim).toList());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Lista explícita en vez de "*": solo las cabeceras que el SPA usa de verdad.
        // X-Lang: el SPA la manda en CADA petición (locale seleccionada); si falta aquí, el preflight
        // no la aprueba y el navegador bloquea TODAS las llamadas cross-origin con "CORS error"
        // (catálogo/imágenes no cargan y /api/me falla → login no queda autenticado).
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Accept-Language", "X-Currency",
                "X-Lang", "X-XSRF-TOKEN", "Idempotency-Key"));
        cfg.setExposedHeaders(List.of("Location", "X-Total-Count", "X-RateLimit-Remaining"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return new CorsFilter(src);
    }
}
