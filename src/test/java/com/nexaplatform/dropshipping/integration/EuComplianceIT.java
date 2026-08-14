package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.config.BaseIntegration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cumplimiento del Reglamento (UE) 2023/988 de extremo a extremo.
 *
 * <p>Cubre las tres obligaciones que la norma impone a la oferta en línea y que el escaparate tiene que
 * satisfacer sin intervención: el operador económico establecido en la Unión (art. 16), la identidad del
 * fabricante (art. 19.a) y las advertencias de seguridad (art. 19.d), incluida su herencia por la jerarquía
 * de categorías.
 *
 * <p>Se comprueba tanto lo que SE PUBLICA como lo que NO: un bloque con la dirección a medias aparentaría
 * cumplir sin cumplir, y esa es precisamente la clase de fallo que nadie detecta mirando la página.
 */
class EuComplianceIT extends BaseIntegration {

    private static final String RUTA_PUBLICA = "/api/compliance/responsible-person";
    private static final String RUTA_ADMIN = "/api/admin/compliance/responsible-person";

    private UUID categoriaPadre;
    private UUID categoriaHija;
    private String slugProducto;

    @BeforeEach
    void sembrarCatalogo() {
        seedCurrencies();
        categoriaPadre = insertCategory("belleza-test", null, "Belleza");
        categoriaHija = insertCategory("bell-dis-test", categoriaPadre, "Secador de pelo");
        slugProducto = "secador-test";
        insertProduct(slugProducto, categoriaHija, "Secador de pelo profesional");
    }

    // ── Operador económico (art. 16) ─────────────────────────────────────────

    @Nested
    @DisplayName("Operador económico establecido en la Unión")
    class OperadorEconomico {

        @Test
        @DisplayName("sin configurar, el endpoint público responde 204 y no 404")
        void sinConfigurarDevuelve204() {
            // 204 y no 404 a propósito: la ausencia es un estado de configuración válido, no una ruta que
            // no existe. Con 404 cada carga del escaparate ensuciaría el registro de errores.
            client.get().uri(RUTA_PUBLICA + "?lang=es").exchange().expectStatus().isNoContent();
        }

        @Test
        @DisplayName("completo y habilitado: se publica con la figura traducida")
        void completoSePublica() {
            guardarOperador(true, "50001");

            client.get().uri(RUTA_PUBLICA + "?lang=es").exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.name").isEqualTo("Jesus Enrique Finol Finol")
                    .jsonPath("$.postalCode").isEqualTo("50001")
                    .jsonPath("$.country").isEqualTo("ES")
                    .jsonPath("$.email").isEqualTo("jfinol02@gmail.com")
                    .jsonPath("$.role").isEqualTo("IMPORTER")
                    .jsonPath("$.roleLabel").isEqualTo("Importador")
                    .jsonPath("$.complete").isEqualTo(true);
        }

        @Test
        @DisplayName("la figura del art. 4.2 llega traducida al idioma pedido")
        void figuraTraducida() {
            guardarOperador(true, "50001");

            client.get().uri(RUTA_PUBLICA + "?lang=de").exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.roleLabel").isEqualTo("Importeur");
        }

        @Test
        @DisplayName("sin código postal NO se publica: la dirección del art. 16.3 estaría incompleta")
        void incompletoNoSePublica() {
            guardarOperador(true, null);

            client.get().uri(RUTA_PUBLICA + "?lang=es").exchange().expectStatus().isNoContent();
            // Pero el panel SÍ lo ve, marcado como incompleto: su trabajo es enseñar lo que falta.
            client.get().uri(RUTA_ADMIN + "?lang=es").header("Authorization", admin()).exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.complete").isEqualTo(false);
        }

        @Test
        @DisplayName("deshabilitado no se publica aunque esté completo")
        void deshabilitadoNoSePublica() {
            guardarOperador(false, "50001");

            client.get().uri(RUTA_PUBLICA + "?lang=es").exchange().expectStatus().isNoContent();
        }

        @Test
        @DisplayName("guardar exige ADMIN: un usuario normal recibe 403")
        void guardarExigeAdmin() {
            client.put().uri(RUTA_ADMIN + "?lang=es")
                    .header("Authorization", "Bearer " + jwt.userToken("USER"))
                    .bodyValue(cuerpo(true, "50001"))
                    .exchange().expectStatus().isForbidden();
        }

        @Test
        @DisplayName("guardar sin credenciales recibe 401")
        void guardarSinCredenciales() {
            client.put().uri(RUTA_ADMIN + "?lang=es").bodyValue(cuerpo(true, "50001"))
                    .exchange().expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("un país que no es ISO 3166-1 alfa-2 se rechaza con 400")
        void paisInvalido() {
            Map<String, Object> malo = cuerpo(true, "50001");
            malo.put("country", "España");

            client.put().uri(RUTA_ADMIN + "?lang=es").header("Authorization", admin()).bodyValue(malo)
                    .exchange().expectStatus().isBadRequest();
        }

        @Test
        @DisplayName("un correo mal formado se rechaza con 400")
        void correoInvalido() {
            Map<String, Object> malo = cuerpo(true, "50001");
            malo.put("email", "no-es-un-correo");

            client.put().uri(RUTA_ADMIN + "?lang=es").header("Authorization", admin()).bodyValue(malo)
                    .exchange().expectStatus().isBadRequest();
        }

        @Test
        @DisplayName("guardar dos veces actualiza la fila en vez de crear otra")
        void guardarEsIdempotente() {
            guardarOperador(true, "50001");
            guardarOperador(true, "50018");

            client.get().uri(RUTA_PUBLICA + "?lang=es").exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.postalCode").isEqualTo("50018");
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM eu_responsible_person", Long.class))
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("un cambio se ve de inmediato: el caché se invalida al guardar")
        void elCambioSeVeDeInmediato() {
            guardarOperador(true, "50001");
            // Primera lectura: deja el valor en caché.
            client.get().uri(RUTA_PUBLICA + "?lang=es").exchange().expectStatus().isOk();

            guardarOperador(true, "50999");

            // Sin invalidación esto devolvería 50001 durante cinco minutos, que es el fallo que ya costó
            // depurar con el margen en tiempo real.
            client.get().uri(RUTA_PUBLICA + "?lang=es").exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.postalCode").isEqualTo("50999");
        }

        @Test
        @DisplayName("las cuatro figuras del art. 4.2 se ofrecen traducidas")
        void listaDeFiguras() {
            client.get().uri("/api/admin/compliance/operator-roles?lang=fr")
                    .header("Authorization", admin()).exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.length()").isEqualTo(4)
                    .jsonPath("$[?(@.code=='IMPORTER')].label").isEqualTo("Importateur");
        }
    }

    // ── Advertencias de seguridad (art. 19.d) ────────────────────────────────

    @Nested
    @DisplayName("Advertencias de seguridad")
    class Advertencias {

        @Test
        @DisplayName("la advertencia del padre alcanza al producto de la categoría hija")
        void seHeredanDelPadre() {
            crearAdvertencia(categoriaPadre, "ELECTRICAL_SAFETY",
                    Map.of("es", "Aparato eléctrico: no sumergir en agua.",
                            "fr", "Appareil électrique : ne pas immerger."));

            client.get().uri("/api/catalog/products/" + slugProducto + "?lang=es").exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.compliance.safetyWarnings.length()").isEqualTo(1)
                    .jsonPath("$.compliance.safetyWarnings[0]")
                    .isEqualTo("Aparato eléctrico: no sumergir en agua.");
        }

        @Test
        @DisplayName("se muestran en el idioma del comprador")
        void enElIdiomaDelComprador() {
            crearAdvertencia(categoriaPadre, "ELECTRICAL_SAFETY",
                    Map.of("es", "Aparato eléctrico: no sumergir en agua.",
                            "fr", "Appareil électrique : ne pas immerger."));

            client.get().uri("/api/catalog/products/" + slugProducto + "?lang=fr").exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.compliance.safetyWarnings[0]")
                    .isEqualTo("Appareil électrique : ne pas immerger.");
        }

        @Test
        @DisplayName("sin traducción al idioma pedido cae a español en vez de desaparecer")
        void caeAEspanolSiFaltaLaTraduccion() {
            // Una advertencia que no se muestra es peor que una en otro idioma: la primera deja al
            // comprador sin el aviso, la segunda al menos se lo da.
            crearAdvertencia(categoriaPadre, "ELECTRICAL_SAFETY",
                    Map.of("es", "Aparato eléctrico: no sumergir en agua."));

            client.get().uri("/api/catalog/products/" + slugProducto + "?lang=nl").exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.compliance.safetyWarnings[0]")
                    .isEqualTo("Aparato eléctrico: no sumergir en agua.");
        }

        @Test
        @DisplayName("declarada en padre e hija, la misma advertencia no sale dos veces")
        void noSeDuplicaPorLaHerencia() {
            crearAdvertencia(categoriaPadre, "ELECTRICAL_SAFETY", Map.of("es", "Aviso del padre"));
            crearAdvertencia(categoriaHija, "ELECTRICAL_SAFETY", Map.of("es", "Aviso de la hija"));

            client.get().uri("/api/catalog/products/" + slugProducto + "?lang=es").exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.compliance.safetyWarnings.length()").isEqualTo(1);
        }

        @Test
        @DisplayName("una advertencia desactivada deja de mostrarse")
        void desactivadaNoSeMuestra() {
            crearAdvertencia(categoriaPadre, "ELECTRICAL_SAFETY", Map.of("es", "Aviso"));
            jdbcTemplate.update("UPDATE category_safety_warning SET active = false");
            vaciarCaches();

            client.get().uri("/api/catalog/products/" + slugProducto + "?lang=es").exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.compliance.safetyWarnings.length()").isEqualTo(0);
        }

        @Test
        @DisplayName("varias advertencias respetan el orden de posición")
        void respetanElOrden() {
            crearAdvertencia(categoriaPadre, "SEGUNDA", 20, Map.of("es", "Va segunda"));
            crearAdvertencia(categoriaPadre, "PRIMERA", 10, Map.of("es", "Va primera"));

            client.get().uri("/api/catalog/products/" + slugProducto + "?lang=es").exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.compliance.safetyWarnings[0]").isEqualTo("Va primera")
                    .jsonPath("$.compliance.safetyWarnings[1]").isEqualTo("Va segunda");
        }

        @Test
        @DisplayName("gestionar advertencias exige ADMIN")
        void gestionarExigeAdmin() {
            client.put().uri("/api/admin/compliance/categories/" + categoriaPadre + "/warnings")
                    .header("Authorization", "Bearer " + jwt.userToken("USER"))
                    .bodyValue(Map.of("code", "X", "texts", Map.of("es", "y")))
                    .exchange().expectStatus().isForbidden();
        }

        @Test
        @DisplayName("un código con caracteres raros se rechaza con 400")
        void codigoInvalido() {
            client.put().uri("/api/admin/compliance/categories/" + categoriaPadre + "/warnings")
                    .header("Authorization", admin())
                    .bodyValue(Map.of("code", "no válido; drop", "texts", Map.of("es", "y")))
                    .exchange().expectStatus().isBadRequest();
        }

        @Test
        @DisplayName("borrar una advertencia la retira de la ficha")
        void borrarLaRetira() {
            crearAdvertencia(categoriaPadre, "ELECTRICAL_SAFETY", Map.of("es", "Aviso"));
            String id = jdbcTemplate.queryForObject(
                    "SELECT id::text FROM category_safety_warning WHERE code = 'ELECTRICAL_SAFETY'",
                    String.class);

            client.delete().uri("/api/admin/compliance/warnings/" + id).header("Authorization", admin())
                    .exchange().expectStatus().isNoContent();

            client.get().uri("/api/catalog/products/" + slugProducto + "?lang=es").exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.compliance.safetyWarnings.length()").isEqualTo(0);
        }
    }

    // ── Fabricante (art. 19.a) y estado del catálogo ─────────────────────────

    @Nested
    @DisplayName("Fabricante y estado del catálogo")
    class Fabricante {

        @Test
        @DisplayName("la ficha marca el fabricante como incompleto cuando falta")
        void incompletoCuandoFalta() {
            client.get().uri("/api/catalog/products/" + slugProducto + "?lang=es").exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.compliance.manufacturerComplete").isEqualTo(false);
        }

        @Test
        @DisplayName("con los tres datos, la ficha lo publica y lo da por completo")
        void completoConLosTresDatos() {
            jdbcTemplate.update("UPDATE product SET manufacturer_name = ?, manufacturer_address = ?, "
                    + "manufacturer_email = ? WHERE slug = ?", "Fábrica Ejemplo S.L.",
                    "Calle Industria 1, 50001 Zaragoza", "fabrica@ejemplo.com", slugProducto);
            vaciarCaches();

            client.get().uri("/api/catalog/products/" + slugProducto + "?lang=es").exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.compliance.manufacturerComplete").isEqualTo(true)
                    .jsonPath("$.compliance.manufacturerName").isEqualTo("Fábrica Ejemplo S.L.")
                    .jsonPath("$.compliance.manufacturerEmail").isEqualTo("fabrica@ejemplo.com");
        }

        @Test
        @DisplayName("con dos de tres sigue incompleto: el art. 19.a exige los tres")
        void dosDeTresSigueIncompleto() {
            jdbcTemplate.update("UPDATE product SET manufacturer_name = ?, manufacturer_address = ? "
                    + "WHERE slug = ?", "Fábrica", "Calle 1", slugProducto);
            vaciarCaches();

            client.get().uri("/api/catalog/products/" + slugProducto + "?lang=es").exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.compliance.manufacturerComplete").isEqualTo(false);
        }

        @Test
        @DisplayName("el estado del catálogo cuenta las referencias activas sin fabricante")
        void estadoCuentaLasIncompletas() {
            client.get().uri("/api/admin/compliance/status?lang=es").header("Authorization", admin())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.activeProducts").isEqualTo(1)
                    .jsonPath("$.missingManufacturer").isEqualTo(1)
                    .jsonPath("$.responsiblePersonReady").isEqualTo(false);
        }

        @Test
        @DisplayName("un producto en borrador no cuenta: la obligación recae sobre la oferta")
        void elBorradorNoCuenta() {
            jdbcTemplate.update("UPDATE product SET status = 'DRAFT' WHERE slug = ?", slugProducto);

            client.get().uri("/api/admin/compliance/status?lang=es").header("Authorization", admin())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.activeProducts").isEqualTo(0)
                    .jsonPath("$.missingManufacturer").isEqualTo(0);
        }

        @Test
        @DisplayName("el listado de incompletos devuelve la referencia con su título")
        void listadoDeIncompletos() {
            client.get().uri("/api/admin/compliance/products/missing-manufacturer?page=0&size=20&lang=es")
                    .header("Authorization", admin()).exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.content.length()").isEqualTo(1)
                    .jsonPath("$.content[0].slug").isEqualTo(slugProducto)
                    .jsonPath("$.content[0].title").isEqualTo("Secador de pelo profesional");
        }

        @Test
        @DisplayName("el estado del catálogo exige ADMIN")
        void estadoExigeAdmin() {
            client.get().uri("/api/admin/compliance/status?lang=es")
                    .header("Authorization", "Bearer " + jwt.userToken("USER"))
                    .exchange().expectStatus().isForbidden();
        }
    }

    // ── Utilidades de siembra ────────────────────────────────────────────────

    private String admin() {
        return "Bearer " + jwt.userToken("ADMIN");
    }

    private Map<String, Object> cuerpo(boolean habilitado, String codigoPostal) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("enabled", habilitado);
        m.put("name", "Jesus Enrique Finol Finol");
        m.put("addressLine", "Calle Catelvi 7, 1D");
        m.put("postalCode", codigoPostal);
        m.put("city", "Zaragoza");
        m.put("region", "Zaragoza");
        m.put("country", "ES");
        m.put("email", "jfinol02@gmail.com");
        m.put("role", "IMPORTER");
        return m;
    }

    private void guardarOperador(boolean habilitado, String codigoPostal) {
        client.put().uri(RUTA_ADMIN + "?lang=es").header("Authorization", admin())
                .bodyValue(cuerpo(habilitado, codigoPostal))
                .exchange().expectStatus().isOk();
    }

    private void crearAdvertencia(UUID categoria, String codigo, Map<String, String> textos) {
        crearAdvertencia(categoria, codigo, 0, textos);
    }

    private void crearAdvertencia(UUID categoria, String codigo, int posicion, Map<String, String> textos) {
        client.put().uri("/api/admin/compliance/categories/" + categoria + "/warnings")
                .header("Authorization", admin())
                .bodyValue(Map.of("code", codigo, "position", posicion, "active", true, "texts", textos))
                .exchange().expectStatus().isOk();
        vaciarCaches();
    }

    private void seedCurrencies() {
        jdbcTemplate.update("INSERT INTO currency_rate (id, code, name, symbol, locale, rate_vs_usd, active) "
                + "VALUES (gen_random_uuid(), 'USD', 'US Dollar', '$', 'en-US', 1.00, true) "
                + "ON CONFLICT (code) DO NOTHING");
        jdbcTemplate.update("INSERT INTO currency_rate (id, code, name, symbol, locale, rate_vs_usd, active) "
                + "VALUES (gen_random_uuid(), 'CNY', 'Chinese Yuan', '¥', 'zh-CN', 7.24, true) "
                + "ON CONFLICT (code) DO NOTHING");
    }

    private UUID insertCategory(String slug, UUID padre, String nombreEs) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO category (id, slug, parent_id, name_zh, position, active, source) "
                + "VALUES (?, ?, ?, ?, 0, true, '1688')", id, slug, padre, nombreEs);
        jdbcTemplate.update("INSERT INTO category_translation (id, category_id, language, name) "
                + "VALUES (gen_random_uuid(), ?, 'es', ?)", id, nombreEs);
        return id;
    }

    private void insertProduct(String slug, UUID categoryId, String tituloEs) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO product (id, slug, external_id, source, category_id, title_zh, "
                + "status, base_price, currency, review_count, monthly_sales, trend_score, free_shipping, "
                + "self_pickup, has_video, inventory_count, moq, shipping_cny, iva_cny, created_at, "
                + "updated_at, ingested_at) "
                + "VALUES (?, ?, ?, '1688', ?, ?, 'ACTIVE', ?, 'CNY', 0, 0, 0, false, false, false, 10, 1, "
                + "5, 1, now(), now(), now())",
                id, slug, "EXT-" + slug, categoryId, tituloEs, new BigDecimal("50.00"));
        jdbcTemplate.update("INSERT INTO product_translation (id, product_id, language, title, description) "
                + "VALUES (gen_random_uuid(), ?, 'es', ?, ?)", id, tituloEs, tituloEs + " — descripción");
        jdbcTemplate.update("INSERT INTO product_image (id, product_id, position, role, source_url, cdn_url) "
                + "VALUES (gen_random_uuid(), ?, 0, 'MAIN', ?, ?)", id,
                "https://origen.test/" + slug + ".jpg", "https://cdn.test/" + slug + ".jpg");
    }
}
