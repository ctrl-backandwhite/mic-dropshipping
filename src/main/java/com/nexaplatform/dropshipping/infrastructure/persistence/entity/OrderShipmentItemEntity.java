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

import java.util.UUID;

/**
 * Lo que viaja dentro de un bulto (tabla {@code order_shipment_item}).
 *
 * <p>Un pedido que no cabe en una guía se reparte en varias, y ese reparto se calcula al crear los
 * envíos. Sin guardarlo, el seguimiento enseñaba «Paquete 1/2» sin decir qué había dentro: quien
 * recibía uno no sabía a qué le estaba siguiendo la pista.
 *
 * <p>Lleva cantidad porque una misma línea puede partirse entre bultos cuando el peso no cabe en una
 * sola guía.
 */
@Entity
@Table(name = "order_shipment_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderShipmentItemEntity {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "shipment_id", nullable = false, columnDefinition = "uuid")
    private UUID shipmentId;

    /** Sin clave ajena: las líneas se borran con el pedido y aquí basta con quedar huérfano. */
    @Column(name = "order_item_id", nullable = false, columnDefinition = "uuid")
    private UUID orderItemId;

    @Column(nullable = false)
    private int quantity;
}
