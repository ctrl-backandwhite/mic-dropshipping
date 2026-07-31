package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.application.service.StockService;
import com.nexaplatform.dropshipping.config.PersistenceITBase;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT de persistencia de {@link ProductVariantRepository}: ejercita el JPQL de selección de imágenes a
 * espejar ({@code findNeedingImageMirror}) y los {@code @Modifying} (markImageCdn / markImageFailed)
 * contra Postgres real. Cada variante necesita un {@link ProductEntity} padre (FK NOT NULL).
 */
class ProductVariantRepositoryIT extends PersistenceITBase {

    private static final String PUBLIC_PREFIX = "https://cdn.nexa.local/%";

    @Autowired
    ProductVariantRepository variants;

    @Autowired
    ProductJpaRepositoryAdapter products;

    @Autowired
    TestEntityManager em;

    private ProductEntity product;

    @BeforeEach
    void setUp() {
        product = products.save(newProduct("var"));
    }

    @Test
    void findNeedingImageMirror_selectsRowsWithSourceAndNoOurCdnAndNotFailed() {
        // SÍ: tiene source y aún no apunta a nuestro storage, sin fallo previo.
        ProductVariantEntity needsNullCdn = variants.save(variant("v-null", "https://src/1.jpg", null, null));
        // SÍ: cdn de otro storage (NOT LIKE prefix).
        ProductVariantEntity needsOtherCdn =
                variants.save(variant("v-other", "https://src/2.jpg", "https://foreign.com/2.jpg", null));
        // NO: ya espejada en nuestro storage.
        variants.save(variant("v-mirrored", "https://src/3.jpg", "https://cdn.nexa.local/3.jpg", null));
        // NO: marcada como fallida.
        variants.save(variant("v-failed", "https://src/4.jpg", null, Instant.parse("2026-01-01T00:00:00Z")));
        // NO: sin imagen de origen.
        variants.save(variant("v-nosrc", null, null, null));
        // NO: imagen de origen vacía.
        variants.save(variant("v-empty", "", null, null));

        List<ProductVariantEntity> result =
                variants.findNeedingImageMirror(PUBLIC_PREFIX, PageRequest.of(0, 50));

        assertThat(result).extracting(ProductVariantEntity::getId)
                .containsExactlyInAnyOrder(needsNullCdn.getId(), needsOtherCdn.getId());
    }

    @Test
    void findNeedingImageMirror_honoursPageableLimit() {
        variants.save(variant("a", "https://src/a.jpg", null, null));
        variants.save(variant("b", "https://src/b.jpg", null, null));
        variants.save(variant("c", "https://src/c.jpg", null, null));

        List<ProductVariantEntity> result =
                variants.findNeedingImageMirror(PUBLIC_PREFIX, PageRequest.of(0, 2));

        assertThat(result).hasSize(2);
    }

    @Test
    void markImageCdn_setsImageCdnUrl() {
        ProductVariantEntity saved = variants.save(variant("v-cdn", "https://src/x.jpg", null, null));
        UUID id = saved.getId();

        variants.markImageCdn(id, "https://cdn.nexa.local/x.jpg");

        em.flush();
        em.clear();
        ProductVariantEntity reloaded = variants.findById(id).orElseThrow();
        assertThat(reloaded.getImageCdnUrl()).isEqualTo("https://cdn.nexa.local/x.jpg");
        assertThat(reloaded.getImageMirrorFailedAt()).isNull();
    }

    @Test
    void markImageFailed_setsImageMirrorFailedAt() {
        ProductVariantEntity saved = variants.save(variant("v-fail", "https://src/dead.jpg", null, null));
        UUID id = saved.getId();
        Instant at = Instant.parse("2026-02-20T08:30:00Z");

        variants.markImageFailed(id, at);

        em.flush();
        em.clear();
        ProductVariantEntity reloaded = variants.findById(id).orElseThrow();
        assertThat(reloaded.getImageMirrorFailedAt()).isEqualTo(at);
        // Tras fallar, deja de aparecer entre las pendientes de espejar.
        assertThat(variants.findNeedingImageMirror(PUBLIC_PREFIX, PageRequest.of(0, 50)))
                .extracting(ProductVariantEntity::getId)
                .doesNotContain(id);
    }

    @Test
    void derivedFinders_resolveByProductSkuAndExternalId() {
        ProductVariantEntity v = variant("v-sku", "https://src/s.jpg", null, null);
        v.setSku("SKU-123");
        v.setExternalId("EXT-9");
        variants.save(v);

        assertThat(variants.findByProductId(product.getId())).hasSize(1);
        assertThat(variants.findBySku("SKU-123")).isPresent();
        assertThat(variants.findByProductIdAndExternalId(product.getId(), "EXT-9")).isPresent();
    }

    /* ============================ Stock: descuento en venta / reintegro en cancelación ============================ */

    @Test
    void deductStock_subtractsWhenEnough_andBlocksOversell() {
        ProductVariantEntity v = variants.save(variantWithStock("v-deduct", 10));
        UUID id = v.getId();
        em.flush();

        // Hay stock suficiente → descuenta y afecta 1 fila.
        int ok = variants.deductStock(id, 3);
        assertThat(ok).isEqualTo(1);
        em.flush();
        em.clear();
        assertThat(variants.findById(id).orElseThrow().getStock()).isEqualTo(7);

        // Pide más de lo que hay → 0 filas, stock intacto (control de sobreventa: nunca negativo).
        int blocked = variants.deductStock(id, 999);
        assertThat(blocked).isZero();
        em.flush();
        em.clear();
        assertThat(variants.findById(id).orElseThrow().getStock()).isEqualTo(7);
    }

    @Test
    void restoreStock_addsBack_andZeroStock_forcesZero() {
        ProductVariantEntity v = variants.save(variantWithStock("v-restore", 5));
        UUID id = v.getId();
        em.flush();

        assertThat(variants.restoreStock(id, 4)).isEqualTo(1);
        em.flush();
        em.clear();
        assertThat(variants.findById(id).orElseThrow().getStock()).isEqualTo(9);

        assertThat(variants.zeroStock(id)).isEqualTo(1);
        em.flush();
        em.clear();
        assertThat(variants.findById(id).orElseThrow().getStock()).isZero();
    }

    /**
     * Modelo de DROPSHIPPING a nivel de servicio + Postgres real: la plataforma no mantiene inventario, así
     * que ni el pago ({@code deductForOrder}) descuenta stock ni la cancelación ({@code restoreForOrder}) lo
     * reintegra. El stock guardado (informativo) permanece intacto tras el ciclo completo pago→cancelar.
     */
    @Test
    void stockService_dropshipping_neverChangesStock() {
        StockService stockService = new StockService();
        ProductVariantEntity a = variants.save(variantWithStock("v-a", 10));
        ProductVariantEntity b = variants.save(variantWithStock("v-b", 8));
        em.flush();

        Order order = Order.builder().orderNumber("ORD-IT-1").items(List.of(
                OrderItem.builder().variantId(a.getId()).quantity(3).build(),
                OrderItem.builder().variantId(b.getId()).quantity(2).build(),
                OrderItem.builder().variantId(null).quantity(5).build()))
                .build();

        // Pago confirmado → NO descuenta (dropshipping: el stock no se agota).
        stockService.deductForOrder(order);
        em.flush();
        em.clear();
        assertThat(variants.findById(a.getId()).orElseThrow().getStock()).isEqualTo(10);
        assertThat(variants.findById(b.getId()).orElseThrow().getStock()).isEqualTo(8);

        // Cancelación/reembolso → NO reintegra (nunca se descontó).
        stockService.restoreForOrder(order);
        em.flush();
        em.clear();
        assertThat(variants.findById(a.getId()).orElseThrow().getStock()).isEqualTo(10);
        assertThat(variants.findById(b.getId()).orElseThrow().getStock()).isEqualTo(8);
    }

    @Test
    void stockService_dropshipping_oversellQuantityDoesNotDepleteStock() {
        StockService stockService = new StockService();
        ProductVariantEntity v = variants.save(variantWithStock("v-oversell", 2));
        em.flush();

        // Pedir 5 con stock 2: en dropshipping el pedido se sirve igual y el stock mostrado no cambia.
        Order order = Order.builder().orderNumber("ORD-IT-2").items(List.of(
                OrderItem.builder().variantId(v.getId()).quantity(5).build())).build();

        stockService.deductForOrder(order);
        em.flush();
        em.clear();
        assertThat(variants.findById(v.getId()).orElseThrow().getStock()).isEqualTo(2);
    }

    private ProductVariantEntity variantWithStock(String tag, int stock) {
        return ProductVariantEntity.builder()
                .product(product)
                .title("variant-" + tag)
                .stock(stock)
                .active(true)
                .build();
    }

    private ProductVariantEntity variant(String tag, String imageSourceUrl, String imageCdnUrl, Instant failedAt) {
        return ProductVariantEntity.builder()
                .product(product)
                .title("variant-" + tag)
                .stock(0)
                .active(true)
                .imageSourceUrl(imageSourceUrl)
                .imageCdnUrl(imageCdnUrl)
                .imageMirrorFailedAt(failedAt)
                .build();
    }

    private ProductEntity newProduct(String tag) {
        return ProductEntity.builder()
                .slug("p-" + tag + "-" + UUID.randomUUID())
                .externalId("ext-" + tag + "-" + UUID.randomUUID())
                .source("test")
                .titleZh("测试产品")
                .moq(1)
                .reviewCount(0)
                .monthlySales(0)
                .status(ProductStatus.ACTIVE)
                .build();
    }
}
