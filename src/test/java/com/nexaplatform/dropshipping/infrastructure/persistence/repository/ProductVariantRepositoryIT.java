package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.config.PersistenceITBase;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
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
