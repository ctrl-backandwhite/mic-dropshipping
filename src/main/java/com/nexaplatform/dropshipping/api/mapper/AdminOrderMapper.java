package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.AdminOrderAddressDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminOrderDetailDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminOrderLineDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminOrderRowDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderRowDtoOut;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
public abstract class AdminOrderMapper {

    /**
     * Conversión y formato de importes. El listado del admin los pintaba en el navegador y salía un
     * céntimo por debajo de lo cobrado; el precio se calcula y se formatea SIEMPRE en el backend.
     */
    protected CurrencyRateService currencyRateService;

    /**
     * Inyección por setter, no por constructor: MapStruct genera {@code AdminOrderMapperImpl extends
     * AdminOrderMapper} sin declarar constructor alguno, así que un constructor con argumentos aquí
     * dejaría a la clase generada sin un {@code super()} al que llamar y no compilaría.
     */
    @Autowired
    protected void setCurrencyRateService(CurrencyRateService currencyRateService) {
        this.currencyRateService = currencyRateService;
    }

    /**
     * Total del pedido tal y como se le cobró al cliente, en la divisa activa del panel.
     *
     * <p>Se suman los tres componentes ya convertidos y redondeados —subtotal, envío e impuestos— y no
     * el total canónico en USD de una vez. Parece lo mismo y no lo es: con el pedido de la certificación
     * (7,14 + 6,25 + 2,81 = 16,20 USD a 0,87717) convertir de una vez da 14,21 € y sumar los componentes
     * da 14,20 €, que es lo que el cliente vio en su ficha y lo que pagó. El panel desde el que se
     * atiende una reclamación tiene que enseñar exactamente esa cifra.
     *
     * <p>Se parte de {@code subtotalCents} y no de las líneas: la ficha del panel no siempre las trae
     * cargadas, y recorrerlas daba cero cuando faltaban.
     */
    protected String totalFormatted(Order order) {
        String ccy = CurrencyHolder.get();
        BigDecimal total = enDivisa(order.getSubtotalCents(), ccy)
                .add(enDivisa(order.getShippingCents(), ccy))
                .add(enDivisa(order.getTaxCents(), ccy))
                .subtract(enDivisa(order.getDiscountCents(), ccy));
        return currencyRateService.formatDisplay(total, ccy);
    }

    /** Un importe en céntimos USD, convertido a la divisa del panel y redondeado a su céntimo. */
    private BigDecimal enDivisa(int cents, String ccy) {
        return currencyRateService.usdTo(BigDecimal.valueOf(cents).movePointLeft(2), ccy)
                .setScale(2, RoundingMode.HALF_UP);
    }


    @Mapping(target = "id", source = "id")
    @Mapping(target = "orderNumber", source = "orderNumber")
    @Mapping(target = "status", expression = "java(order.getStatus() != null ? order.getStatus().name() : null)")
    @Mapping(target = "partnerAppId", source = "partnerAppId")
    @Mapping(target = "source", source = "source")
    @Mapping(target = "subtotalCents", source = "subtotalCents")
    @Mapping(target = "shippingCents", source = "shippingCents")
    @Mapping(target = "totalCents", source = "totalCents")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "totalFormatted", expression = "java(totalFormatted(order))")
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
    public abstract AdminOrderRowDtoOut toRow(Order order);

    public abstract List<AdminOrderRowDtoOut> toRows(List<Order> orders);

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
    @Mapping(target = "totalFormatted", expression = "java(totalFormatted(order))")
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
    public abstract AdminOrderDetailDtoOut toDetail(Order order);

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
    public abstract AdminOrderLineDtoOut toLine(OrderItem item);

    /**
     * Miniatura de la línea. Prioriza la imagen de la VARIANTE seleccionada (color concreto) para que
     * coincida con lo pedido; si no hay, cae al snapshot del pedido y luego a la imagen viva del producto.
     */
    protected String lineImage(OrderItem item) {
        if (item.getVariantImageUrl() != null && !item.getVariantImageUrl().isBlank()) {
            return item.getVariantImageUrl();
        }
        String image = item.getImageUrlSnapshot();
        if ((image == null || image.isBlank()) && item.getProductImageUrl() != null) {
            image = item.getProductImageUrl();
        }
        return image;
    }

    public abstract List<AdminOrderLineDtoOut> toLines(List<OrderItem> items);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "orderNumber", source = "orderNumber")
    @Mapping(target = "status", expression = "java(order.getStatus() != null ? order.getStatus().name() : null)")
    @Mapping(target = "totalCents", source = "totalCents")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "totalFormatted", expression = "java(totalFormatted(order))")
    @Mapping(target = "itemCount", expression = "java(order.getItems() != null ? order.getItems().size() : 0)")
    @Mapping(target = "placedAt", source = "placedAt")
    @Mapping(target = "shippedAt", source = "shippedAt")
    @Mapping(target = "deliveredAt", source = "deliveredAt")
    public abstract MeOrderRowDtoOut toMeRow(Order order);

    public abstract List<MeOrderRowDtoOut> toMeRows(List<Order> orders);

    /** Builds the shipping address block from the order's flat snapshot fields ({@code state -> region}). */
    protected AdminOrderAddressDtoOut toAddress(Order order) {
        if (order.getShippingFullName() == null && order.getShippingLine1() == null) {
            return null;
        }
        return AdminOrderAddressDtoOut.builder().fullName(order.getShippingFullName()).line1(order.getShippingLine1())
                .line2(order.getShippingLine2()).city(order.getShippingCity()).region(order.getShippingState())
                .postalCode(order.getShippingPostalCode()).country(order.getShippingCountry())
                .phone(order.getShippingPhone()).build();
    }

    /** Title fallback: explicit snapshot, then live product titleZh, then sku. */
    protected String resolveTitle(OrderItem item) {
        if (item.getTitleSnapshot() != null) {
            return item.getTitleSnapshot();
        }
        if (item.getProductTitleZh() != null) {
            return item.getProductTitleZh();
        }
        return item.getSkuSnapshot();
    }
}
