package com.nexaplatform.dropshipping.infrastructure.seed;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Cubre la reconstrucción del slug en el importador masivo. upsertProduct lo genera a partir del
 * título ZH y Slugify descarta los caracteres CJK, así que con un título chino real el slug quedaba
 * en "-&lt;externalId&gt;". Aquí sí está disponible el título ES, que es el que debe mandar.
 */
@ExtendWith(MockitoExtension.class)
class CatalogFillWriterTest {

    @Mock
    private CatalogUseCase catalogService;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private CatalogFillWriter writer;

    private static IngestProductRequest req(String externalId, String titleZh) {
        return new IngestProductRequest("1688", externalId, titleZh, null, null, null, 1,
                new BigDecimal("19.90"), "CNY", 500, 100, null, new BigDecimal("4.8"), 30,
                "https://detail.1688.com/offer/" + externalId + ".html", null, null, List.of(), null, null, null);
    }

    private ProductEntity stubProduct(String externalId, String slug) {
        ProductEntity p = ProductEntity.builder().source("1688").externalId(externalId).slug(slug)
                .moq(1).monthlySales(100).reviewCount(30).build();
        p.setId(UUID.randomUUID());
        when(catalogService.upsertProduct(any(IngestProductRequest.class))).thenReturn(p);
        when(productRepository.findById(p.getId())).thenReturn(Optional.of(p));
        when(productRepository.save(any(ProductEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        return p;
    }

    @Test
    void write_rebuildsSlugFromSpanishTitle_whenChineseTitleLeftItDegraded() {
        ProductEntity p = stubProduct("954159512484", "-954159512484");

        writer.write(req("954159512484", "厚底女鞋增高休闲板鞋"),
                "Zapatillas retro de mujer con plataforma", "Retro platform sneakers", "Ténis retrô",
                "厚底女鞋增高休闲板鞋", "desc es", "desc en", "desc pt", "desc zh", null);

        assertThat(p.getSlug()).isEqualTo("zapatillas-retro-de-mujer-con-plataforma-954159512484");
    }

    @Test
    void write_keepsExistingReadableSlug_soReimportDoesNotChangeTheUrl() {
        ProductEntity p = stubProduct("954159512484", "slug-bueno-de-antes-954159512484");

        writer.write(req("954159512484", "厚底女鞋增高休闲板鞋"),
                "Otro título distinto", "Another title", "Outro título",
                "厚底女鞋增高休闲板鞋", "desc es", "desc en", "desc pt", "desc zh", null);

        assertThat(p.getSlug()).isEqualTo("slug-bueno-de-antes-954159512484");
    }

    @Test
    void write_leavesSlugAlone_whenSpanishTitleHasNothingToSlugify() {
        ProductEntity p = stubProduct("954159512484", "-954159512484");

        writer.write(req("954159512484", "厚底女鞋"), "厚底女鞋", "厚底女鞋", "厚底女鞋",
                "厚底女鞋", "desc", "desc", "desc", "desc", null);

        assertThat(p.getSlug()).isEqualTo("-954159512484");
    }
}
