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
    int totalCents;
    /** Total YA convertido a la moneda activa y formateado (igual que el detalle), p.ej. "19,22 €". */
    String totalFormatted;
    String currency;
    int itemCount;
    Instant placedAt;
    Instant shippedAt;
    Instant deliveredAt;
}
