package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestImage;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestSupplierRequest;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontMapper;
import com.nexaplatform.dropshipping.api.mapper.ProductBulkExportMapper;
import com.nexaplatform.dropshipping.application.service.CustomsProfileService;
import com.nexaplatform.dropshipping.application.usecase.impl.CatalogUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ProductMapper;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ImageMirrorService;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.Category1688MappingRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryAttributeSchemaRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductImageRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ObjectStorageService;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductAttributeRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductPriceTierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductReviewJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductSpecificationRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.VariantValueRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.search.CategoryIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.ProductIndexer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Mockito unit test for {@link CatalogUseCaseImpl}, mirroring the deleted
 * {@code CatalogServiceTest}. Mocks the domain port and the legacy collaborators
 * used by the ingest/read flows.
 */
@ExtendWith(MockitoExtension.class)
class CatalogUseCaseImplTest {

    @Mock
    com.nexaplatform.dropshipping.domain.repository.ProductRepository productRepository;
    @Mock
    SupplierRepository supplierRepository;
    @Mock
    CategoryRepository categoryRepository;
    @Mock
    CustomsProfileService customsProfileService;
    @Mock
    ProductPriceTierRepository priceTierRepository;
    @Mock
    ProductImageRepository imageRepository;
    @Mock
    ObjectStorageService objectStorage;
    @Mock
    ProductRepository productJpaRepository;
    @Mock
    ProductMapper productMapper;
    @Mock
    CatalogStorefrontMapper catalogStorefrontMapper;
    @Mock
    KafkaTemplate<String, Object> kafkaTemplate;
    @Mock
    ProductVariantRepository variantRepository;
    @Mock
    ProductIndexer productIndexer;
    @Mock
    CategoryIndexer categoryIndexer;
    @Mock
    ProductAttributeRepository productAttributeRepository;
    @Mock
    ProductSpecificationRepository productSpecificationRepository;
    @Mock
    VariantValueRepository variantValueRepository;
    @Mock
    JdbcTemplate jdbcTemplate;
    @Mock
    ProductBulkExportMapper bulkExportMapper;
    @Mock
    ImageMirrorService imageMirrorService;
    @Mock
    Category1688MappingRepository category1688MappingRepository;
    @Mock
    CategoryAttributeSchemaRepository categoryAttributeSchemaRepository;
    @Mock
    ProductReviewJpaRepositoryAdapter productReviewJpaRepositoryAdapter;

    // Con @InjectMocks los colaboradores se pasan por el constructor por tipo: añadir uno nuevo al
    // caso de uso ya no obliga a retocar esta lista de argumentos.
    @InjectMocks
    CatalogUseCaseImpl useCase;

    @Test
    void upsertSupplier_creates_when_missing() {
        when(supplierRepository.findBySourceAndExternalId("1688", "S1")).thenReturn(Optional.empty());
        when(supplierRepository.save(any(SupplierEntity.class))).thenAnswer(inv -> {
            SupplierEntity s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        SupplierEntity s = useCase.upsertSupplier(new IngestSupplierRequest("1688", "S1", "Acme", "艾克米", "CN", "Yiwu",
                new BigDecimal("4.7"), 5, true, true, null));

        assertThat(s.getId()).isNotNull();
        assertThat(s.getName()).isEqualTo("Acme");
        assertThat(s.isVerified()).isTrue();
    }

    @Test
    void upsertProduct_idempotent_with_same_external_id() {
        ProductEntity existing = ProductEntity.builder().source("1688").externalId("OFFER-1")
                .status(ProductStatus.DRAFT).titleZh("Old title").slug("old-slug-offer-1").moq(1).build();
        existing.setId(UUID.randomUUID());
        when(productJpaRepository.findBySourceAndExternalId("1688", "OFFER-1")).thenReturn(Optional.of(existing));
        when(productJpaRepository.save(any(ProductEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        IngestProductRequest req = new IngestProductRequest("1688", "OFFER-1", "Nuevo título", null, null, null, 2,
                new BigDecimal("19.90"), "CNY", 500, 1234, null, new BigDecimal("4.8"), 30,
                "https://detail.1688.com/offer/OFFER-1.html", null, null,
                List.of(new IngestImage("https://cbu01.alicdn.com/img.jpg", 0, "GALLERY")), null, null, null);

        ProductEntity saved = useCase.upsertProduct(req);
        assertThat(saved.getTitleZh()).isEqualTo("Nuevo título");
        assertThat(saved.getMoq()).isEqualTo(2);
        assertThat(saved.getImages()).hasSize(1);
        assertThat(saved.getImages().get(0).getSourceUrl()).contains("cbu01.alicdn");
        // slug preservado (no se regenera si ya existe)
        assertThat(saved.getSlug()).isEqualTo("old-slug-offer-1");
    }

    @Test
    void upsertProduct_cjkTitle_doesNotProduceSlugStartingWithDash() {
        // Slugify descarta lo que no sea ASCII: con un título íntegramente en chino devolvía "" y el slug
        // quedaba en "-<externalId>", una URL sin ninguna palabra. Debe caer en el prefijo neutro.
        when(productJpaRepository.findBySourceAndExternalId("1688", "OFFER-CJK")).thenReturn(Optional.empty());
        when(productJpaRepository.save(any(ProductEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        IngestProductRequest req = new IngestProductRequest("1688", "OFFER-CJK", "真皮复古经典德训鞋女款", null, null,
                null, 1, new BigDecimal("42.90"), "CNY", 500, 100, null, new BigDecimal("4.8"), 30,
                "https://detail.1688.com/offer/OFFER-CJK.html", null, null, List.of(), null, null, null);

        ProductEntity saved = useCase.upsertProduct(req);

        assertThat(saved.getSlug()).doesNotStartWith("-");
        assertThat(saved.getSlug()).isEqualTo("product-offer-cjk");
    }

    @Test
    void upsertProduct_latinTitle_keepsReadableSlug() {
        when(productJpaRepository.findBySourceAndExternalId("1688", "OFFER-ES")).thenReturn(Optional.empty());
        when(productJpaRepository.save(any(ProductEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        IngestProductRequest req = new IngestProductRequest("1688", "OFFER-ES", "Bailarinas planas de mujer", null,
                null, null, 1, new BigDecimal("19.90"), "CNY", 500, 100, null, new BigDecimal("4.8"), 30,
                "https://detail.1688.com/offer/OFFER-ES.html", null, null, List.of(), null, null, null);

        ProductEntity saved = useCase.upsertProduct(req);

        assertThat(saved.getSlug()).isEqualTo("bailarinas-planas-de-mujer-offer-es");
    }

    @Test
    void listProductImages_delegatesToRepositoryAndMapper() {
        UUID id = UUID.randomUUID();
        when(imageRepository.findByProductIdOrderByPositionAsc(id)).thenReturn(List.of());
        when(catalogStorefrontMapper.toImageDtos(List.of())).thenReturn(List.of());

        assertThat(useCase.listProductImages(id)).isEmpty();
    }

    @Test
    void listProductPriceTiers_delegatesToRepositoryAndMapper() {
        UUID id = UUID.randomUUID();
        when(priceTierRepository.findByProductIdOrderByMinQtyAsc(id)).thenReturn(List.of());
        when(catalogStorefrontMapper.toPriceTierDtos(List.of())).thenReturn(List.of());

        assertThat(useCase.listProductPriceTiers(id)).isEmpty();
    }

    @Test
    void computeTrendScore_within_unit_range() {
        ProductEntity p = ProductEntity.builder().monthlySales(500).rating(new BigDecimal("4.5"))
                .repurchaseRate(new BigDecimal("30")).reviewCount(100).build();
        BigDecimal score = useCase.computeTrendScore(p);
        assertThat(score).isGreaterThan(BigDecimal.ZERO).isLessThanOrEqualTo(BigDecimal.ONE);
    }
}
