package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Single wallet transaction row for the authenticated user. Replaces the legacy
 * {@code WalletDtos.WalletTxView} record; field names are kept identical.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeWalletTxDtoOut {

    @Schema(description = "Transaction id")
    private UUID id;

    @Schema(description = "Transaction kind")
    private String kind;

    @Schema(description = "Signed amount in USD cents")
    private long amountUsdCents;

    @Schema(description = "Resulting balance in USD cents")
    private long balanceAfterCents;

    @Schema(description = "Transaction status")
    private String status;

    @Schema(description = "Human-readable description")
    private String description;

    @Schema(description = "Related payment id, if any")
    private UUID paymentId;

    @Schema(description = "Related order id, if any")
    private UUID orderId;

    @Schema(description = "Creation timestamp")
    private Instant createdAt;

    @Schema(description = "Signed amount (USD) formatted by the backend (country convention)")
    private String amountFormatted;

    @Schema(description = "Balance after (USD) formatted by the backend (country convention)")
    private String balanceAfterFormatted;
}
