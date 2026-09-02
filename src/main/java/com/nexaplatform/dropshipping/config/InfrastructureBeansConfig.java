package com.nexaplatform.dropshipping.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;

/**
 * Beans that Spring Boot 4 no longer auto-configures by default (or that we want a stable
 * handle on across the app). Declared here in one place so wiring stays predictable.
 */
@Configuration
public class InfrastructureBeansConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    /**
     * El reloj del sistema, como bean.
     *
     * <p>Lo que se inyecta un reloj se puede probar. Quien llama a {@code Instant.now()} por su cuenta
     * obliga a que sus pruebas esperen de verdad: un token externo que se renueva cada cierto tiempo y
     * comprobar eso sin un reloj sustituible no es que sea incómodo, es que no se hace, y el caso se
     * queda sin probar. Con este bean, la prueba fija la hora que necesita y el caso queda cubierto.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
