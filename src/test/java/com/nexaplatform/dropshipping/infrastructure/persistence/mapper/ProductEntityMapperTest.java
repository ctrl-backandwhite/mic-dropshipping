package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.domain.model.Product;
import com.nexaplatform.dropshipping.domain.model.ProductImage;
import com.nexaplatform.dropshipping.domain.model.ProductTranslation;
import com.nexaplatform.dropshipping.domain.model.ProductVariant;
import com.nexaplatform.dropshipping.domain.model.VariantOption;
import com.nexaplatform.dropshipping.domain.model.VariantValue;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantOptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapper Model&lt;-&gt;Entity de {@link Product} (el más grande: ~11 sub-entidades anidadas). El mapper
 * es autocontenido (los métodos anidados toImageDomain/toVariantDomain/... viven en la misma
 * interfaz, sin {@code uses=...}), así que {@code Mappers.getMapper} basta y NO hay que inyectar
 * mappers anidados por reflexión. Cubre: round-trip de escalares (toEntity descarta auditoría,
 * relaciones gestionadas y las colecciones anidadas, que las resuelve el repositorio) y el mapeo
 * de las sub-entidades anidadas en toDomain (tamaños + campos representativos).
 */
class ProductEntityMapperTest {

    private final ProductEntityMapper mapper = Mappers.getMapper(ProductEntityMapper.class);

    @Test
    void roundTrip_preservesScalarsAndDropsAuditAndManagedCollections() {
        UUID id = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();

        Product model = Product.builder().id(id).slug("widget-pro").source("1688").externalId("EXT-1")
                .supplierId(supplierId).categoryId(categoryId).titleZh("小部件").shortDescriptionZh("短")
                .descriptionZh("描述").brand("ACME").moq(5).basePrice(new BigDecimal("12.3400")).currency("CNY")
                .weightGrams(250).packageWeightGrams(300).shipFrom("CN").freeShipping(true).selfPickup(false)
                .hasVideo(true).videoUrl("http://v/1.mp4").inventoryCount(99)
                .certifications(new ArrayList<>(List.of("CE", "RoHS"))).status(ProductStatus.ACTIVE)
                .rating(new BigDecimal("4.50")).reviewCount(120).monthlySales(33).repurchaseRate(new BigDecimal("0.20"))
                .trendScore(new BigDecimal("7.1234")).sourceUrl("http://src/p").ingestedAt(Instant.now())
                .lastSyncedAt(Instant.now()).supplierName("ACME Co").createdAt(Instant.now()).updatedAt(Instant.now())
                .createdBy("seed").updatedBy("editor").build();

        ProductEntity entity = mapper.toEntity(model);

        // toEntity IGNORA la auditoría (no la copia)
        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isNull();
        assertThat(entity.getCreatedBy()).isNull();
        assertThat(entity.getUpdatedBy()).isNull();
        // y las relaciones / colecciones gestionadas
        assertThat(entity.getSupplier()).isNull();
        assertThat(entity.getCategory()).isNull();
        assertThat(entity.getImages()).isEmpty();
        assertThat(entity.getVariants()).isEmpty();
        assertThat(entity.getVariantOptions()).isEmpty();
        assertThat(entity.getTranslations()).isEmpty();

        Product back = mapper.toDomain(entity);

        // Round-trip de escalares. Ignoramos: auditoría; supplierName/supplierId/categoryId
        // (sin SupplierEntity/CategoryEntity gestionado en este camino); las colecciones anidadas
        // y priceTiers (los resuelve el repositorio, toEntity los descarta).
        assertThat(back).usingRecursiveComparison()
                .ignoringFields("createdAt", "updatedAt", "createdBy", "updatedBy", "supplierName", "supplierId",
                        "categoryId", "images", "variants", "variantOptions", "translations", "priceTiers")
                .isEqualTo(model);
    }

    @Test
    void toDomain_mapsNestedSubEntities() {
        SupplierEntity supplier = new SupplierEntity();
        UUID supplierId = UUID.randomUUID();
        supplier.setId(supplierId);
        supplier.setName("ACME Co");

        ProductImageEntity image = ProductImageEntity.builder().position(0).role("cover")
                .sourceUrl("http://src/img.jpg").cdnUrl("http://cdn/img.jpg").width(800).height(600).bytes(12345L)
                .hash("abc").mirrorStatus(MirrorStatus.MIRRORED).mirroredAt(Instant.now()).build();
        image.setId(UUID.randomUUID());

        ProductVariantEntity variant = ProductVariantEntity.builder().externalId("V-1").sku("SKU-1").title("Rojo / L")
                .price(new BigDecimal("15.0000")).stock(7).weightGrams(260).barcode("BC-1")
                .imageSourceUrl("http://src/v.jpg").imageCdnUrl("http://cdn/v.jpg").options(Map.of("color", "rojo"))
                .active(true).build();
        variant.setId(UUID.randomUUID());

        VariantValueEntity value = VariantValueEntity.builder().valueZh("红").value("Rojo")
                .imageSourceUrl("http://src/red.jpg").imageCdnUrl("http://cdn/red.jpg").position(0).build();
        value.setId(UUID.randomUUID());

        VariantOptionEntity option = VariantOptionEntity.builder().nameZh("颜色").name("Color").position(0)
                .values(new ArrayList<>(List.of(value))).build();
        option.setId(UUID.randomUUID());

        ProductTranslationEntity translation = ProductTranslationEntity.builder().language("es").title("Widget")
                .shortDescription("corto").description("largo").metaTitle("MT").metaDescription("MD").provider("deepl")
                .build();
        translation.setId(UUID.randomUUID());

        ProductEntity entity = ProductEntity.builder().slug("widget-pro").source("1688").externalId("EXT-1")
                .supplier(supplier).titleZh("小部件").moq(1).status(ProductStatus.ACTIVE)
                .images(new ArrayList<>(List.of(image))).variants(new ArrayList<>(List.of(variant)))
                .variantOptions(new ArrayList<>(List.of(option))).translations(new ArrayList<>(List.of(translation)))
                .build();
        entity.setId(UUID.randomUUID());

        Product model = mapper.toDomain(entity);

        // Relaciones aplanadas desde supplier
        assertThat(model.getSupplierId()).isEqualTo(supplierId);
        assertThat(model.getSupplierName()).isEqualTo("ACME Co");

        // Imágenes
        assertThat(model.getImages()).hasSize(1);
        ProductImage img = model.getImages().get(0);
        assertThat(img.getRole()).isEqualTo("cover");
        assertThat(img.getMirrorStatus()).isEqualTo(MirrorStatus.MIRRORED);

        // Variantes
        assertThat(model.getVariants()).hasSize(1);
        ProductVariant v = model.getVariants().get(0);
        assertThat(v.getSku()).isEqualTo("SKU-1");
        assertThat(v.getPrice()).isEqualByComparingTo("15.0000");
        assertThat(v.getOptions()).containsEntry("color", "rojo");

        // Opciones de variante y sus valores anidados
        assertThat(model.getVariantOptions()).hasSize(1);
        VariantOption opt = model.getVariantOptions().get(0);
        assertThat(opt.getName()).isEqualTo("Color");
        assertThat(opt.getValues()).hasSize(1);
        VariantValue val = opt.getValues().get(0);
        assertThat(val.getValue()).isEqualTo("Rojo");
        assertThat(val.getValueZh()).isEqualTo("红");

        // Traducciones
        assertThat(model.getTranslations()).hasSize(1);
        ProductTranslation tr = model.getTranslations().get(0);
        assertThat(tr.getLanguage()).isEqualTo("es");
        assertThat(tr.getTitle()).isEqualTo("Widget");
    }
}
