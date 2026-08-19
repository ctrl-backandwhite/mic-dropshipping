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

    /** Canal del transportista que eligió el cliente al pagar; vacío si no eligió. */
    @Column(name = "shipping_channel_code", length = 32)
    private String shippingChannelCode;

    /**
     * Qué transportista lo lleva. Los pedidos anteriores al 18-ago-2026 son todos de YunExpress, que es
     * lo que la migración deja escrito: dejarlo vacío obligaría a adivinarlo al despachar un pedido
     * antiguo.
     */
    @Column(name = "shipping_carrier", length = 32)
    private String shippingCarrier;

    /** Nombre de la línea contratada. CJ lo exige para emitir la guía; el código no le vale. */
    @Column(name = "shipping_channel_name", length = 96)
    private String shippingChannelName;

    @Column(name = "shipping_cents", nullable = false)
    private int shippingCents;

    @Column(name = "tax_cents", nullable = false)
    private int taxCents;

    /**
     * Derecho de aduana de la Unión cobrado en el pedido. Va INCLUIDO en {@link #shippingCents}: no se
     * suma dos veces al total. Se guarda aparte para poder desglosarlo en la factura, donde un tributo que
     * el vendedor recauda y entrega a la aduana no puede ir escondido dentro del precio del transporte.
     */
    @Column(name = "customs_duty_cents", nullable = false)
    private int customsDutyCents;

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

    /** Intentos de creación del envío consumidos; vuelve a 0 en cuanto la guía existe. */
    @Column(name = "fulfillment_attempts", nullable = false)
    private int fulfillmentAttempts;

    /** Último motivo de rechazo del transportista, tal cual, para que el admin sepa qué corregir. */
    @Column(name = "fulfillment_error")
    private String fulfillmentError;

    /** No nulo = se abandonó el envío y hace falta intervención manual. */
    @Column(name = "fulfillment_failed_at")
    private Instant fulfillmentFailedAt;

    /** No se reintenta antes de este instante (backoff entre fallos transitorios). */
    @Column(name = "fulfillment_next_attempt_at")
    private Instant fulfillmentNextAttemptAt;

    @Builder.Default
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrderItemEntity> items = new ArrayList<>();
}
