package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.config.PersistenceITBase;
import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT de persistencia de {@link ProductImageRepository}: ejercita los finders derivados y, sobre todo,
 * el JPQL {@code @Modifying} (markMirrored / markStatus / requeueNotMirrored) contra Postgres real.
 * Cada imagen necesita un {@link ProductEntity} padre (FK product_id NOT NULL): se persiste primero.
 */
class ProductImageRepositoryIT extends PersistenceITBase {

    private static final String PUBLIC_PREFIX = "https://cdn.nexa.local/%";

    @Autowired
    ProductImageRepository images;

    @Autowired
    ProductJpaRepositoryAdapter products;

    @Autowired
    TestEntityManager em;

    private ProductEntity product;

    @BeforeEach
    void setUp() {
        product = products.save(newProduct("img"));
    }

    @Test
    void findByProductIdOrderByPositionAsc_returnsImagesInPositionOrder() {
        images.save(image(2, "https://src/b.jpg", MirrorStatus.PENDING, null));
        images.save(image(0, "https://src/a.jpg", MirrorStatus.PENDING, null));
        images.save(image(1, "https://src/c.jpg", MirrorStatus.PENDING, null));

        List<ProductImageEntity> result = images.findByProductIdOrderByPositionAsc(product.getId());

        assertThat(result).extracting(ProductImageEntity::getPosition).containsExactly(0, 1, 2);
    }

    @Test
    void findTop100ByMirrorStatus_andCount_filterByStatus() {
        images.save(image(0, "https://src/p1.jpg", MirrorStatus.PENDING, null));
        images.save(image(1, "https://src/p2.jpg", MirrorStatus.PENDING, null));
        images.save(image(2, "https://src/m.jpg", MirrorStatus.MIRRORED, "https://cdn.nexa.local/m.jpg"));
        images.save(image(3, "https://src/f.jpg", MirrorStatus.FAILED, null));

        assertThat(images.findTop100ByMirrorStatusOrderByCreatedAtAsc(MirrorStatus.PENDING)).hasSize(2);
        assertThat(images.countByMirrorStatus(MirrorStatus.PENDING)).isEqualTo(2);
        assertThat(images.countByMirrorStatus(MirrorStatus.MIRRORED)).isEqualTo(1);
        assertThat(images.countByMirrorStatus(MirrorStatus.FAILED)).isEqualTo(1);
    }

    @Test
    void findByMirrorStatusAndCdnUrlStartingWith_matchesPrefix() {
        images.save(image(0, "https://src/ours.jpg", MirrorStatus.MIRRORED, "https://cdn.nexa.local/ours.jpg"));
        images.save(image(1, "https://src/other.jpg", MirrorStatus.MIRRORED, "https://other-cdn.com/x.jpg"));
        images.save(image(2, "https://src/pending.jpg", MirrorStatus.PENDING, null));

        List<ProductImageEntity> result =
                images.findByMirrorStatusAndCdnUrlStartingWith(MirrorStatus.MIRRORED, "https://cdn.nexa.local/");

        assertThat(result).extracting(ProductImageEntity::getCdnUrl)
                .containsExactly("https://cdn.nexa.local/ours.jpg");
    }

    @Test
    void markMirrored_setsCdnUrlBytesHashStatusAndMirroredAt() {
        ProductImageEntity saved = images.save(image(0, "https://src/x.jpg", MirrorStatus.PENDING, null));
        UUID id = saved.getId();
        Instant at = Instant.parse("2026-01-15T10:00:00Z");

        images.markMirrored(id, "https://cdn.nexa.local/x.jpg", 4096L, "sha256:abc", MirrorStatus.MIRRORED, at);

        // El contexto de persistencia cachea la entidad: hay que limpiar y releer para ver el UPDATE en BD.
        em.flush();
        em.clear();
        ProductImageEntity reloaded = images.findById(id).orElseThrow();
        assertThat(reloaded.getCdnUrl()).isEqualTo("https://cdn.nexa.local/x.jpg");
        assertThat(reloaded.getBytes()).isEqualTo(4096L);
        assertThat(reloaded.getHash()).isEqualTo("sha256:abc");
        assertThat(reloaded.getMirrorStatus()).isEqualTo(MirrorStatus.MIRRORED);
        assertThat(reloaded.getMirroredAt()).isEqualTo(at);
    }

    @Test
    void markStatus_changesOnlyMirrorStatus() {
        ProductImageEntity saved = images.save(image(0, "https://src/y.jpg", MirrorStatus.PENDING, null));
        UUID id = saved.getId();

        images.markStatus(id, MirrorStatus.FAILED);

        em.flush();
        em.clear();
        ProductImageEntity reloaded = images.findById(id).orElseThrow();
        assertThat(reloaded.getMirrorStatus()).isEqualTo(MirrorStatus.FAILED);
        assertThat(reloaded.getCdnUrl()).isNull();
    }

    @Test
    void requeueNotMirrored_setsPendingForRowsNotPointingToOurStorage() {
        // Ya espejada en nuestro storage -> NO debe reencolarse.
        ProductImageEntity ours =
                images.save(image(0, "https://src/a.jpg", MirrorStatus.MIRRORED, "https://cdn.nexa.local/a.jpg"));
        // MIRRORED pero apuntando a otro storage -> SÍ se reencola (cdn NOT LIKE prefix).
        ProductImageEntity otherCdn =
                images.save(image(1, "https://src/b.jpg", MirrorStatus.MIRRORED, "https://foreign.com/b.jpg"));
        // FAILED sin cdn -> SÍ (mirrorStatus <> MIRRORED y cdn null).
        ProductImageEntity failed = images.save(image(2, "https://src/c.jpg", MirrorStatus.FAILED, null));

        int affected = images.requeueNotMirrored(PUBLIC_PREFIX);

        em.flush();
        em.clear();
        assertThat(affected).isEqualTo(2);
        assertThat(images.findById(ours.getId()).orElseThrow().getMirrorStatus()).isEqualTo(MirrorStatus.MIRRORED);
        assertThat(images.findById(otherCdn.getId()).orElseThrow().getMirrorStatus()).isEqualTo(MirrorStatus.PENDING);
        assertThat(images.findById(failed.getId()).orElseThrow().getMirrorStatus()).isEqualTo(MirrorStatus.PENDING);
    }

    private ProductImageEntity image(int position, String sourceUrl, MirrorStatus status, String cdnUrl) {
        return ProductImageEntity.builder()
                .product(product)
                .position(position)
                .sourceUrl(sourceUrl)
                .cdnUrl(cdnUrl)
                .mirrorStatus(status)
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
