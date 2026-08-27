package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Ajuste (fila única, id = 1) del margen para productos con pedido mínimo (MOQ &gt; 1): si está habilitado,
 * esos productos aplican {@code factorPercent}% del margen que les corresponda (por defecto 50%). No es una
 * regla de {@code price_rule} porque no define un margen propio, sino un modificador sobre el margen resuelto.
 */
@Entity
@Table(name = "moq_margin_setting")
@Getter
@Setter
@NoArgsConstructor
public class MoqMarginSettingEntity {

    @Id
    @Column(nullable = false)
    private Short id;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "factor_percent", precision = 6, scale = 2, nullable = false)
    private BigDecimal factorPercent;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
