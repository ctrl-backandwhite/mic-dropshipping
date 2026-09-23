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
 *   <li>GET  /api/catalog/products                         — authenticated (muro del catálogo)</li>
 *   <li>GET  /api/catalog/home/sections                    — permitAll (portada pública)</li>
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
    private static final String IMAGEN = "/api/admin/catalog/products/images/%s";
    private static final String IMAGENES = "/api/admin/catalog/products/%s/images";
    private static final String ORDEN_IMAGENES = "/api/admin/catalog/products/%s/images/order";
    private static final String VIDEO = "/api/admin/catalog/products/%s/video";
    private static final String URL_DE_ORIGEN = "/api/admin/catalog/products/%s/source-url";
    private static final String PRODUCTO = "/api/admin/catalog/products/%s";
    private static final String VALOR_DE_VARIANTE = "/api/admin/catalog/variant-values/%s";
    private static final String REVIEWER = "REVIEWER";

    /**
     * PROHIBIDO: el listado del catálogo exige cuenta.
     *
     * <p>El muro estaba solo en el frontend (ProtectedRoute), que oculta la vista pero no cierra la
     * API: sin credenciales se sacaban 100 productos por llamada —con precio, ventas mensuales y trend
     * score—, o sea el catálogo entero en ~45 peticiones. Un scraper no usa el navegador.
     */
    @Test
    void catalogProducts_anonymous_isRejected() {
        client.get().uri(CATALOG_PRODUCTS).exchange().expectStatus().isUnauthorized();
    }

    /** PERMITIDO: con cuenta, el mismo listado es accesible. */
    @Test
    void catalogProducts_authenticated_isReachable() {
        client.get().uri(CATALOG_PRODUCTS).header("Authorization", bearer(jwt.userToken("USER"))).exchange()
                .expectStatus().value(s -> assertThat(s).isNotIn(401, 403));
    }

    /** PROHIBIDO: la búsqueda también permite enumerar, así que va detrás del mismo muro. */
    @Test
    void search_anonymous_isRejected() {
        client.get().uri("/api/search?q=vestido").exchange().expectStatus().isUnauthorized();
    }

    /**
     * PERMITIDO: la portada la ven visitantes sin cuenta.
     *
     * <p>Es el contrapeso del muro: cerrar de más echaba al visitante a la pantalla de login nada más
     * entrar en la home, porque esta pedía el listado solo para leer el total de SKUs.
     */
    @Test
    void homeSections_anonymous_isReachable() {
        client.get().uri("/api/catalog/home/sections?lang=es&perSection=6").exchange().expectStatus()
                .value(s -> assertThat(s).isNotIn(401, 403));
    }

    /** PERMITIDO: margin-estimate exige ROLE_ADMIN. */
    @Test
    void marginEstimate_admin_isAllowed() {
        client.get().uri(String.format(MARGIN_ESTIMATE, UUID.randomUUID()))
                .header("Authorization", bearer(jwt.userToken("ADMIN"))).exchange().expectStatus()
                .value(s -> assertThat(s).isNotIn(401, 403));
    }

    /** PROHIBIDO: un USER no llega al margen → 403. */
    @Test
    void marginEstimate_user_isForbidden() {
        client.get().uri(String.format(MARGIN_ESTIMATE, UUID.randomUUID()))
                .header("Authorization", bearer(jwt.userToken("USER"))).exchange().expectStatus().isEqualTo(403);
    }

    /** PROHIBIDO: sin token → 401. */
    @Test
    void marginEstimate_anonymous_isUnauthorized() {
        client.get().uri(String.format(MARGIN_ESTIMATE, UUID.randomUUID())).exchange().expectStatus().isEqualTo(401);
    }

    /** PERMITIDO: /api/me/** solo pide autenticación; un USER pasa (no 401/403). */
    @Test
    void meOrders_user_isAllowed() {
        client.get().uri(ME_ORDERS).header("Authorization", bearer(jwt.userToken("USER"))).exchange().expectStatus()
                .value(s -> assertThat(s).isNotIn(401, 403));
    }

    /** PROHIBIDO: /api/me/orders sin token → 401. */
    @Test
    void meOrders_anonymous_isUnauthorized() {
        client.get().uri(ME_ORDERS).exchange().expectStatus().isEqualTo(401);
    }

    /** PERMITIDO: /api/admin/orders/** lo pueden tocar ADMIN y OPERATOR. */
    @Test
    void shipOrder_admin_isAllowed() {
        client.post().uri(String.format(ORDER_SHIP, UUID.randomUUID()))
                .header("Authorization", bearer(jwt.userToken("ADMIN"))).exchange().expectStatus()
                .value(s -> assertThat(s).isNotIn(401, 403));
    }

    /** PERMITIDO: OPERATOR (soporte) también puede procesar órdenes. */
    @Test
    void shipOrder_operator_isAllowed() {
        client.post().uri(String.format(ORDER_SHIP, UUID.randomUUID()))
                .header("Authorization", bearer(jwt.userToken("OPERATOR"))).exchange().expectStatus()
                .value(s -> assertThat(s).isNotIn(401, 403));
    }

    /** PROHIBIDO: un USER no puede marcar enviado → 403. */
    @Test
    void shipOrder_user_isForbidden() {
        client.post().uri(String.format(ORDER_SHIP, UUID.randomUUID()))
                .header("Authorization", bearer(jwt.userToken("USER"))).exchange().expectStatus().isEqualTo(403);
    }

    /** PERMITIDO: las ganancias del operador son para ADMIN y OPERATOR. */
    @Test
    void operatorEarnings_operator_isAllowed() {
        client.get().uri(OPERATOR_EARNINGS).header("Authorization", bearer(jwt.userToken("OPERATOR"))).exchange()
                .expectStatus().value(s -> assertThat(s).isNotIn(401, 403));
    }

    /** PROHIBIDO: un USER no ve las ganancias del operador → 403. */
    @Test
    void operatorEarnings_user_isForbidden() {
        client.get().uri(OPERATOR_EARNINGS).header("Authorization", bearer(jwt.userToken("USER"))).exchange()
                .expectStatus().isEqualTo(403);
    }

    /** PERMITIDO: el dashboard (resto de /api/admin/**) es EXCLUSIVO de ADMIN. */
    @Test
    void dashboardMetrics_admin_isAllowed() {
        client.get().uri(DASHBOARD_METRICS).header("Authorization", bearer(jwt.userToken("ADMIN"))).exchange()
                .expectStatus().value(s -> assertThat(s).isNotIn(401, 403));
    }

    /** PROHIBIDO: un OPERATOR no entra al dashboard admin → 403. */
    @Test
    void dashboardMetrics_operator_isForbidden() {
        client.get().uri(DASHBOARD_METRICS).header("Authorization", bearer(jwt.userToken("OPERATOR"))).exchange()
                .expectStatus().isEqualTo(403);
    }

    /* =================== REVIEWER: revisión del material gráfico ===================
     *
     * El rol existe para que alguien pueda arreglar las fotos de una ficha —borrar las que no son del
     * producto, reordenar la galería, quitar el vídeo— sin darle el panel ni los números. Las reglas
     * son por MÉTODO y ruta exacta, así que lo que de verdad hay que probar no es solo lo que puede,
     * sino que un comodín no le haya abierto de paso el precio o el borrado del producto.
     */

    /** PERMITIDO: borrar una foto de la galería es el caso central del rol. */
    @Test
    void borrarImagen_revisor_isAllowed() {
        client.delete().uri(String.format(IMAGEN, UUID.randomUUID()))
                .header("Authorization", bearer(jwt.userToken(REVIEWER))).exchange().expectStatus()
                .value(s -> assertThat(s).isNotIn(401, 403));
    }

    /** PERMITIDO: añadir a la galería una foto que venía en una variante. */
    @Test
    void anadirImagen_revisor_isAllowed() {
        client.post().uri(String.format(IMAGENES, UUID.randomUUID()))
                .header("Authorization", bearer(jwt.userToken(REVIEWER))).header("Content-Type", "application/json")
                .bodyValue("{\"sourceUrl\":\"https://cbu01.alicdn.com/img/ibank/x.jpg\"}").exchange().expectStatus()
                .value(s -> assertThat(s).isNotIn(401, 403));
    }

    /** PERMITIDO: reordenar la galería (la primera pasa a ser la principal). */
    @Test
    void reordenarImagenes_revisor_isAllowed() {
        client.put().uri(String.format(ORDEN_IMAGENES, UUID.randomUUID()))
                .header("Authorization", bearer(jwt.userToken(REVIEWER))).header("Content-Type", "application/json")
                .bodyValue("{\"imageIds\":[]}").exchange().expectStatus().value(s -> assertThat(s).isNotIn(401, 403));
    }

    /** PERMITIDO: quitar el vídeo de explicación. */
    @Test
    void borrarVideo_revisor_isAllowed() {
        client.delete().uri(String.format(VIDEO, UUID.randomUUID()))
                .header("Authorization", bearer(jwt.userToken(REVIEWER))).exchange().expectStatus()
                .value(s -> assertThat(s).isNotIn(401, 403));
    }

    /** PERMITIDO: corregir el enlace a la oferta de origen, que es contra lo que coteja las fotos. */
    @Test
    void editarUrlDeOrigen_revisor_isAllowed() {
        client.put().uri(String.format(URL_DE_ORIGEN, UUID.randomUUID()))
                .header("Authorization", bearer(jwt.userToken(REVIEWER))).header("Content-Type", "application/json")
                .bodyValue("{\"sourceUrl\":\"https://detail.1688.com/offer/1.html\"}").exchange().expectStatus()
                .value(s -> assertThat(s).isNotIn(401, 403));
    }

    /** PERMITIDO: retirar un color sin foto válida (regla color=imagen). */
    @Test
    void borrarValorDeVariante_revisor_isAllowed() {
        client.delete().uri(String.format(VALOR_DE_VARIANTE, UUID.randomUUID()))
                .header("Authorization", bearer(jwt.userToken(REVIEWER))).exchange().expectStatus()
                .value(s -> assertThat(s).isNotIn(401, 403));
    }

    /**
     * PROHIBIDO, y es la prueba que justifica enumerar las rutas una a una: la edición rápida cuelga de
     * PUT /products/{id}, la misma forma que PUT /products/{id}/source-url con un segmento menos. Por
     * ahí entran «Verificado» —que decide el dueño y nadie más— y los importes en yuanes.
     */
    @Test
    void edicionRapida_revisor_isForbidden() {
        client.put().uri(String.format(PRODUCTO, UUID.randomUUID()))
                .header("Authorization", bearer(jwt.userToken(REVIEWER))).header("Content-Type", "application/json")
                .bodyValue("{\"verified\":true}").exchange().expectStatus().isEqualTo(403);
    }

    /**
     * PROHIBIDO: borrar el producto. DELETE /products/{id} tiene un segmento MENOS que el borrado de
     * una imagen, y si el comodín de aquella regla cruzara, el revisor podría vaciar el catálogo.
     */
    @Test
    void borrarProducto_revisor_isForbidden() {
        client.delete().uri(String.format(PRODUCTO, UUID.randomUUID()))
                .header("Authorization", bearer(jwt.userToken(REVIEWER))).exchange().expectStatus().isEqualTo(403);
    }

    /** PROHIBIDO: el revisor no entra al panel. */
    @Test
    void dashboardMetrics_revisor_isForbidden() {
        client.get().uri(DASHBOARD_METRICS).header("Authorization", bearer(jwt.userToken(REVIEWER))).exchange()
                .expectStatus().isEqualTo(403);
    }

    /** PROHIBIDO: ni el margen, que es exclusivo de ADMIN. */
    @Test
    void marginEstimate_revisor_isForbidden() {
        client.get().uri(String.format(MARGIN_ESTIMATE, UUID.randomUUID()))
                .header("Authorization", bearer(jwt.userToken(REVIEWER))).exchange().expectStatus().isEqualTo(403);
    }

    /** PROHIBIDO: y un USER corriente no toca las fotos de nadie. */
    @Test
    void borrarImagen_user_isForbidden() {
        client.delete().uri(String.format(IMAGEN, UUID.randomUUID()))
                .header("Authorization", bearer(jwt.userToken("USER"))).exchange().expectStatus().isEqualTo(403);
    }
}
