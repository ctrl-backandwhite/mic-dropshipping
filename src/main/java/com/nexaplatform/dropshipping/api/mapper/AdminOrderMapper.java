package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.AdminOrderAddressDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminOrderDetailDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminOrderLineDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminOrderRowDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderRowDtoOut;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for the Admin Orders boundary. Translates the enriched
 * {@link Order} domain model into the flat DtoOut contract. Injected in the
 * controller. The cross-aggregate fields (customerEmail, shopName, shopHandle,
 * supplierName) are read-only enrichment filled by the use case; the shipping
 * address block is built from the flat snapshot fields the repository adapter
 * resolved from the managed {@code AddressEntity}.
 */
@Mapper(componentModel = "spring")
public interface AdminOrderMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "orderNumber", source = "orderNumber")
    @Mapping(target = "status", expression = "java(order.getStatus() != null ? order.getStatus().name() : null)")
    @Mapping(target = "partnerAppId", source = "partnerAppId")
    @Mapping(target = "source", source = "source")
    @Mapping(target = "subtotalCents", source = "subtotalCents")
    @Mapping(target = "shippingCents", source = "shippingCents")
    @Mapping(target = "totalCents", source = "totalCents")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "itemCount", expression = "java(order.getItems() != null ? order.getItems().size() : 0)")
    @Mapping(target = "placedAt", source = "placedAt")
    @Mapping(target = "forwardedAt", source = "forwardedAt")
    @Mapping(target = "shippedAt", source = "shippedAt")
    @Mapping(target = "deliveredAt", source = "deliveredAt")
    @Mapping(target = "cancelledAt", source = "cancelledAt")
    @Mapping(target = "customerEmail", source = "customerEmail")
    @Mapping(target = "shopName", source = "shopName")
    @Mapping(target = "shopHandle", source = "shopHandle")
    @Mapping(target = "supplierName", source = "supplierName")
    AdminOrderRowDtoOut toRow(Order order);

    List<AdminOrderRowDtoOut> toRows(List<Order> orders);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "orderNumber", source = "orderNumber")
    @Mapping(target = "status", expression = "java(order.getStatus() != null ? order.getStatus().name() : null)")
    @Mapping(target = "partnerAppId", source = "partnerAppId")
    @Mapping(target = "source", source = "source")
    @Mapping(target = "subtotalCents", source = "subtotalCents")
    @Mapping(target = "shippingCents", source = "shippingCents")
    @Mapping(target = "taxCents", source = "taxCents")
    @Mapping(target = "totalCents", source = "totalCents")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "itemCount", expression = "java(order.getItems() != null ? order.getItems().size() : 0)")
    @Mapping(target = "placedAt", source = "placedAt")
    @Mapping(target = "forwardedAt", source = "forwardedAt")
    @Mapping(target = "shippedAt", source = "shippedAt")
    @Mapping(target = "deliveredAt", source = "deliveredAt")
    @Mapping(target = "cancelledAt", source = "cancelledAt")
    @Mapping(target = "customerEmail", source = "customerEmail")
    @Mapping(target = "shopName", source = "shopName")
    @Mapping(target = "shopHandle", source = "shopHandle")
    @Mapping(target = "supplierName", source = "supplierName")
    @Mapping(target = "items", source = "items")
    @Mapping(target = "shippingAddress", expression = "java(toAddress(order))")
    @Mapping(target = "notes", source = "notes")
    // El número de seguimiento es el que da el transportista, no la referencia externa del pedido
    // (externalOrderId, del tipo "ME-1784936692"): mostrar esa hacía que el admin viese en la ficha un
    // número distinto del que aparece en el bloque de seguimiento y del que se comunica al cliente.
    @Mapping(target = "trackingNumber", source = "trackingNumber")
    AdminOrderDetailDtoOut toDetail(Order order);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "productId", source = "productId")
    @Mapping(target = "sku", source = "skuSnapshot")
    @Mapping(target = "title", expression = "java(resolveTitle(item))")
    @Mapping(target = "variantName", source = "variantName")
    @Mapping(target = "imageUrl", expression = "java(lineImage(item))")
    @Mapping(target = "qty", source = "quantity")
    @Mapping(target = "unitPriceCents", source = "unitPriceCents")
    @Mapping(target = "lineTotalCents", source = "lineTotalCents")
    @Mapping(target = "productSourceUrl", source = "productSourceUrl")
    AdminOrderLineDtoOut toLine(OrderItem item);

    /**
     * Miniatura de la línea. Prioriza la imagen de la VARIANTE seleccionada (color concreto) para que
     * coincida con lo pedido; si no hay, cae al snapshot del pedido y luego a la imagen viva del producto.
     */
    default String lineImage(OrderItem item) {
        if (item.getVariantImageUrl() != null && !item.getVariantImageUrl().isBlank()) {
            return item.getVariantImageUrl();
        }
        String image = item.getImageUrlSnapshot();
        if ((image == null || image.isBlank()) && item.getProductImageUrl() != null) {
            image = item.getProductImageUrl();
        }
        return image;
    }

    List<AdminOrderLineDtoOut> toLines(List<OrderItem> items);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "orderNumber", source = "orderNumber")
    @Mapping(target = "status", expression = "java(order.getStatus() != null ? order.getStatus().name() : null)")
    @Mapping(target = "totalCents", source = "totalCents")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "itemCount", expression = "java(order.getItems() != null ? order.getItems().size() : 0)")
    @Mapping(target = "placedAt", source = "placedAt")
    @Mapping(target = "shippedAt", source = "shippedAt")
    @Mapping(target = "deliveredAt", source = "deliveredAt")
    MeOrderRowDtoOut toMeRow(Order order);

    List<MeOrderRowDtoOut> toMeRows(List<Order> orders);

    /** Builds the shipping address block from the order's flat snapshot fields ({@code state -> region}). */
    default AdminOrderAddressDtoOut toAddress(Order order) {
        if (order.getShippingFullName() == null && order.getShippingLine1() == null) {
            return null;
        }
        return AdminOrderAddressDtoOut.builder().fullName(order.getShippingFullName()).line1(order.getShippingLine1())
                .line2(order.getShippingLine2()).city(order.getShippingCity()).region(order.getShippingState())
                .postalCode(order.getShippingPostalCode()).country(order.getShippingCountry())
                .phone(order.getShippingPhone()).build();
    }

    /** Title fallback: explicit snapshot, then live product titleZh, then sku. */
    default String resolveTitle(OrderItem item) {
        if (item.getTitleSnapshot() != null) {
            return item.getTitleSnapshot();
        }
        if (item.getProductTitleZh() != null) {
            return item.getProductTitleZh();
        }
        return item.getSkuSnapshot();
    }
}
