package com.nexaplatform.dropshipping.api.dto.out;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerOrderEntity;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full order detail for the authenticated user. Field names mirror the legacy
 * {@code MeOrderDetailView} record the controller exposed.
 */
@Value
@Builder(toBuilder = true)
public class MeOrderDetailDtoOut {

    UUID id;
    String orderNumber;
    String externalOrderId;
    String status;
    // Método de pago original (CARD/PAYPAL/USDT/WALLET): el front decide a dónde ofrecer el reembolso.
    String paymentMethod;
    /**
     * ¿Se puede cancelar todavía?
     *
     * <p>No se deduce del estado: un pedido en PAGADO deja de poder cancelarse en cuanto se compra el
     * género en 1688, y esa compra vive en su propio tablero sin mover el estado del pedido. El
     * servidor es quien lo sabe, así que lo dice aquí; ofrecer un botón que el backend va a rechazar es
     * peor que no ofrecerlo.
     */
    boolean cancellable;
    BigDecimal subtotal;
    BigDecimal shipping;
    // Derecho de aduana, APARTE del porte aunque en la base vivan sumados. El comprador lo ve separado
    // al pagar; enseñarlo dentro del envío en el pedido hacía dudar de lo cobrado, y en régimen DDP es
    // un concepto con nombre propio.
    BigDecimal customsDuty;
    BigDecimal tax;
    BigDecimal total;
    // Descuento de referido del comprador (0 si no aplica). total ya lo resta.
    BigDecimal discount;
    String currency;
    // DROP-637: importes ya FORMATEADOS por el backend en la moneda mostrada (el front solo pinta).
    String subtotalFormatted;
    String shippingFormatted;
    String customsDutyFormatted;
    String taxFormatted;
    String totalFormatted;
    String discountFormatted;
    MeOrderAddressDtoOut shippingAddress;
    MeOrderAddressDtoOut billingAddress;
    String notes;
    String trackingCarrier;
    String trackingNumber;
    Instant placedAt;
    Instant shippedAt;
    Instant deliveredAt;
    Instant cancelledAt;
    List<MeOrderItemDetailDtoOut> items;

    public static MeOrderDetailDtoOut from(CustomerOrderEntity o) {
        return from(o, "es");
    }

    public static MeOrderDetailDtoOut from(CustomerOrderEntity o, String lang) {
        return MeOrderDetailDtoOut.builder().id(o.getId()).orderNumber(o.getOrderNumber())
                .externalOrderId(o.getExternalOrderId()).status(o.getStatus().name())
                // `shipping_cents` lleva el arancel dentro; se resta para poder enseñarlos separados sin
                // mover el total, igual que hace el desglose de OrderAmounts.
                .subtotal(cents(o.getSubtotalCents())).shipping(cents(o.getShippingCents() - o.getCustomsDutyCents()))
                .customsDuty(cents(o.getCustomsDutyCents())).tax(cents(o.getTaxCents())).total(cents(o.getTotalCents()))
                .discount(cents(o.getDiscountCents())).currency(o.getCurrency())
                .shippingAddress(MeOrderAddressDtoOut.from(o.getShippingAddress()))
                .billingAddress(MeOrderAddressDtoOut.from(o.getBillingAddress())).notes(o.getNotes())
                .trackingCarrier(null).trackingNumber(null).placedAt(o.getPlacedAt()).shippedAt(o.getShippedAt())
                .deliveredAt(o.getDeliveredAt()).cancelledAt(o.getCancelledAt())
                .items(o.getItems().stream().map(i -> MeOrderItemDetailDtoOut.from(i, lang)).toList()).build();
    }

    private static BigDecimal cents(int v) {
        return BigDecimal.valueOf(v).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }
}
