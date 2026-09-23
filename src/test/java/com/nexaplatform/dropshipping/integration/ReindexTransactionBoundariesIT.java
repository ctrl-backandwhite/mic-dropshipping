package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.config.BaseIntegration;
import com.nexaplatform.dropshipping.infrastructure.integration.search.ProductIndexer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * El reindexado completo NO puede sostener UNA transacción de base de datos durante todo el barrido.
 *
 * <p><b>Por qué existe este test.</b> El 12-sep-2026, al desplegar la migración v170, una conexión del
 * backend llevaba veinte minutos «idle in transaction» sobre {@code product}. Era este reindexado: su
 * {@code @Transactional(readOnly = true)} envolvía el barrido entero para poder recorrer las colecciones
 * perezosas, así que la transacción quedaba abierta desde el primer producto hasta el último —medido en
 * local sobre 7.646 productos: <b>más de doce minutos</b>— reteniendo {@code AccessShareLock} sobre
 * {@code product} y sus tablas hijas. El {@code ALTER TABLE} de la migración pidió cerrojo exclusivo, se
 * encoló detrás, y una petición de cerrojo exclusivo EN ESPERA bloquea a todo lo que llegue después,
 * incluidos los SELECT: el catálogo de preproducción estuvo diecisiete minutos parado.
 *
 * <p><b>Qué invariante fija.</b> Que ninguna llamada a OpenSearch ocurra con una transacción abierta. Es
 * la forma comprobable de exigir lo que de verdad importa: que el trabajo de red no alargue una
 * transacción, y por tanto que cada producto se lea en la suya, corta.
 *
 * <p><b>Por qué no basta un tiempo de espera en Postgres.</b> Se probó contra un Postgres 18.6 real:
 * {@code idle_in_transaction_session_timeout} mide huecos ociosos CONTINUOS y los reinicia con cada
 * consulta, así que esta transacción —que no para de consultar, con huecos medidos de 1,0 s como máximo—
 * lo atraviesa entera. El único freno del servidor que la corta es {@code transaction_timeout}, y ese
 * también cortaría el reindexado legítimo, dejando el índice purgado a medias. El arreglo tiene que ir
 * en el código; el freno del servidor viene después, como red por debajo.
 */
@DisplayName("Reindexado: fronteras de transacción")
class ReindexTransactionBoundariesIT extends BaseIntegration {

    @Autowired
    private ProductIndexer productIndexer;

    /**
     * Sin OpenSearch real en los tests. Lo que interesa del doble no es lo que devuelve, sino el momento
     * en que se le llama: apunta si en ese instante había una transacción viva.
     */
    @MockitoBean
    private OpenSearchClient openSearchClient;

    /** Una anotación por llamada a OpenSearch: ¿había transacción abierta? */
    private final List<Boolean> transaccionVivaAlIndexar = new CopyOnWriteArrayList<>();

    @BeforeEach
    void prepara() throws Exception {
        transaccionVivaAlIndexar.clear();
        when(openSearchClient.index(any(IndexRequest.class))).thenAnswer(invocacion -> {
            transaccionVivaAlIndexar.add(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        });
        siembraProductos(3);
    }

    @Test
    @DisplayName("no hay ninguna transacción abierta cuando se llama a OpenSearch")
    void noSostieneLaTransaccionMientrasHablaConOpenSearch() {
        productIndexer.reindexAll();

        assertThat(transaccionVivaAlIndexar).as("se esperaban tres llamadas a OpenSearch, una por producto sembrado")
                .hasSize(3);
        assertThat(transaccionVivaAlIndexar)
                .as("cada llamada a OpenSearch bloquea la conexión de Postgres mientras dura; si ocurre "
                        + "dentro de una transacción, esa transacción retiene sus cerrojos durante toda "
                        + "la red y el barrido entero acaba siendo UNA transacción de minutos")
                .containsOnly(false);
    }

    private void siembraProductos(int cuantos) {
        UUID proveedor = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO supplier (id, external_id, source, name) VALUES (?, ?, '1688', ?)", proveedor,
                "SUP-TX", "Proveedor de prueba");
        UUID categoria = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO category (id, slug, name_zh, active) VALUES (?, ?, ?, true)", categoria,
                "tx-pruebas", "测试分类");

        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < cuantos; i++) {
            UUID id = UUID.randomUUID();
            Instant ahora = Instant.now();
            jdbcTemplate.update("INSERT INTO product (id, slug, external_id, source, supplier_id, category_id, "
                    + "title_zh, status, base_price, currency, rating, review_count, monthly_sales, trend_score, "
                    + "ship_from, free_shipping, self_pickup, has_video, inventory_count, moq, shipping_cny, "
                    + "iva_cny, created_at, updated_at, ingested_at) "
                    + "VALUES (?, ?, ?, '1688', ?, ?, ?, 'ACTIVE', ?, 'CNY', 4.5, 0, 10, 1, 'CN', false, false, "
                    + "false, 1, 1, 5, 1, ?, ?, ?)", id, "tx-producto-" + i, "EXT-TX-" + i, proveedor, categoria,
                    "产品 " + i, new BigDecimal("10.00"), Timestamp.from(ahora), Timestamp.from(ahora),
                    Timestamp.from(ahora));
            // Colección perezosa: es justo lo que obligaba a mantener la sesión abierta durante el barrido.
            jdbcTemplate.update("INSERT INTO product_translation (id, product_id, language, title, description) "
                    + "VALUES (gen_random_uuid(), ?, 'es', ?, ?)", id, "Producto " + i, "Descripción " + i);
            jdbcTemplate.update(
                    "INSERT INTO product_image (id, product_id, position, role, source_url, cdn_url) "
                            + "VALUES (gen_random_uuid(), ?, 0, 'MAIN', ?, ?)",
                    id, "https://origen.test/" + id + ".jpg", "https://cdn.test/" + id + ".jpg");
            jdbcTemplate.update("INSERT INTO product_attribute (id, product_id, attr_key, attr_value) "
                    + "VALUES (gen_random_uuid(), ?, ?, ?)", id, "material", "algodón");
            ids.add(id);
        }
        assertThat(ids).hasSize(cuantos);
    }
}
