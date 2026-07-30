package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.MeOrderAddressDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderDetailDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderItemDetailDtoOut;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
    private final PaymentJpaRepositoryAdapter paymentRepository;

    /**
     * Importe REALMENTE cobrado (settlement) del pago satisfactorio del pedido, si su moneda coincide con
     * la moneda mostrada. Para un pedido ya pagado con proveedor externo (tarjeta/PayPal) devolvemos lo
     * cobrado en su día en vez de re-convertir los USD canónicos a la tasa actual (que deriva ~% con el
     * tiempo y no coincide con Stripe). {@code null} si no aplica (sin pago, otra moneda, pago con wallet).
     */
    private BigDecimal settlementTotal(UUID orderId, String ccy) {
        if (orderId == null || ccy == null) {
            return null;
        }
        return paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED)
                .filter(p -> p.getSettlementAmount() != null && ccy.equalsIgnoreCase(p.getSettlementCurrency()))
                .map(p -> p.getSettlementAmount())
                .findFirst().orElse(null);
    }

    public MeOrderDetailDtoOut toDetailDtoOut(Order model) {
        if (model == null) {
            return null;
        }
        String ccy = CurrencyHolder.get();

        BigDecimal subtotal = BigDecimal.ZERO;
        List<MeOrderItemDetailDtoOut> items = new ArrayList<>();
        // Igual que formatOrderTotal: un pedido sin líneas cargadas no puede romper la ficha (la lista
        // ya lo tolera, y ver un pedido sin líneas es mejor que un 500 al abrirlo).
        for (OrderItem item : model.getItems() == null ? List.<OrderItem>of() : model.getItems()) {
            BigDecimal usdUnit = BigDecimal.valueOf(item.getUnitPriceCents()).movePointLeft(2);
            BigDecimal unit = currencyRateService.usdTo(usdUnit, ccy);
            BigDecimal lineTotal = unit.multiply(BigDecimal.valueOf(item.getQuantity()));
            subtotal = subtotal.add(lineTotal);
            items.add(toItemDetail(item, unit, lineTotal, ccy));
        }
        BigDecimal shipping = currencyRateService.usdTo(BigDecimal.valueOf(model.getShippingCents()).movePointLeft(2),
                ccy);
        BigDecimal tax = currencyRateService.usdTo(BigDecimal.valueOf(model.getTaxCents()).movePointLeft(2), ccy);
        BigDecimal discount = currencyRateService.usdTo(BigDecimal.valueOf(model.getDiscountCents()).movePointLeft(2),
                ccy);
        // Total = subtotal − DESCUENTO + envío + IVA, con los componentes YA redondeados a 2 decimales,
        // para que el desglose mostrado cuadre exactamente y coincida con el resumen del checkout y con
        // lo COBRADO (total_cents del pedido ya resta el descuento).
        subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);
        shipping = shipping.setScale(2, RoundingMode.HALF_UP);
        tax = tax.setScale(2, RoundingMode.HALF_UP);
        discount = discount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.subtract(discount).add(shipping).add(tax);
        // Pedido ya pagado: mostramos EXACTAMENTE lo cobrado (settlement), no la re-conversión a la tasa
        // actual. Escalamos el desglose por settlement/total (la conversión es lineal) para que cuadre.
        BigDecimal settle = settlementTotal(model.getId(), ccy);
        if (settle != null && total.signum() > 0) {
            BigDecimal f = settle.divide(total, 10, RoundingMode.HALF_UP);
            subtotal = subtotal.multiply(f).setScale(2, RoundingMode.HALF_UP);
            shipping = shipping.multiply(f).setScale(2, RoundingMode.HALF_UP);
            discount = discount.multiply(f).setScale(2, RoundingMode.HALF_UP);
            total = settle.setScale(2, RoundingMode.HALF_UP);
            tax = total.subtract(subtotal).add(discount).subtract(shipping);
        }

        return MeOrderDetailDtoOut.builder().id(model.getId()).orderNumber(model.getOrderNumber())
                .externalOrderId(model.getExternalOrderId())
                .status(model.getStatus() != null ? model.getStatus().name() : null)
                .subtotal(subtotal).shipping(shipping).tax(tax).total(total).discount(discount).currency(ccy)
                .subtotalFormatted(currencyRateService.formatDisplay(subtotal, ccy))
                .shippingFormatted(currencyRateService.formatDisplay(shipping, ccy))
                .taxFormatted(currencyRateService.formatDisplay(tax, ccy))
                .totalFormatted(currencyRateService.formatDisplay(total, ccy))
                .discountFormatted(currencyRateService.formatDisplay(discount, ccy))
                .shippingAddress(shippingAddress(model)).billingAddress(billingAddress(model)).notes(model.getNotes())
                // Transportista y nº de seguimiento reales: la ficha del pedido los muestra en cuanto el
                // envío existe, sin obligar al comprador a abrir el bloque de seguimiento para verlos.
                .trackingCarrier(model.getCarrier()).trackingNumber(model.getTrackingNumber())
                .placedAt(model.getPlacedAt()).shippedAt(model.getShippedAt())
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
        BigDecimal discount = currencyRateService.usdTo(BigDecimal.valueOf(o.getDiscountCents()).movePointLeft(2), ccy);
        BigDecimal total = subtotal.setScale(2, RoundingMode.HALF_UP)
                .subtract(discount.setScale(2, RoundingMode.HALF_UP))
                .add(shipping.setScale(2, RoundingMode.HALF_UP))
                .add(tax.setScale(2, RoundingMode.HALF_UP));
        // Pedido pagado: el total de la lista es EXACTAMENTE lo cobrado (settlement), igual que el detalle.
        BigDecimal settle = settlementTotal(o.getId(), ccy);
        return currencyRateService.formatDisplay(settle != null ? settle : total, ccy);
    }

    private MeOrderItemDetailDtoOut toItemDetail(OrderItem item, BigDecimal unit, BigDecimal lineTotal, String ccy) {
        return MeOrderItemDetailDtoOut.builder().id(item.getId()).productId(item.getProductId())
                .variantId(item.getVariantId()).productTitle(item.getTitleSnapshot())
                .variantName(item.getVariantName()).imageUrl(image(item)).quantity(item.getQuantity())
                .unitPrice(unit).lineTotal(lineTotal)
                .unitPriceFormatted(currencyRateService.formatDisplay(unit, ccy))
                .lineTotalFormatted(currencyRateService.formatDisplay(lineTotal, ccy)).build();
    }

    /**
     * Imagen de la línea. Prioriza la imagen de la VARIANTE seleccionada (color concreto) para que la
     * miniatura coincida con lo pedido; si no hay, usa el snapshot y luego la imagen viva del producto.
     */
    private String image(OrderItem item) {
        if (item.getVariantImageUrl() != null && !item.getVariantImageUrl().isBlank()) {
            return item.getVariantImageUrl();
        }
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
