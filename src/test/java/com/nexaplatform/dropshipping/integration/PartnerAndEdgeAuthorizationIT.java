package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.config.BaseIntegration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Autorización de la <b>Partner API</b> (scopes OAuth2 → path), los <b>webhooks</b> (permitAll, la
 * firma la valida el controller) y el <b>cross-token</b> (un token de una cadena no debe servir en la
 * otra). Arranca el contexto completo + servidor real + Postgres de Testcontainers vía
 * {@link BaseIntegration}.
 *
 * <p>Semántica de las aserciones:
 * <ul>
 *   <li><b>permitido</b> = el status NO es 401 ni 403 (puede ser 200/400/404/422… de negocio); lo que
 *       importa es que la autorización no bloqueó. Se usa {@code isNotIn(401, 403)}.</li>
 *   <li><b>prohibido</b> = status EXACTO: 401 cuando falta credencial, 403 cuando el scope/rol es
 *       insuficiente.</li>
 * </ul>
 *
 * <p>Sobre {@code jwt.partnerToken(...)}: la cadena partner ({@code ResourceServerConfig}) usa el
 * {@code JwtDecoder} auto-configurado por defecto (firma RSA del JWKSource compartido + issuer +
 * exp/nbf), sin el control extra {@code typ=access} de la cadena BFF. Como {@code partnerToken} firma
 * con ese mismo JWKSource, incluye el issuer y un {@code exp} válido, la cadena partner ACEPTA el
 * token. Por eso los casos "permitido con scope" pueden afirmar {@code isNotIn(401, 403)} sin caer en
 * un 401 espurio del decoder.
 */
class PartnerAndEdgeAuthorizationIT extends BaseIntegration {

    private static final String PARTNER_CATALOG_PRODUCTS = "/api/v1/partner/catalog/products";
    private static final String PARTNER_ORDERS = "/api/v1/partner/orders";
    private static final String WEBHOOK_STRIPE = "/api/webhooks/stripe";
    private static final String ADMIN_DASHBOARD_METRICS = "/api/admin/dashboard/metrics";

    // ---------------------------------------------------------------------------------------------
    // Partner catalog — scope catalog.read mapeado a /api/v1/partner/catalog/**
    // ---------------------------------------------------------------------------------------------

    @Test
    void partnerCatalog_conScopeCatalogRead_noEsProhibido() {
        Integer status = client.get().uri(PARTNER_CATALOG_PRODUCTS)
                .header("Authorization", bearer(jwt.partnerToken(List.of("catalog.read")))).exchange()
                .returnResult(Void.class).getStatus().value();

        assertThat(status).as("partner con catalog.read no debe ser bloqueado por auth").isNotIn(401, 403);
    }

    @Test
    void partnerCatalog_sinBearer_es401() {
        client.get().uri(PARTNER_CATALOG_PRODUCTS).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void partnerCatalog_conScopeEquivocado_es403() {
        client.get().uri(PARTNER_CATALOG_PRODUCTS)
                .header("Authorization", bearer(jwt.partnerToken(List.of("shop.sync")))).exchange().expectStatus()
                .isForbidden();
    }

    // ---------------------------------------------------------------------------------------------
    // Partner orders — scope orders.write mapeado a /api/v1/partner/orders/**
    // ---------------------------------------------------------------------------------------------

    @Test
    void partnerOrders_conScopeOrdersWrite_noEsProhibido() {
        Integer status = client.post().uri(PARTNER_ORDERS)
                .header("Authorization", bearer(jwt.partnerToken(List.of("orders.write"))))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().returnResult(Void.class).getStatus()
                .value();

        assertThat(status).as("partner con orders.write no debe ser bloqueado por auth").isNotIn(401, 403);
    }

    @Test
    void partnerOrders_conScopeSoloCatalogRead_es403() {
        client.post().uri(PARTNER_ORDERS).header("Authorization", bearer(jwt.partnerToken(List.of("catalog.read"))))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isForbidden();
    }

    // ---------------------------------------------------------------------------------------------
    // Webhook stripe — permitAll: la verificación de firma es del controller (4xx de negocio, no auth)
    // ---------------------------------------------------------------------------------------------

    @Test
    void webhookStripe_sinFirmaNiCuerpoValido_noEs401Ni403() {
        Integer status = client.post().uri(WEBHOOK_STRIPE).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"invalid\":true}").exchange().returnResult(Void.class).getStatus().value();

        assertThat(status)
                .as("/api/webhooks/stripe es permitAll: la firma la valida el controller, no la cadena de auth")
                .isNotIn(401, 403);
    }

    // ---------------------------------------------------------------------------------------------
    // Cross-token — un token de una cadena no debe abrir la otra
    // ---------------------------------------------------------------------------------------------

    @Test
    void crossToken_userAdminContraPartnerCatalog_es403() {
        // Un access token de usuario ADMIN no lleva claim scope → sin SCOPE_catalog.read → 403.
        client.get().uri(PARTNER_CATALOG_PRODUCTS).header("Authorization", bearer(jwt.userToken("ADMIN"))).exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void crossToken_partnerContraAdminDashboard_es401O403() {
        // Un token de partner (claim scope, sin authorities ROLE_*) contra la cadena admin:
        // 403 si el decoder lo acepta pero no tiene ROLE_ADMIN, o 401 si el decoder de usuario lo
        // rechaza (p.ej. control typ/no-revocado). Cualquiera de los dos es un rechazo válido de auth.
        Integer status = client.get().uri(ADMIN_DASHBOARD_METRICS)
                .header("Authorization", bearer(jwt.partnerToken(List.of("catalog.read")))).exchange()
                .returnResult(Void.class).getStatus().value();

        assertThat(status).as("token de partner no debe acceder a la cadena admin").isIn(401, 403);
    }

    // ---------------------------------------------------------------------------------------------
    // Caso límite extra — UUID aleatorio donde el path lleva {id} (lectura de una orden concreta)
    // ---------------------------------------------------------------------------------------------

    @Test
    void partnerOrderById_sinBearer_es401() {
        client.get().uri(PARTNER_ORDERS + "/" + UUID.randomUUID()).exchange().expectStatus().isUnauthorized();
    }
}
