package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin order detail. Extends the row contract with line items, the shipping
 * address block, notes and a tracking number. Field names mirror the keys the
 * controller previously placed into its detail {@code Map<String,Object>}.
 */
@Value
@Builder
public class AdminOrderDetailDtoOut {

    UUID id;
    String orderNumber;
    String status;
    UUID partnerAppId;
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
    List<AdminOrderLineDtoOut> items;
    AdminOrderAddressDtoOut shippingAddress;
    String notes;
    String trackingNumber;
}
