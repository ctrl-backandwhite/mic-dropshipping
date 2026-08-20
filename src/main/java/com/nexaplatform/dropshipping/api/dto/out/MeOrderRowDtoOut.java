package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

/**
 * Compact row for the authenticated user's order list. Field names mirror the
 * keys the controller previously placed into its per-row {@code Map}.
 */
@Value
@Builder(toBuilder = true)
public class MeOrderRowDtoOut {

    UUID id;
    String orderNumber;
    String status;
    // Método de pago original (CARD/PAYPAL/USDT/WALLET): decide a dónde ofrecer el reembolso al cancelar.
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
    int totalCents;
    /** Total YA convertido a la moneda activa y formateado (igual que el detalle), p.ej. "19,22 €". */
    String totalFormatted;
    String currency;
    int itemCount;
    Instant placedAt;
    Instant shippedAt;
    Instant deliveredAt;
}
