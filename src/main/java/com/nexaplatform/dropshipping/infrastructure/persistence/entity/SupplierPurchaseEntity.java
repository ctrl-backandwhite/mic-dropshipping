package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import com.nexaplatform.dropshipping.domain.enums.SupplierPurchaseStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * La compra al proveedor de 1688 para un pedido (tabla {@code supplier_purchase}).
 *
 * <p>Una fila por (pedido, proveedor), que es exactamente un bulto: el proveedor manda un paquete con
 * todo lo que se le compre para ese pedido. Dos líneas del mismo proveedor son una sola compra; dos
 * proveedores distintos son dos bultos que luego se consolidan bajo un único número YT.
 */
@Entity
@Table(name = "supplier_purchase")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupplierPurchaseEntity {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "order_id", nullable = false, columnDefinition = "uuid")
    private UUID orderId;

    @Column(name = "supplier_id", nullable = false, columnDefinition = "uuid")
    private UUID supplierId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SupplierPurchaseStatus status;

    /** Número del pedido en 1688, que teclea el admin tras pagar con Alipay. */
    @Column(name = "purchase_ref", length = 120)
    private String purchaseRef;

    /**
     * Coste REALMENTE pagado en céntimos de CNY, no el del catálogo: el proveedor cambia precios y sin
     * este dato el margen del pedido es una estimación, no un hecho.
     */
    @Column(name = "cost_cny_cents")
    private Long costCnyCents;

    /** Envío nacional chino pagado al proveedor, en céntimos de CNY. */
    @Column(name = "shipping_cny_cents")
    private Long shippingCnyCents;

    @Column(name = "purchased_at")
    private Instant purchasedAt;

    /** Seguimiento nacional: por él empareja el almacén el bulto físico con las instrucciones. */
    @Column(name = "domestic_tracking", length = 64)
    private String domesticTracking;

    @Column(name = "domestic_carrier", length = 64)
    private String domesticCarrier;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "warehouse_code", nullable = false, length = 32)
    private String warehouseCode;

    /** Desde aquí corren los 30 días que el almacén aguanta un bulto antes de destruirlo. */
    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "pack_order_no", length = 64)
    private String packOrderNo;

    @Column(name = "pack_service_type", length = 40)
    private String packServiceType;

    @Column(name = "pack_submitted_at")
    private Instant packSubmittedAt;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
