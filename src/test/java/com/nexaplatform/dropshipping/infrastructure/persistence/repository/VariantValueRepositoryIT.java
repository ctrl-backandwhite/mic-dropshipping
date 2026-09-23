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
        option = em.persistAndFlush(
                VariantOptionEntity.builder().product(product).nameZh("颜色").name("Color").position(0).build());
    }

    @Test
    void findNeedingImageMirror_selectsRowsWithSourceAndNoOurCdnAndNotFailed() {
        VariantValueEntity needsNullCdn = values.save(value("rojo", "https://src/red.jpg", null, null));
        VariantValueEntity needsOtherCdn = values
                .save(value("azul", "https://src/blue.jpg", "https://foreign.com/blue.jpg", null));
        values.save(value("verde", "https://src/green.jpg", "https://cdn.nexa.local/green.jpg", null));
        values.save(value("negro", "https://src/black.jpg", null, Instant.parse("2026-01-01T00:00:00Z")));
        values.save(value("sinimg", null, null, null));
        values.save(value("vacio", "", null, null));

        List<VariantValueEntity> result = values.findNeedingImageMirror(PUBLIC_PREFIX, PageRequest.of(0, 50));

        assertThat(result).extracting(VariantValueEntity::getId).containsExactlyInAnyOrder(needsNullCdn.getId(),
                needsOtherCdn.getId());
    }

    @Test
    void findNeedingImageMirror_honoursPageableLimit() {
        values.save(value("a", "https://src/a.jpg", null, null));
        values.save(value("b", "https://src/b.jpg", null, null));
        values.save(value("c", "https://src/c.jpg", null, null));

        List<VariantValueEntity> result = values.findNeedingImageMirror(PUBLIC_PREFIX, PageRequest.of(0, 2));

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
                .extracting(VariantValueEntity::getId).doesNotContain(id);
    }

    private VariantValueEntity value(String valueZh, String imageSourceUrl, String imageCdnUrl, Instant failedAt) {
        return VariantValueEntity.builder().option(option).valueZh(valueZh).position(0).imageSourceUrl(imageSourceUrl)
                .imageCdnUrl(imageCdnUrl).imageMirrorFailedAt(failedAt).build();
    }

    private ProductEntity newProduct(String tag) {
        return ProductEntity.builder().slug("p-" + tag + "-" + UUID.randomUUID())
                .externalId("ext-" + tag + "-" + UUID.randomUUID()).source("test").titleZh("测试产品").moq(1).reviewCount(0)
                .monthlySales(0).status(ProductStatus.ACTIVE).build();
    }

    /**
     * El reintento devuelve a la cola lo que falló hace rato, y solo eso.
     *
     * <p>Va en una prueba de integración y no en una unitaria porque la consulta es SQL NATIVO con un
     * LIMIT dentro del subselect: lo que hay que comprobar es que PostgreSQL la acepta y que acota de
     * verdad, no que se llame al repositorio.
     *
     * <p>Lo que impide: el 18-sep-2026, al cargar 9.425 productos, el 96% de las muestras de color quedó
     * marcado como fallido y NADIE limpiaba esa marca —el barrido descarta lo marcado y el reintento solo
     * miraba las imágenes de producto—. Resultado: muestras apuntando para siempre al proveedor, que
     * responde 403 al enlazarlas desde otra web, y en la ficha salían rotas.
     */
    @Test
    void requeueFailed_limpiaSoloLoQueFalloHaceYaUnRato() {
        Instant hace1h = Instant.parse("2026-09-18T11:00:00Z");
        Instant haceNada = Instant.parse("2026-09-18T11:59:00Z");
        Instant corte = Instant.parse("2026-09-18T11:55:00Z");
        VariantValueEntity vieja = values.save(value("roja", "https://src/r.jpg", null, hace1h));
        VariantValueEntity reciente = values.save(value("azul", "https://src/a.jpg", null, haceNada));
        VariantValueEntity sana = values.save(value("verde", "https://src/v.jpg", null, null));

        int tocadas = values.requeueFailed(corte, 100);

        assertThat(tocadas).isEqualTo(1);
        em.clear();
        assertThat(values.findById(vieja.getId()).orElseThrow().getImageMirrorFailedAt()).isNull();
        assertThat(values.findById(reciente.getId()).orElseThrow().getImageMirrorFailedAt())
                .as("lo que acaba de fallar espera su turno: reintentarlo ya repetiría la avalancha")
                .isEqualTo(haceNada);
        assertThat(values.findById(sana.getId()).orElseThrow().getImageMirrorFailedAt()).isNull();
    }

    /** El tope existe para no reencolar decenas de miles de golpe, que es lo que tumbó el espejado. */
    @Test
    void requeueFailed_respetaElTope() {
        Instant hace1h = Instant.parse("2026-09-18T11:00:00Z");
        for (int i = 0; i < 5; i++) {
            values.save(value("c" + i, "https://src/" + i + ".jpg", null, hace1h));
        }

        assertThat(values.requeueFailed(Instant.parse("2026-09-18T11:55:00Z"), 2)).isEqualTo(2);
    }

    /** Y tras limpiarla, el barrido vuelve a verlas: es la mitad que faltaba del arreglo. */
    @Test
    void trasElReintentoElBarridoVuelveAVerlas() {
        Instant hace1h = Instant.parse("2026-09-18T11:00:00Z");
        VariantValueEntity fallida = values.save(value("roja", "https://src/r.jpg", null, hace1h));
        assertThat(values.findNeedingImageMirror(PUBLIC_PREFIX, PageRequest.of(0, 50))).isEmpty();

        values.requeueFailed(Instant.parse("2026-09-18T11:55:00Z"), 100);
        em.clear();

        assertThat(values.findNeedingImageMirror(PUBLIC_PREFIX, PageRequest.of(0, 50)))
                .extracting(VariantValueEntity::getId).containsExactly(fallida.getId());
    }
}
