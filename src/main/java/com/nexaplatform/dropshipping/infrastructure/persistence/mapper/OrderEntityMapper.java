package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerOrderEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderItemEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Infrastructure-layer mapper between the {@link Order} domain model and the
 * JPA entity. Builder disabled so MapStruct uses setters and can reach the
 * id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}.
 *
 * <p>On {@code toDomain} the managed relations are flattened: the shipping/billing
 * {@code AddressEntity} are projected into the flat snapshot fields and each item's
 * product/variant/supplier/image/translation data into the read-only resolved
 * fields. On {@code toEntity} the {@code items} list and the address relations are
 * resolved by the repository adapter (which owns the managed entity), so they are
 * ignored; the read-only enrichment fields have no entity counterpart.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface OrderEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "orderNumber", source = "orderNumber")
    @Mapping(target = "partnerAppId", source = "partnerAppId")
    @Mapping(target = "userId", source = "userId")
    @Mapping(target = "externalOrderId", source = "externalOrderId")
    @Mapping(target = "shippingAddressId", expression = "java(entity.getShippingAddress() != null ? entity.getShippingAddress().getId() : null)")
    @Mapping(target = "billingAddressId", expression = "java(entity.getBillingAddress() != null ? entity.getBillingAddress().getId() : null)")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "subtotalCents", source = "subtotalCents")
    @Mapping(target = "shippingCents", source = "shippingCents")
    @Mapping(target = "taxCents", source = "taxCents")
    @Mapping(target = "totalCents", source = "totalCents")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "notes", source = "notes")
    @Mapping(target = "placedAt", source = "placedAt")
    @Mapping(target = "forwardedAt", source = "forwardedAt")
    @Mapping(target = "shippedAt", source = "shippedAt")
    @Mapping(target = "deliveredAt", source = "deliveredAt")
    @Mapping(target = "cancelledAt", source = "cancelledAt")
    @Mapping(target = "items", source = "items")
    // Read-only enrichment filled by the use case; no entity counterpart.
    @Mapping(target = "customerEmail", ignore = true)
    @Mapping(target = "shopName", ignore = true)
    @Mapping(target = "shopHandle", ignore = true)
    @Mapping(target = "supplierName", ignore = true)
    // Flattened shipping-address snapshot.
    @Mapping(target = "shippingFullName", expression = "java(entity.getShippingAddress() != null ? entity.getShippingAddress().getFullName() : null)")
    @Mapping(target = "shippingPhone", expression = "java(entity.getShippingAddress() != null ? entity.getShippingAddress().getPhone() : null)")
    @Mapping(target = "shippingEmail", expression = "java(entity.getShippingAddress() != null ? entity.getShippingAddress().getEmail() : null)")
    @Mapping(target = "shippingLine1", expression = "java(entity.getShippingAddress() != null ? entity.getShippingAddress().getLine1() : null)")
    @Mapping(target = "shippingLine2", expression = "java(entity.getShippingAddress() != null ? entity.getShippingAddress().getLine2() : null)")
    @Mapping(target = "shippingCity", expression = "java(entity.getShippingAddress() != null ? entity.getShippingAddress().getCity() : null)")
    @Mapping(target = "shippingState", expression = "java(entity.getShippingAddress() != null ? entity.getShippingAddress().getState() : null)")
    @Mapping(target = "shippingPostalCode", expression = "java(entity.getShippingAddress() != null ? entity.getShippingAddress().getPostalCode() : null)")
    @Mapping(target = "shippingCountry", expression = "java(entity.getShippingAddress() != null ? entity.getShippingAddress().getCountry() : null)")
    // Flattened billing-address snapshot.
    @Mapping(target = "billingFullName", expression = "java(entity.getBillingAddress() != null ? entity.getBillingAddress().getFullName() : null)")
    @Mapping(target = "billingPhone", expression = "java(entity.getBillingAddress() != null ? entity.getBillingAddress().getPhone() : null)")
    @Mapping(target = "billingEmail", expression = "java(entity.getBillingAddress() != null ? entity.getBillingAddress().getEmail() : null)")
    @Mapping(target = "billingLine1", expression = "java(entity.getBillingAddress() != null ? entity.getBillingAddress().getLine1() : null)")
    @Mapping(target = "billingLine2", expression = "java(entity.getBillingAddress() != null ? entity.getBillingAddress().getLine2() : null)")
    @Mapping(target = "billingCity", expression = "java(entity.getBillingAddress() != null ? entity.getBillingAddress().getCity() : null)")
    @Mapping(target = "billingState", expression = "java(entity.getBillingAddress() != null ? entity.getBillingAddress().getState() : null)")
    @Mapping(target = "billingPostalCode", expression = "java(entity.getBillingAddress() != null ? entity.getBillingAddress().getPostalCode() : null)")
    @Mapping(target = "billingCountry", expression = "java(entity.getBillingAddress() != null ? entity.getBillingAddress().getCountry() : null)")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    Order toDomain(CustomerOrderEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "orderNumber", source = "orderNumber")
    @Mapping(target = "partnerAppId", source = "partnerAppId")
    @Mapping(target = "userId", source = "userId")
    @Mapping(target = "externalOrderId", source = "externalOrderId")
    @Mapping(target = "shippingAddress", ignore = true)
    @Mapping(target = "billingAddress", ignore = true)
    @Mapping(target = "status", source = "status")
    @Mapping(target = "subtotalCents", source = "subtotalCents")
    @Mapping(target = "shippingCents", source = "shippingCents")
    @Mapping(target = "taxCents", source = "taxCents")
    @Mapping(target = "totalCents", source = "totalCents")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "notes", source = "notes")
    @Mapping(target = "placedAt", source = "placedAt")
    @Mapping(target = "forwardedAt", source = "forwardedAt")
    @Mapping(target = "shippedAt", source = "shippedAt")
    @Mapping(target = "deliveredAt", source = "deliveredAt")
    @Mapping(target = "cancelledAt", source = "cancelledAt")
    @Mapping(target = "items", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    CustomerOrderEntity toEntity(Order model);

    /**
     * Aplica los campos escalares del modelo (incluidos los de tracking de Cainiao) sobre una entidad
     * gestionada (ruta de actualización). Las relaciones gestionadas (direcciones, items) y la auditoría
     * las resuelve el repositorio, por eso se ignoran aquí. MapStruct auto-mapea el resto por nombre.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "shippingAddress", ignore = true)
    @Mapping(target = "billingAddress", ignore = true)
    @Mapping(target = "items", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    void updateEntity(@MappingTarget CustomerOrderEntity entity, Order model);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "productId", expression = "java(item.getProduct() != null ? item.getProduct().getId() : null)")
    @Mapping(target = "variantId", expression = "java(item.getVariant() != null ? item.getVariant().getId() : null)")
    @Mapping(target = "titleSnapshot", source = "titleSnapshot")
    @Mapping(target = "imageUrlSnapshot", source = "imageUrlSnapshot")
    @Mapping(target = "skuSnapshot", source = "skuSnapshot")
    @Mapping(target = "unitPriceCents", source = "unitPriceCents")
    @Mapping(target = "costCents", source = "costCents")
    @Mapping(target = "costCnyCents", source = "costCnyCents")
    @Mapping(target = "quantity", source = "quantity")
    @Mapping(target = "lineTotalCents", source = "lineTotalCents")
    @Mapping(target = "productTitleZh", expression = "java(item.getProduct() != null ? item.getProduct().getTitleZh() : null)")
    @Mapping(target = "variantName", expression = "java(variantLabel(item.getVariant()))")
    @Mapping(target = "supplierName", expression = "java(item.getProduct() != null && item.getProduct().getSupplier() != null ? item.getProduct().getSupplier().getName() : null)")
    @Mapping(target = "productImageUrl", source = "product", qualifiedByName = "resolveLiveImage")
    @Mapping(target = "productSourceUrl", expression = "java(item.getProduct() != null ? item.getProduct().getSourceUrl() : null)")
    @Mapping(target = "productTitles", source = "product", qualifiedByName = "resolveTitles")
    OrderItem toItemDomain(OrderItemEntity item);

    List<OrderItem> toItemDomainList(List<OrderItemEntity> items);

    /**
     * Nombre legible de la variante para mostrar en carrito/checkout/factura.
     * Prioriza el {@code title} de la variante; si está vacío (caso de los
     * productos importados, que solo traen {@code options_json}), compone la
     * etiqueta uniendo los valores de opción (p. ej. "Negro / M").
     */
    default String variantLabel(ProductVariantEntity v) {
        if (v == null) {
            return null;
        }
        if (v.getTitle() != null && !v.getTitle().isBlank()) {
            return v.getTitle();
        }
        Map<String, String> opts = v.getOptions();
        if (opts == null || opts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String val : opts.values()) {
            if (val != null && !val.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(" / ");
                }
                sb.append(val);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Picks the first live catalog image, preferring the CDN url over the source url. */
    @Named("resolveLiveImage")
    default String resolveLiveImage(ProductEntity product) {
        if (product == null || product.getImages() == null || product.getImages().isEmpty()) {
            return null;
        }
        ProductImageEntity img = product.getImages().get(0);
        return img.getCdnUrl() != null && !img.getCdnUrl().isBlank() ? img.getCdnUrl() : img.getSourceUrl();
    }

    /** Collapses the product translations into a {language -> title} map for fallback resolution. */
    @Named("resolveTitles")
    default Map<String, String> resolveTitles(ProductEntity product) {
        Map<String, String> titles = new HashMap<>();
        if (product != null && product.getTranslations() != null) {
            for (ProductTranslationEntity tr : product.getTranslations()) {
                if (tr.getLanguage() != null && tr.getTitle() != null) {
                    titles.put(tr.getLanguage().toLowerCase(), tr.getTitle());
                }
            }
        }
        return titles;
    }
}
