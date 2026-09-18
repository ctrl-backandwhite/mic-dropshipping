package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.MeOrderAddressDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderDetailDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderItemDetailDtoOut;
import com.nexaplatform.dropshipping.application.service.OrderAmounts;
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
 * <p>DROP-637: la conversión se hace <b>línea a línea</b> y luego se SUMA — exactamente igual que el
 * cobro ({@code PaymentUseCaseImpl.perLineSettlementAmount}). Convertir el total USD de una sola vez
 * daba 1–5 céntimos menos que lo realmente cobrado (p.ej. el pedido mostraba 85,76 € mientras Stripe
 * cobró 85,81 €).
 *
 * <p>Dentro de cada línea, el importe se multiplica en dólares y se convierte al final —una sola
 * conversión, un solo redondeo—, que es lo que fija {@link OrderAmounts#lineSubtotal}. Redondear el
 * unitario y multiplicarlo después inflaba el cargo hasta un 1,45 %.
 */
@Component
@RequiredArgsConstructor
public class MeOrderDtoMapper {

    private final CurrencyRateService currencyRateService;
    /** La cuenta del pedido, compartida con el checkout, el cobro y el panel. */
    private final OrderAmounts orderAmounts;
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

        // El desglose lo calcula OrderAmounts, la misma cuenta que el resumen del checkout, el importe que
        // se cobra y el panel. Aquí se hacía aparte, y de las cuatro copias tres acabaron desviándose.
        OrderAmounts.Breakdown amounts = orderAmounts.of(model, ccy);
        BigDecimal subtotal = amounts.subtotal();
        BigDecimal shipping = amounts.shipping();
        BigDecimal customsDuty = amounts.customsDuty();
        BigDecimal tax = amounts.tax();
        BigDecimal discount = amounts.discount();
        BigDecimal total = amounts.total();

        // Las líneas de la ficha: el unitario convertido (lo que el cliente reconoce) y el importe de la
        // línea, que lo calcula OrderAmounts multiplicando en dólares y convirtiendo al final. Ojo: el
        // unitario POR la cantidad ya no tiene por qué dar el importe de la línea —0,14 € × 100 son
        // 13,80 €, no 14,00 €—, y por eso la ficha publica las dos cifras: la suma de los importes de
        // línea es exactamente el subtotal de arriba.
        List<MeOrderItemDetailDtoOut> items = new ArrayList<>();
        for (OrderItem item : model.getItems() == null ? List.<OrderItem>of() : model.getItems()) {
            BigDecimal unit = currencyRateService.usdTo(
                    BigDecimal.valueOf(item.getUnitPriceCents()).movePointLeft(2), ccy);
            items.add(toItemDetail(item,
                    unit, orderAmounts.lineSubtotal(item.getUnitPriceCents(), item.getQuantity(), ccy), ccy));
        }
        // Pedido ya pagado: mostramos EXACTAMENTE lo cobrado (settlement), no la re-conversión a la tasa
        // actual. Escalamos el desglose por settlement/total (la conversión es lineal) para que cuadre.
        BigDecimal settle = settlementTotal(model.getId(), ccy);
        if (settle != null && total.signum() > 0) {
            BigDecimal f = settle.divide(total, 10, RoundingMode.HALF_UP);
            subtotal = subtotal.multiply(f).setScale(2, RoundingMode.HALF_UP);
            shipping = shipping.multiply(f).setScale(2, RoundingMode.HALF_UP);
            customsDuty = customsDuty.multiply(f).setScale(2, RoundingMode.HALF_UP);
            discount = discount.multiply(f).setScale(2, RoundingMode.HALF_UP);
            total = settle.setScale(2, RoundingMode.HALF_UP);
            // El impuesto se despeja del resto: es el único componente que no se escala directamente,
            // así que absorbe el céntimo de redondeo y la suma sigue dando lo cobrado.
            tax = total.subtract(subtotal).add(discount).subtract(shipping).subtract(customsDuty);
        }

        return MeOrderDetailDtoOut.builder().id(model.getId()).orderNumber(model.getOrderNumber())
                .externalOrderId(model.getExternalOrderId())
                .status(model.getStatus() != null ? model.getStatus().name() : null)
                .subtotal(subtotal).shipping(shipping).customsDuty(customsDuty).tax(tax).total(total)
                .discount(discount).currency(ccy)
                .subtotalFormatted(currencyRateService.formatDisplay(subtotal, ccy))
                .shippingFormatted(currencyRateService.formatDisplay(shipping, ccy))
                .customsDutyFormatted(currencyRateService.formatDisplay(customsDuty, ccy))
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
     * Total del pedido en la moneda activa para la LISTA de pedidos.
     *
     * <p>La cuenta la hace {@link OrderAmounts}, la misma que el detalle, el cobro y el panel. Aquí estaba
     * copiada línea por línea, y esa copia es exactamente la que se desvió cuando cambió el redondeo de
     * los importes de línea: la lista seguía multiplicando el unitario ya convertido mientras el cobro
     * había dejado de hacerlo.
     */
    public String formatOrderTotal(Order o) {
        if (o == null) {
            return null;
        }
        String ccy = CurrencyHolder.get();
        BigDecimal total = orderAmounts.totalOf(o, ccy);
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
