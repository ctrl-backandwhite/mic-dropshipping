package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.util.UUID;

@Entity
@Table(name = "order_item")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItemEntity {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrderEntity order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private ProductEntity product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariantEntity variant;

    @Column(name = "title_snapshot", length = 500)
    private String titleSnapshot;

    @Column(name = "image_url_snapshot", length = 800)
    private String imageUrlSnapshot;

    @Column(name = "sku_snapshot", length = 120)
    private String skuSnapshot;

    @Column(name = "unit_price_cents", nullable = false)
    private int unitPriceCents;

    @Column(name = "cost_cents", nullable = false)
    private int costCents;

    /** Coste unitario en YUAN (CNY) céntimos, congelado al crear la orden. Base de la comisión del operador. */
    @Column(name = "cost_cny_cents", nullable = false)
    @lombok.Builder.Default
    private long costCnyCents = 0L;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "line_total_cents", nullable = false)
    private int lineTotalCents;

    /**
     * Con qué descripción se declaró esta línea en la aduana.
     *
     * <p>Es un snapshot de verdad, no un dato derivado: si el grupo se aprueba DESPUÉS de cobrar, este
     * pedido tiene que seguir contando por lo que se declaró. Sin él la vista previa contaría una línea
     * (con la descripción del grupo) y el despacho contaría dos (con el título del producto), y esos
     * 3 EUR de diferencia los pondría el comercio.
     *
     * <p>Nulo en los pedidos anteriores a la agrupación: esos se resuelven por el título, que es como se
     * declararon.
     */
    @Column(name = "declared_description", length = 512)
    private String declaredDescription;
}
