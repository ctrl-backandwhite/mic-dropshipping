package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Pure domain model for a currency rate. Use cases operate on this model;
 * mappers translate to/from DtoIn/DtoOut (api) and the JPA entity (infra).
 * Currency rows are addressed by their ISO {@code code} from the API, while the
 * persistent identity stays the inherited {@code id}.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrencyRate {

    private UUID id;
    private String code;
    private String name;
    private String symbol;
    private String countryCode;
    private String flagEmoji;
    private String locale;
    private BigDecimal rateVsUsd;
    private boolean active;
    private Instant lastSyncedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
