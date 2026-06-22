package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.config.BaseIntegration;
import org.junit.jupiter.api.Test;

/**
 * Smoke de la Fase 0 (camino de endpoint): confirma que el CONTEXTO COMPLETO arranca con un servidor
 * real + Postgres de Testcontainers y que {@link BaseIntegration} expone un {@code WebTestClient}
 * funcional. Sirve de cimiento para los tests endpoint×rol de la Fase 4.
 */
class ContextBootIT extends BaseIntegration {

    @Test
    void publicPlansEndpointIsReachable() {
        // /api/storefront/** es permitAll: prueba la cadena BFF de punta a punta sin token.
        client.get().uri("/api/storefront/billing/plans").exchange().expectStatus().isOk();
    }
}
