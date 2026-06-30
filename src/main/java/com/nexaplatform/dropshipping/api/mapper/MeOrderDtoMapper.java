package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.MeOrderAddressDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderDetailDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderItemDetailDtoOut;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Proyecta el modelo de dominio {@link Order} (importes canónicos en céntimos USD) al detalle del
 * pedido del usuario, CONVERTIDO a la moneda activa (header {@code X-Currency}) y FORMATEADO en el
 * backend (el front solo pinta).
 *
 * <p>DROP-637: la conversión se hace <b>línea a línea</b> (cada línea {@code usdTo}, 2 dec hacia
 * arriba) y luego se SUMA — exactamente igual que el cobro ({@code PaymentUseCaseImpl
 * .perLineSettlementAmount}). Convertir el total USD de una sola vez daba 1–5 céntimos menos que lo
 * realmente cobrado (p.ej. el pedido mostraba 85,76 € mientras Stripe cobró 85,81 €).
 */
@Component
@RequiredArgsConstructor
public class MeOrderDtoMapper {

    private final CurrencyRateService currencyRateService;

    public MeOrderDetailDtoOut toDetailDtoOut(Order model) {
        if (model == null) {
            return null;
        }
        String ccy = CurrencyHolder.get();

        BigDecimal subtotal = BigDecimal.ZERO;
        List<MeOrderItemDetailDtoOut> items = new ArrayList<>();
        for (OrderItem item : model.getItems()) {
            BigDecimal usdUnit = BigDecimal.valueOf(item.getUnitPriceCents()).movePointLeft(2);
            BigDecimal unit = currencyRateService.usdTo(usdUnit, ccy);
            BigDecimal lineTotal = unit.multiply(BigDecimal.valueOf(item.getQuantity()));
            subtotal = subtotal.add(lineTotal);
            items.add(toItemDetail(item, unit, lineTotal, ccy));
        }
        BigDecimal shipping = currencyRateService.usdTo(BigDecimal.valueOf(model.getShippingCents()).movePointLeft(2),
                ccy);
        BigDecimal tax = currencyRateService.usdTo(BigDecimal.valueOf(model.getTaxCents()).movePointLeft(2), ccy);
        // Total = suma de los componentes YA redondeados a 2 decimales, para que el desglose mostrado
        // cuadre exactamente (subtotal + envío + IVA = total) y coincida con el resumen del checkout.
        BigDecimal total = subtotal.setScale(2, java.math.RoundingMode.HALF_UP)
                .add(shipping.setScale(2, java.math.RoundingMode.HALF_UP))
                .add(tax.setScale(2, java.math.RoundingMode.HALF_UP));

        return MeOrderDetailDtoOut.builder().id(model.getId()).orderNumber(model.getOrderNumber())
                .externalOrderId(model.getExternalOrderId())
                .status(model.getStatus() != null ? model.getStatus().name() : null)
                .subtotal(subtotal).shipping(shipping).tax(tax).total(total).currency(ccy)
                .subtotalFormatted(currencyRateService.formatDisplay(subtotal, ccy))
                .shippingFormatted(currencyRateService.formatDisplay(shipping, ccy))
                .taxFormatted(currencyRateService.formatDisplay(tax, ccy))
                .totalFormatted(currencyRateService.formatDisplay(total, ccy))
                .shippingAddress(shippingAddress(model)).billingAddress(billingAddress(model)).notes(model.getNotes())
                .trackingCarrier(null).trackingNumber(null).placedAt(model.getPlacedAt()).shippedAt(model.getShippedAt())
                .deliveredAt(model.getDeliveredAt()).cancelledAt(model.getCancelledAt()).items(items).build();
    }

    /**
     * Total del pedido en la moneda activa, calculado EXACTAMENTE igual que el detalle (conversión línea a
     * línea + suma de componentes redondeados), para que la lista de pedidos muestre el MISMO total que el
     * detalle (evita desfases de céntimos entre ambas vistas).
     */
    public String formatOrderTotal(Order o) {
        if (o == null) {
            return null;
        }
        String ccy = CurrencyHolder.get();
        BigDecimal subtotal = BigDecimal.ZERO;
        if (o.getItems() != null) {
            for (OrderItem item : o.getItems()) {
                BigDecimal usdUnit = BigDecimal.valueOf(item.getUnitPriceCents()).movePointLeft(2);
                subtotal = subtotal.add(currencyRateService.usdTo(usdUnit, ccy)
                        .multiply(BigDecimal.valueOf(item.getQuantity())));
            }
        }
        BigDecimal shipping = currencyRateService.usdTo(BigDecimal.valueOf(o.getShippingCents()).movePointLeft(2), ccy);
        BigDecimal tax = currencyRateService.usdTo(BigDecimal.valueOf(o.getTaxCents()).movePointLeft(2), ccy);
        BigDecimal total = subtotal.setScale(2, java.math.RoundingMode.HALF_UP)
                .add(shipping.setScale(2, java.math.RoundingMode.HALF_UP))
                .add(tax.setScale(2, java.math.RoundingMode.HALF_UP));
        return currencyRateService.formatDisplay(total, ccy);
    }

    private MeOrderItemDetailDtoOut toItemDetail(OrderItem item, BigDecimal unit, BigDecimal lineTotal, String ccy) {
        return MeOrderItemDetailDtoOut.builder().id(item.getId()).productId(item.getProductId())
                .variantId(item.getVariantId()).productTitle(item.getTitleSnapshot())
                .variantName(item.getVariantName()).imageUrl(image(item)).quantity(item.getQuantity())
                .unitPrice(unit).lineTotal(lineTotal)
                .unitPriceFormatted(currencyRateService.formatDisplay(unit, ccy))
                .lineTotalFormatted(currencyRateService.formatDisplay(lineTotal, ccy)).build();
    }

    /** Prefers a live catalog image over the snapshot (often a placeholder or empty). */
    private String image(OrderItem item) {
        String image = item.getImageUrlSnapshot();
        if ((image == null || image.isBlank()) && item.getProductImageUrl() != null) {
            image = item.getProductImageUrl();
        }
        return image;
    }

    /** Builds the shipping address block from the order's flat snapshot fields. */
    private MeOrderAddressDtoOut shippingAddress(Order model) {
        if (model.getShippingFullName() == null && model.getShippingLine1() == null) {
            return null;
        }
        return MeOrderAddressDtoOut.builder().fullName(model.getShippingFullName()).phone(model.getShippingPhone())
                .email(model.getShippingEmail()).line1(model.getShippingLine1()).line2(model.getShippingLine2())
                .city(model.getShippingCity()).state(model.getShippingState()).postalCode(model.getShippingPostalCode())
                .country(model.getShippingCountry()).build();
    }

    /** Builds the billing address block from the order's flat snapshot fields (nullable). */
    private MeOrderAddressDtoOut billingAddress(Order model) {
        if (model.getBillingFullName() == null && model.getBillingLine1() == null) {
            return null;
        }
        return MeOrderAddressDtoOut.builder().fullName(model.getBillingFullName()).phone(model.getBillingPhone())
                .email(model.getBillingEmail()).line1(model.getBillingLine1()).line2(model.getBillingLine2())
                .city(model.getBillingCity()).state(model.getBillingState()).postalCode(model.getBillingPostalCode())
                .country(model.getBillingCountry()).build();
    }
}
