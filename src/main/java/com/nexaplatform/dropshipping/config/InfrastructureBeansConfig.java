package com.nexaplatform.dropshipping.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

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
}
