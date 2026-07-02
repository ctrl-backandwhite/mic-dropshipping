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
 * Pure domain model for a user wallet (aggregate root). Carries the flattened
 * {@code userId} (resolved to the managed user relation by the repository
 * adapter) plus the read-only enrichment fields ({@code userEmail},
 * {@code userName}) the admin listing exposes, filled by the use case.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Wallet {

    private UUID id;
    private UUID userId;
    private long balanceUsdCents;
    private long holdUsdCents;
    private String currencyDefault;
    private String status;

    // Read-only enrichment (filled by the use case from the user relation).
    private String userEmail;
    private String userName;

    // Read-only enrichment for the "me wallet" snapshot, filled by the use case:
    // available balance plus the display-currency conversion.
    private long availableUsdCents;
    private BigDecimal balanceDisplay;
    private String displayCurrency;
    private String displaySymbol;
    // Importes YA formateados por el backend con la convención del país del visor (el front solo pinta):
    // saldo en la divisa activa, saldo canónico en USD y saldo retenido en USD.
    private String balanceFormatted;
    private String balanceUsdFormatted;
    private String holdUsdFormatted;

    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
