package com.nexaplatform.dropshipping.domain.model;

import com.nexaplatform.dropshipping.domain.enums.MarginType;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleChannel;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Pure domain model for a pricing rule. Use cases operate on this model;
 * mappers translate to/from DtoIn/DtoOut (api) and the JPA entity (infra).
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceRule {

    private UUID id;
    private PriceRuleScope scope;
    private UUID scopeId;
    private MarginType marginType;
    private BigDecimal marginValue;
    private BigDecimal minCostUsd;
    private BigDecimal maxCostUsd;
    private boolean active;
    private int position;
    private String description;
    /** Canal al que aplica (STOREFRONT por defecto; INTEGRATION para apps API). */
    private PriceRuleChannel channel;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
