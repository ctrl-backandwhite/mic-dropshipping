package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import com.nexaplatform.dropshipping.domain.enums.PromotionKind;
import com.nexaplatform.dropshipping.domain.enums.PromotionScope;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Una rebaja, cupón o promoción (tabla {@code promotion}).
 *
 * <p>La misma fila sirve para los tres casos y solo cambian dos campos: sin {@link #code} es una
 * rebaja automática que se anuncia en el catálogo; con código es un cupón que hay que teclear.
 */
@Entity
@Table(name = "promotion")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromotionEntity {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false, length = 160)
    private String name;

    /** Código del cupón. Nulo en las rebajas automáticas. Se guarda en mayúsculas. */
    @Column(length = 40)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PromotionKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PromotionScope scope;

    /** Porcentaje de descuento (1..99). Excluyente con {@link #amountOffCents}. */
    @Column(name = "percent_off", precision = 5, scale = 2)
    private BigDecimal percentOff;

    /** Descuento de importe fijo, en céntimos de la moneda base. Excluyente con el porcentaje. */
    @Column(name = "amount_off_cents")
    private Integer amountOffCents;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(nullable = false)
    private boolean active;

    /** Solo desempata: entre dos promociones aplicables gana siempre la de mayor descuento. */
    @Column(nullable = false)
    private int priority;

    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(name = "used_count", nullable = false)
    private int usedCount;

    /** Pedido mínimo para que el cupón sea válido, en céntimos. */
    @Column(name = "min_order_cents")
    private Integer minOrderCents;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * ¿Está viva ahora mismo?
     *
     * <p>Vigencia abierta por cualquiera de los dos lados: una promoción sin fecha de fin dura hasta
     * que se desactive, y una sin fecha de inicio ya está corriendo.
     */
    public boolean isLiveAt(Instant when) {
        if (!active) {
            return false;
        }
        if (startsAt != null && when.isBefore(startsAt)) {
            return false;
        }
        if (endsAt != null && !when.isBefore(endsAt)) {
            return false;
        }
        return maxUses == null || usedCount < maxUses;
    }
}
