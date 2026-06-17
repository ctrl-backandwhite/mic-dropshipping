package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.Product;
import com.nexaplatform.dropshipping.domain.model.ProductImage;
import com.nexaplatform.dropshipping.domain.model.ProductPriceTier;
import com.nexaplatform.dropshipping.domain.model.ProductTranslation;
import com.nexaplatform.dropshipping.domain.model.ProductVariant;
import com.nexaplatform.dropshipping.domain.model.VariantOption;
import com.nexaplatform.dropshipping.domain.model.VariantValue;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantOptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link Product} domain model and the JPA
 * entity. Builder disabled so MapStruct uses setters and can reach the id/audit
 * fields inherited from {@code BaseEntity}/{@code AuditableEntity}. The
 * {@code supplier}/{@code category} relations and the price tiers (a separate
 * table) are resolved/persisted by the repository adapter, so the domain side only
 * carries the flattened {@code supplierId}/{@code categoryId}. The nested image,
 * variant, variant-option and translation collections are flattened both ways;
 * the read-only display fields ({@code supplierName}) have no entity counterpart.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface ProductEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "slug", source = "slug")
    @Mapping(target = "source", source = "source")
    @Mapping(target = "externalId", source = "externalId")
    @Mapping(target = "supplierId", expression = "java(entity.getSupplier() != null ? entity.getSupplier().getId() : null)")
    @Mapping(target = "categoryId", expression = "java(entity.getCategory() != null ? entity.getCategory().getId() : null)")
    @Mapping(target = "supplierName", expression = "java(entity.getSupplier() != null ? entity.getSupplier().getName() : null)")
    @Mapping(target = "titleZh", source = "titleZh")
    @Mapping(target = "shortDescriptionZh", source = "shortDescriptionZh")
    @Mapping(target = "descriptionZh", source = "descriptionZh")
    @Mapping(target = "brand", source = "brand")
    @Mapping(target = "moq", source = "moq")
    @Mapping(target = "basePrice", source = "basePrice")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "weightGrams", source = "weightGrams")
    @Mapping(target = "packageWeightGrams", source = "packageWeightGrams")
    @Mapping(target = "shipFrom", source = "shipFrom")
    @Mapping(target = "freeShipping", source = "freeShipping")
    @Mapping(target = "selfPickup", source = "selfPickup")
    @Mapping(target = "hasVideo", source = "hasVideo")
    @Mapping(target = "videoUrl", source = "videoUrl")
    @Mapping(target = "inventoryCount", source = "inventoryCount")
    @Mapping(target = "certifications", source = "certifications")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "rating", source = "rating")
    @Mapping(target = "reviewCount", source = "reviewCount")
    @Mapping(target = "monthlySales", source = "monthlySales")
    @Mapping(target = "repurchaseRate", source = "repurchaseRate")
    @Mapping(target = "trendScore", source = "trendScore")
    @Mapping(target = "sourceUrl", source = "sourceUrl")
    @Mapping(target = "ingestedAt", source = "ingestedAt")
    @Mapping(target = "lastSyncedAt", source = "lastSyncedAt")
    @Mapping(target = "images", source = "images")
    @Mapping(target = "variants", source = "variants")
    @Mapping(target = "variantOptions", source = "variantOptions")
    @Mapping(target = "translations", source = "translations")
    @Mapping(target = "priceTiers", ignore = true)
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    Product toDomain(ProductEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "slug", source = "slug")
    @Mapping(target = "source", source = "source")
    @Mapping(target = "externalId", source = "externalId")
    @Mapping(target = "supplier", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "titleZh", source = "titleZh")
    @Mapping(target = "shortDescriptionZh", source = "shortDescriptionZh")
    @Mapping(target = "descriptionZh", source = "descriptionZh")
    @Mapping(target = "brand", source = "brand")
    @Mapping(target = "moq", source = "moq")
    @Mapping(target = "basePrice", source = "basePrice")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "weightGrams", source = "weightGrams")
    @Mapping(target = "lengthMm", ignore = true)
    @Mapping(target = "widthMm", ignore = true)
    @Mapping(target = "heightMm", ignore = true)
    @Mapping(target = "hsCode", ignore = true)
    @Mapping(target = "packageWeightGrams", source = "packageWeightGrams")
    @Mapping(target = "leadTimeDays", ignore = true)
    @Mapping(target = "warrantyMonths", ignore = true)
    @Mapping(target = "countryOfOrigin", ignore = true)
    @Mapping(target = "returnPolicyDays", ignore = true)
    @Mapping(target = "certifications", source = "certifications")
    @Mapping(target = "shipFrom", source = "shipFrom")
    @Mapping(target = "freeShipping", source = "freeShipping")
    @Mapping(target = "selfPickup", source = "selfPickup")
    @Mapping(target = "hasVideo", source = "hasVideo")
    @Mapping(target = "videoUrl", source = "videoUrl")
    @Mapping(target = "inventoryCount", source = "inventoryCount")
    @Mapping(target = "podEnabled", ignore = true)
    @Mapping(target = "brandSelected", ignore = true)
    @Mapping(target = "readyToShip", ignore = true)
    @Mapping(target = "arModelUrl", ignore = true)
    @Mapping(target = "reviewsSummary", ignore = true)
    @Mapping(target = "reviewsSentiment", ignore = true)
    @Mapping(target = "status", source = "status")
    @Mapping(target = "rating", source = "rating")
    @Mapping(target = "reviewCount", source = "reviewCount")
    @Mapping(target = "monthlySales", source = "monthlySales")
    @Mapping(target = "repurchaseRate", source = "repurchaseRate")
    @Mapping(target = "trendScore", source = "trendScore")
    @Mapping(target = "sourceUrl", source = "sourceUrl")
    @Mapping(target = "ingestedAt", source = "ingestedAt")
    @Mapping(target = "lastSyncedAt", source = "lastSyncedAt")
    @Mapping(target = "images", ignore = true)
    @Mapping(target = "variants", ignore = true)
    @Mapping(target = "variantOptions", ignore = true)
    @Mapping(target = "translations", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    ProductEntity toEntity(Product model);

    /**
     * Aplica los campos escalares simples del modelo sobre una entidad gestionada
     * (ruta de actualización). El {@code id}, la auditoría y TODAS las relaciones /
     * colecciones gestionadas (supplier, category, images, variants, translations,
     * variantOptions y los priceTiers de su propia tabla) las resuelve el repositorio,
     * por eso se ignoran aquí. MapStruct auto-mapea el resto por nombre; los campos
     * que solo existen en la entidad (sin contraparte en el modelo) quedan intactos.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "supplier", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "images", ignore = true)
    @Mapping(target = "variants", ignore = true)
    @Mapping(target = "translations", ignore = true)
    @Mapping(target = "variantOptions", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    void updateEntity(@MappingTarget ProductEntity entity, Product model);

    List<Product> toDomainList(List<ProductEntity> entities);

    /* ------------------ nested sub-entity mapping ------------------ */

    @Mapping(target = "id", source = "id")
    ProductImage toImageDomain(ProductImageEntity entity);

    List<ProductImage> toImageDomainList(List<ProductImageEntity> entities);

    @Mapping(target = "id", source = "id")
    ProductVariant toVariantDomain(ProductVariantEntity entity);

    List<ProductVariant> toVariantDomainList(List<ProductVariantEntity> entities);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "values", source = "values")
    VariantOption toOptionDomain(VariantOptionEntity entity);

    List<VariantOption> toOptionDomainList(List<VariantOptionEntity> entities);

    @Mapping(target = "id", source = "id")
    VariantValue toValueDomain(VariantValueEntity entity);

    List<VariantValue> toValueDomainList(List<VariantValueEntity> entities);

    @Mapping(target = "id", source = "id")
    ProductTranslation toTranslationDomain(ProductTranslationEntity entity);

    List<ProductTranslation> toTranslationDomainList(List<ProductTranslationEntity> entities);

    @Named("priceTierToDomain")
    @Mapping(target = "id", source = "id")
    ProductPriceTier toPriceTierDomain(
            ProductPriceTierEntity entity);

    List<ProductPriceTier> toPriceTierDomainList(
            List<ProductPriceTierEntity> entities);
}
