package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.config.BaseIntegration;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matriz endpoint×rol de la cadena BFF (resource server JWT de {@code BffSecurityConfig}). Cada caso
 * verifica un PERMITIDO (el status NO es 401 ni 403; puede ser 200/204/400/404 según datos: es
 * autorización, no negocio) y, donde aplica, un PROHIBIDO con status EXACTO (401 sin token, 403 con
 * rol insuficiente). Arranca el contexto completo + servidor real + Postgres (Testcontainers) vía
 * {@link BaseIntegration}; el contexto se cachea entre tests de la clase.
 *
 * <p>Paths reales (verificados contra {@code BffSecurityConfig} y los controllers):
 * <ul>
 *   <li>GET  /api/catalog/products                         — permitAll</li>
 *   <li>GET  /api/catalog/products/{id}/margin-estimate    — hasRole ADMIN</li>
 *   <li>GET  /api/me/orders                                           — authenticated</li>
 *   <li>POST /api/admin/orders/{id}/ship                              — hasAnyRole ADMIN, OPERATOR</li>
 *   <li>GET  /api/admin/operator/earnings                             — hasAnyRole ADMIN, OPERATOR</li>
 *   <li>GET  /api/admin/dashboard/metrics                             — hasRole ADMIN</li>
 * </ul>
 */
class BffEndpointAuthorizationIT extends BaseIntegration {

    private static final String CATALOG_PRODUCTS = "/api/catalog/products";
    private static final String MARGIN_ESTIMATE = "/api/catalog/products/%s/margin-estimate";
    private static final String ME_ORDERS = "/api/me/orders";
    private static final String ORDER_SHIP = "/api/admin/orders/%s/ship";
    private static final String OPERATOR_EARNINGS = "/api/admin/operator/earnings";
    private static final String DASHBOARD_METRICS = "/api/admin/dashboard/metrics";

    /** PERMITIDO: el catálogo storefront es permitAll → accesible sin token (no 401/403). */
    @Test
    void catalogProducts_anonymous_isReachable() {
        client.get().uri(CATALOG_PRODUCTS).exchange()
                .expectStatus().value(s -> assertThat(s).isNotIn(401, 403));
    }

    /** PERMITIDO: margin-estimate exige ROLE_ADMIN. */
    @Test
    void marginEstimate_admin_isAllowed() {
        client.get().uri(String.format(MARGIN_ESTIMATE, UUID.randomUUID()))
                .header("Authorization", bearer(jwt.userToken("ADMIN"))).exchange()
                .expectStatus().value(s -> assertThat(s).isNotIn(401, 403));
    }

    /** PROHIBIDO: un USER no llega al margen → 403. */
    @Test
    void marginEstimate_user_isForbidden() {
        client.get().uri(String.format(MARGIN_ESTIMATE, UUID.randomUUID()))
                .header("Authorization", bearer(jwt.userToken("USER"))).exchange()
                .expectStatus().isEqualTo(403);
    }

    /** PROHIBIDO: sin token → 401. */
    @Test
    void marginEstimate_anonymous_isUnauthorized() {
        client.get().uri(String.format(MARGIN_ESTIMATE, UUID.randomUUID())).exchange()
                .expectStatus().isEqualTo(401);
    }

    /** PERMITIDO: /api/me/** solo pide autenticación; un USER pasa (no 401/403). */
    @Test
    void meOrders_user_isAllowed() {
        client.get().uri(ME_ORDERS)
                .header("Authorization", bearer(jwt.userToken("USER"))).exchange()
                .expectStatus().value(s -> assertThat(s).isNotIn(401, 403));
    }

    /** PROHIBIDO: /api/me/orders sin token → 401. */
    @Test
    void meOrders_anonymous_isUnauthorized() {
        client.get().uri(ME_ORDERS).exchange()
                .expectStatus().isEqualTo(401);
    }

    /** PERMITIDO: /api/admin/orders/** lo pueden tocar ADMIN y OPERATOR. */
    @Test
    void shipOrder_admin_isAllowed() {
        client.post().uri(String.format(ORDER_SHIP, UUID.randomUUID()))
                .header("Authorization", bearer(jwt.userToken("ADMIN"))).exchange()
                .expectStatus().value(s -> assertThat(s).isNotIn(401, 403));
    }

    /** PERMITIDO: OPERATOR (soporte) también puede procesar órdenes. */
    @Test
    void shipOrder_operator_isAllowed() {
        client.post().uri(String.format(ORDER_SHIP, UUID.randomUUID()))
                .header("Authorization", bearer(jwt.userToken("OPERATOR"))).exchange()
                .expectStatus().value(s -> assertThat(s).isNotIn(401, 403));
    }

    /** PROHIBIDO: un USER no puede marcar enviado → 403. */
    @Test
    void shipOrder_user_isForbidden() {
        client.post().uri(String.format(ORDER_SHIP, UUID.randomUUID()))
                .header("Authorization", bearer(jwt.userToken("USER"))).exchange()
                .expectStatus().isEqualTo(403);
    }

    /** PERMITIDO: las ganancias del operador son para ADMIN y OPERATOR. */
    @Test
    void operatorEarnings_operator_isAllowed() {
        client.get().uri(OPERATOR_EARNINGS)
                .header("Authorization", bearer(jwt.userToken("OPERATOR"))).exchange()
                .expectStatus().value(s -> assertThat(s).isNotIn(401, 403));
    }

    /** PROHIBIDO: un USER no ve las ganancias del operador → 403. */
    @Test
    void operatorEarnings_user_isForbidden() {
        client.get().uri(OPERATOR_EARNINGS)
                .header("Authorization", bearer(jwt.userToken("USER"))).exchange()
                .expectStatus().isEqualTo(403);
    }

    /** PERMITIDO: el dashboard (resto de /api/admin/**) es EXCLUSIVO de ADMIN. */
    @Test
    void dashboardMetrics_admin_isAllowed() {
        client.get().uri(DASHBOARD_METRICS)
                .header("Authorization", bearer(jwt.userToken("ADMIN"))).exchange()
                .expectStatus().value(s -> assertThat(s).isNotIn(401, 403));
    }

    /** PROHIBIDO: un OPERATOR no entra al dashboard admin → 403. */
    @Test
    void dashboardMetrics_operator_isForbidden() {
        client.get().uri(DASHBOARD_METRICS)
                .header("Authorization", bearer(jwt.userToken("OPERATOR"))).exchange()
                .expectStatus().isEqualTo(403);
    }
}
