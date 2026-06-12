package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Wallet detail for the admin wallet detail view (DROP-633). Carries the wallet
 * id (used by the client to fetch the transactions page), the owner enrichment
 * and the three money figures the panel shows: available, held and total
 * balance, all in USD (the canonical wallet currency) as 2 dp BigDecimal.
 */
@Value
@Builder
public class AdminWalletDetailDtoOut {

    UUID id;
    UUID userId;
    String email;
    String name;
    BigDecimal balanceUsd;
    BigDecimal holdUsd;
    BigDecimal availableUsd;
    String currency;
    String status;
}
