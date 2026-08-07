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
 * Qué línea del pedido cubre una compra (tabla {@code supplier_purchase_item}).
 *
 * <p>Una línea pertenece a UNA sola compra —la de su proveedor—, y la base de datos lo impone con un
 * índice único sobre {@code order_item_id}: si una línea apareciera en dos compras se pagaría dos
 * veces al proveedor.
 */
@Entity
@Table(name = "supplier_purchase_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupplierPurchaseItemEntity {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "purchase_id", nullable = false, columnDefinition = "uuid")
    private UUID purchaseId;

    @Column(name = "order_item_id", nullable = false, columnDefinition = "uuid")
    private UUID orderItemId;

    @Column(nullable = false)
    private int quantity;
}
