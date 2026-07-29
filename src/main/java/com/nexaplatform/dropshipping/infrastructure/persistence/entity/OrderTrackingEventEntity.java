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
 * Un evento del timeline de seguimiento de un pedido (tabla {@code order_tracking_event}). Es lo que ve
 * el cliente desde que se hace el pedido hasta que lo recibe. Alimentado por el sync de Cainiao
 * ({@code source=CAINIAO}), por transiciones del sistema ({@code SYSTEM}) o por el admin ({@code ADMIN}).
 */
@Entity
@Table(name = "order_tracking_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderTrackingEventEntity {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "order_id", nullable = false, columnDefinition = "uuid")
    private UUID orderId;

    /**
     * Bulto al que pertenece el evento. Nulo para los eventos del pedido que no son de un envío concreto
     * (el "envío registrado" que escribe el sistema) y para el histórico anterior al reparto en guías.
     */
    @Column(name = "shipment_id", columnDefinition = "uuid")
    private UUID shipmentId;

    @Column(nullable = false, length = 40)
    private String status;

    @Column(length = 300)
    private String description;

    @Column(length = 160)
    private String location;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String source = "CAINIAO";

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
