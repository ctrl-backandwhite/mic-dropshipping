package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestImage;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestSupplierRequest;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontMapper;
import com.nexaplatform.dropshipping.application.usecase.impl.CatalogUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ProductMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductImageRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductPriceTierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    @Mock com.nexaplatform.dropshipping.domain.repository.ProductRepository productRepository;
    @Mock SupplierRepository supplierRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock ProductPriceTierRepository priceTierRepository;
    @Mock ProductImageRepository imageRepository;
    @Mock ProductRepository productJpaRepository;
    @Mock ProductMapper productMapper;
    @Mock CatalogStorefrontMapper catalogStorefrontMapper;
    @Mock KafkaTemplate<String, Object> kafkaTemplate;

    CatalogUseCaseImpl useCase;

    @BeforeEach
    void setup() {
        useCase = new CatalogUseCaseImpl(productRepository, supplierRepository, categoryRepository,
                priceTierRepository, imageRepository, productJpaRepository, productMapper,
                catalogStorefrontMapper, kafkaTemplate);
    }

    @Test
    void upsertSupplier_creates_when_missing() {
        when(supplierRepository.findBySourceAndExternalId("1688", "S1")).thenReturn(Optional.empty());
        when(supplierRepository.save(any(SupplierEntity.class))).thenAnswer(inv -> {
            SupplierEntity s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        SupplierEntity s = useCase.upsertSupplier(new IngestSupplierRequest(
                "1688", "S1", "Acme", "艾克米", "CN", "Yiwu",
                new BigDecimal("4.7"), 5, true, true, null));

        assertThat(s.getId()).isNotNull();
        assertThat(s.getName()).isEqualTo("Acme");
        assertThat(s.isVerified()).isTrue();
    }

    @Test
    void upsertProduct_idempotent_with_same_external_id() {
        ProductEntity existing = ProductEntity.builder()
                .source("1688").externalId("OFFER-1").status(ProductStatus.DRAFT)
                .titleZh("Old title").slug("old-slug-offer-1").moq(1).build();
        existing.setId(UUID.randomUUID());
        when(productJpaRepository.findBySourceAndExternalId("1688", "OFFER-1")).thenReturn(Optional.of(existing));
        when(productJpaRepository.save(any(ProductEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        IngestProductRequest req = new IngestProductRequest(
                "1688", "OFFER-1", "Nuevo título",
                null, null, null, 2, new BigDecimal("19.90"), "CNY",
                500, 1234, null, new BigDecimal("4.8"), 30,
                "https://detail.1688.com/offer/OFFER-1.html",
                null, null,
                List.of(new IngestImage("https://cbu01.alicdn.com/img.jpg", 0, "GALLERY")),
                null, null, null);

        ProductEntity saved = useCase.upsertProduct(req);
        assertThat(saved.getTitleZh()).isEqualTo("Nuevo título");
        assertThat(saved.getMoq()).isEqualTo(2);
        assertThat(saved.getImages()).hasSize(1);
        assertThat(saved.getImages().get(0).getSourceUrl()).contains("cbu01.alicdn");
        // slug preservado (no se regenera si ya existe)
        assertThat(saved.getSlug()).isEqualTo("old-slug-offer-1");
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
        ProductEntity p = ProductEntity.builder()
                .monthlySales(500)
                .rating(new BigDecimal("4.5"))
                .repurchaseRate(new BigDecimal("30"))
                .reviewCount(100)
                .build();
        BigDecimal score = useCase.computeTrendScore(p);
        assertThat(score).isGreaterThan(BigDecimal.ZERO).isLessThanOrEqualTo(BigDecimal.ONE);
    }
}
