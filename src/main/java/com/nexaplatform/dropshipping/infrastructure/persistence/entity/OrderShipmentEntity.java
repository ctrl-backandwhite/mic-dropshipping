package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Un bulto del pedido (tabla {@code order_shipment}): una guía del transportista con su propia
 * trazabilidad.
 *
 * <p>Un pedido no siempre cabe en un paquete — cada canal impone un peso y un valor máximos — así que se
 * reparte con {@code ParcelSplitter} y cada bulto se da de alta por separado. El
 * {@link #sequenceNo} permite enseñar "Paquete 1 de 3" de forma estable.
 */
@Entity
@Table(name = "order_shipment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderShipmentEntity {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "order_id", nullable = false, columnDefinition = "uuid")
    private UUID orderId;

    /** Posición del bulto dentro del pedido (1..N). */
    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(length = 64)
    private String carrier;

    /** Canal del transportista con el que viaja este bulto. */
    @Column(name = "product_code", length = 50)
    private String productCode;

    /** Guía del transportista: es como identifica el envío en sus consultas y pushes. */
    @Column(name = "waybill_number", length = 64)
    private String waybillNumber;

    /** Número que ve el cliente; el canal puede asignarlo más tarde que la guía. */
    @Column(name = "tracking_number", length = 64)
    private String trackingNumber;

    /** Último estado conocido de ESTE bulto (los bultos de un pedido avanzan a ritmos distintos). */
    @Column(length = 32)
    private String status;

    @Column(name = "weight_grams", nullable = false)
    private int weightGrams;

    @Column(name = "declared_value_cents", nullable = false)
    private int declaredValueCents;

    @Column(name = "label_url", columnDefinition = "text")
    private String labelUrl;

    @Column(name = "estimated_delivery_at")
    private Instant estimatedDeliveryAt;

    @Column(name = "last_tracked_at")
    private Instant lastTrackedAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;
}
