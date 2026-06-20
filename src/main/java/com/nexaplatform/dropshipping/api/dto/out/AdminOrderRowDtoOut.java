package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin list/detail row for an order. Field names mirror the keys the controller
 * previously put into its ad-hoc {@code Map<String,Object>} so the JSON contract
 * is preserved for the admin frontend.
 */
@Value
@Builder(toBuilder = true)
public class AdminOrderRowDtoOut {

    UUID id;
    String orderNumber;
    String status;
    UUID partnerAppId;
    /** Origen: PLATFORM (tienda propia) o INTEGRATION (tienda conectada). */
    String source;
    int subtotalCents;
    int shippingCents;
    int totalCents;
    String currency;
    int itemCount;
    Instant placedAt;
    Instant forwardedAt;
    Instant shippedAt;
    Instant deliveredAt;
    Instant cancelledAt;
    String customerEmail;
    String shopName;
    String shopHandle;
    String supplierName;
}
