package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A wallet row in the admin wallets listing. Field names mirror the keys the
 * controller previously placed into its per-row {@code Map}.
 */
@Value
@Builder
public class AdminWalletRowDtoOut {

    UUID id;
    UUID userId;
    String email;
    String name;
    BigDecimal balanceUsd;
    BigDecimal holdUsd;
    String currency;
    String status;
}
