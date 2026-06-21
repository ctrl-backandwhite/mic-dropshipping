package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.config.PersistenceITBase;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantOptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueEntity;
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
 * IT de persistencia de {@link VariantValueRepository}: ejercita {@code findNeedingImageMirror} y los
 * {@code @Modifying} (markImageCdn / markImageFailed) contra Postgres real. Cada valor cuelga de un
 * {@link VariantOptionEntity} (FK option_id NOT NULL) que a su vez cuelga de un {@link ProductEntity};
 * como no hay adapter de option, se persisten ambos vía {@link TestEntityManager}.
 */
class VariantValueRepositoryIT extends PersistenceITBase {

    private static final String PUBLIC_PREFIX = "https://cdn.nexa.local/%";

    @Autowired
    VariantValueRepository values;

    @Autowired
    ProductJpaRepositoryAdapter products;

    @Autowired
    TestEntityManager em;

    private VariantOptionEntity option;

    @BeforeEach
    void setUp() {
        ProductEntity product = products.save(newProduct("val"));
        option = em.persistAndFlush(VariantOptionEntity.builder()
                .product(product)
                .nameZh("颜色")
                .name("Color")
                .position(0)
                .build());
    }

    @Test
    void findNeedingImageMirror_selectsRowsWithSourceAndNoOurCdnAndNotFailed() {
        VariantValueEntity needsNullCdn = values.save(value("rojo", "https://src/red.jpg", null, null));
        VariantValueEntity needsOtherCdn =
                values.save(value("azul", "https://src/blue.jpg", "https://foreign.com/blue.jpg", null));
        values.save(value("verde", "https://src/green.jpg", "https://cdn.nexa.local/green.jpg", null));
        values.save(value("negro", "https://src/black.jpg", null, Instant.parse("2026-01-01T00:00:00Z")));
        values.save(value("sinimg", null, null, null));
        values.save(value("vacio", "", null, null));

        List<VariantValueEntity> result =
                values.findNeedingImageMirror(PUBLIC_PREFIX, PageRequest.of(0, 50));

        assertThat(result).extracting(VariantValueEntity::getId)
                .containsExactlyInAnyOrder(needsNullCdn.getId(), needsOtherCdn.getId());
    }

    @Test
    void findNeedingImageMirror_honoursPageableLimit() {
        values.save(value("a", "https://src/a.jpg", null, null));
        values.save(value("b", "https://src/b.jpg", null, null));
        values.save(value("c", "https://src/c.jpg", null, null));

        List<VariantValueEntity> result =
                values.findNeedingImageMirror(PUBLIC_PREFIX, PageRequest.of(0, 2));

        assertThat(result).hasSize(2);
    }

    @Test
    void markImageCdn_setsImageCdnUrl() {
        VariantValueEntity saved = values.save(value("c", "https://src/c.jpg", null, null));
        UUID id = saved.getId();

        values.markImageCdn(id, "https://cdn.nexa.local/c.jpg");

        em.flush();
        em.clear();
        VariantValueEntity reloaded = values.findById(id).orElseThrow();
        assertThat(reloaded.getImageCdnUrl()).isEqualTo("https://cdn.nexa.local/c.jpg");
        assertThat(reloaded.getImageMirrorFailedAt()).isNull();
    }

    @Test
    void markImageFailed_setsImageMirrorFailedAtAndDropsFromPending() {
        VariantValueEntity saved = values.save(value("f", "https://src/dead.jpg", null, null));
        UUID id = saved.getId();
        Instant at = Instant.parse("2026-03-10T12:00:00Z");

        values.markImageFailed(id, at);

        em.flush();
        em.clear();
        VariantValueEntity reloaded = values.findById(id).orElseThrow();
        assertThat(reloaded.getImageMirrorFailedAt()).isEqualTo(at);
        assertThat(values.findNeedingImageMirror(PUBLIC_PREFIX, PageRequest.of(0, 50)))
                .extracting(VariantValueEntity::getId)
                .doesNotContain(id);
    }

    private VariantValueEntity value(String valueZh, String imageSourceUrl, String imageCdnUrl, Instant failedAt) {
        return VariantValueEntity.builder()
                .option(option)
                .valueZh(valueZh)
                .position(0)
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
