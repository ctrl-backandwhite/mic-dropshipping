package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "customer_order")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerOrderEntity extends BaseEntity {

    @Column(name = "order_number", nullable = false, unique = true, length = 40)
    private String orderNumber;

    @Column(name = "partner_app_id")
    private UUID partnerAppId;

    /** Origen de la orden: PLATFORM (tienda propia) o INTEGRATION (tienda conectada). */
    @Column(name = "source", nullable = false, length = 20)
    @lombok.Builder.Default
    private String source = "PLATFORM";

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "external_order_id", length = 120)
    private String externalOrderId;

    /** Clave de idempotencia del checkout (hash del carrito): reintentos del mismo
     *  carrito reutilizan la orden aún sin pagar en vez de crear un duplicado. */
    @Column(name = "idempotency_key", length = 80)
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipping_address_id", nullable = false)
    private AddressEntity shippingAddress;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "billing_address_id")
    private AddressEntity billingAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status;

    @Column(name = "subtotal_cents", nullable = false)
    private int subtotalCents;

    @Column(name = "shipping_cents", nullable = false)
    private int shippingCents;

    @Column(name = "tax_cents", nullable = false)
    private int taxCents;

    @Column(name = "total_cents", nullable = false)
    private int totalCents;

    // Descuento de referido aplicado al comprador (céntimos USD). total_cents ya lo resta.
    @Column(name = "discount_cents", nullable = false)
    private int discountCents;

    @Column(length = 8)
    private String currency;

    @Column(length = 2000)
    private String notes;

    @Column(name = "placed_at")
    private Instant placedAt;

    @Column(name = "forwarded_at")
    private Instant forwardedAt;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    // Fulfillment / tracking de Cainiao.
    @Column(length = 60)
    private String carrier;

    @Column(name = "tracking_number", length = 120)
    private String trackingNumber;

    @Column(name = "fulfillment_ref", length = 120)
    private String fulfillmentRef;

    @Column(name = "tracking_status", length = 60)
    private String trackingStatus;

    @Column(name = "estimated_delivery_at")
    private Instant estimatedDeliveryAt;

    @Column(name = "last_tracked_at")
    private Instant lastTrackedAt;

    @Builder.Default
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrderItemEntity> items = new ArrayList<>();
}
