package com.nexaplatform.dropshipping.domain.model;

import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure domain model for a customer order (aggregate root). Use cases operate on
 * this model; mappers translate to/from the JPA entity (infra) and the transport
 * DTOs (api). The {@code items} list is the nested sub-entity model; the address
 * relations are resolved by the repository adapter from the flattened ids.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    private UUID id;
    private String orderNumber;
    private UUID partnerAppId;
    private UUID userId;
    private String externalOrderId;
    private UUID shippingAddressId;
    private UUID billingAddressId;
    private OrderStatus status;
    private int subtotalCents;
    private int shippingCents;
    private int taxCents;
    private int totalCents;
    private String currency;
    private String notes;
    private Instant placedAt;
    private Instant forwardedAt;
    private Instant shippedAt;
    private Instant deliveredAt;
    private Instant cancelledAt;

    // Fulfillment / tracking de Cainiao.
    private String carrier;
    private String trackingNumber;
    private String fulfillmentRef;
    private String trackingStatus;
    private Instant estimatedDeliveryAt;
    private Instant lastTrackedAt;

    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    // Read-only enrichment (filled by the use case from cross-aggregate relations).
    private String customerEmail;
    private String shopName;
    private String shopHandle;
    private String supplierName;

    // Flattened shipping-address snapshot (resolved by the repository adapter from
    // the managed AddressEntity) so the api mappers can build the address blocks
    // without touching the entity.
    private String shippingFullName;
    private String shippingPhone;
    private String shippingEmail;
    private String shippingLine1;
    private String shippingLine2;
    private String shippingCity;
    private String shippingState;
    private String shippingPostalCode;
    private String shippingCountry;

    // Flattened billing-address snapshot (nullable; resolved by the adapter).
    private String billingFullName;
    private String billingPhone;
    private String billingEmail;
    private String billingLine1;
    private String billingLine2;
    private String billingCity;
    private String billingState;
    private String billingPostalCode;
    private String billingCountry;

    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
