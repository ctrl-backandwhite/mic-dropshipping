package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.mapper.ProductUpdateMapper;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.domain.model.Product;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El mapper de actualización parcial de producto copia los campos editables (contenido, precio,
 * inventario...) y preserva identidad, claves canónicas de origen (source/externalId/slug) y la
 * auditoría. Con {@code NullValuePropertyMappingStrategy.IGNORE} un null en el source no pisa el target.
 */
class ProductUpdateMapperTest {

    private final ProductUpdateMapper mapper = Mappers.getMapper(ProductUpdateMapper.class);

    @Test
    void updateFromModel_copiesEditableFieldsAndPreservesIdentityAndAudit() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2020-01-01T00:00:00Z");
        Product target = Product.builder()
                .id(id).source("OTRO").externalId("EXT-1").slug("the-slug")
                .titleZh("旧标题").brand("OldBrand").basePrice(new BigDecimal("10.00"))
                .inventoryCount(5).status(ProductStatus.ACTIVE)
                .certifications(new ArrayList<>(List.of("CE")))
                .createdAt(createdAt).createdBy("creator").updatedBy("editor1")
                .build();

        Product source = Product.builder()
                .id(UUID.randomUUID()).source("EVIL").externalId("EXT-EVIL").slug("evil-slug")
                .titleZh("新标题").brand("NewBrand").basePrice(new BigDecimal("99.99"))
                .inventoryCount(42).status(ProductStatus.DRAFT)
                .certifications(new ArrayList<>(List.of("FCC", "RoHS")))
                .createdAt(Instant.parse("2099-01-01T00:00:00Z")).createdBy("attacker").updatedBy("editor2")
                .build();

        mapper.updateFromModel(source, target);

        // Editables: se actualizan
        assertThat(target.getTitleZh()).isEqualTo("新标题");
        assertThat(target.getBrand()).isEqualTo("NewBrand");
        assertThat(target.getBasePrice()).isEqualByComparingTo("99.99");
        assertThat(target.getInventoryCount()).isEqualTo(42);
        assertThat(target.getStatus()).isEqualTo(ProductStatus.DRAFT);
        assertThat(target.getCertifications()).contains("FCC", "RoHS");

        // Identidad / claves canónicas de origen / auditoría: se preservan
        assertThat(target.getId()).isEqualTo(id);
        assertThat(target.getSource()).isEqualTo("OTRO");
        assertThat(target.getExternalId()).isEqualTo("EXT-1");
        assertThat(target.getSlug()).isEqualTo("the-slug");
        assertThat(target.getCreatedAt()).isEqualTo(createdAt);
        assertThat(target.getCreatedBy()).isEqualTo("creator");
        assertThat(target.getUpdatedBy()).isEqualTo("editor1");
    }

    @Test
    void updateFromModel_ignoresNullSourceFields() {
        Product target = Product.builder().titleZh("Keep").brand("KeepBrand").build();
        Product source = Product.builder().titleZh(null).brand("Changed").build();

        mapper.updateFromModel(source, target);

        assertThat(target.getTitleZh()).isEqualTo("Keep"); // null source no pisa
        assertThat(target.getBrand()).isEqualTo("Changed");
    }
}
