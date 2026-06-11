package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import com.nexaplatform.dropshipping.domain.enums.MarginType;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "price_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceRuleEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PriceRuleScope scope;

    @Column(name = "scope_id", columnDefinition = "uuid")
    private UUID scopeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "margin_type", nullable = false, length = 20)
    private MarginType marginType;

    @Column(name = "margin_value", precision = 12, scale = 4, nullable = false)
    private BigDecimal marginValue;

    @Column(name = "min_cost_usd", precision = 12, scale = 4)
    private BigDecimal minCostUsd;

    @Column(name = "max_cost_usd", precision = 12, scale = 4)
    private BigDecimal maxCostUsd;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private int position;

    @Column(length = 300)
    private String description;
}
