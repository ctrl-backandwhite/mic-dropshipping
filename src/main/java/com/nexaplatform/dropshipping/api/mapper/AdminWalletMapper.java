package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.AdminWalletRowDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminWalletTxResultDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminWalletTxRowDtoOut;
import com.nexaplatform.dropshipping.domain.model.Wallet;
import com.nexaplatform.dropshipping.domain.model.WalletTransaction;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for the Admin Wallets boundary. Translates the wallet domain
 * models into their DtoOut row/result shapes. Injected in the controller. The
 * money fields are converted to BigDecimal (2 dp); the user join data is carried
 * on the {@link Wallet} model as read-only enrichment filled by the use case.
 */
@Mapper(componentModel = "spring")
public interface AdminWalletMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "kind", source = "kind")
    @Mapping(target = "amountCents", source = "amountUsdCents")
    @Mapping(target = "balanceAfterCents", source = "balanceAfterCents")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "createdAt", source = "createdAt")
    AdminWalletTxRowDtoOut toTxRow(WalletTransaction tx);

    List<AdminWalletTxRowDtoOut> toTxRows(List<WalletTransaction> txs);

    @Mapping(target = "transactionId", source = "id")
    @Mapping(target = "balanceAfter", source = "balanceAfterCents")
    @Mapping(target = "amountCents", source = "amountUsdCents")
    AdminWalletTxResultDtoOut toResult(WalletTransaction tx);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "userId", source = "userId")
    @Mapping(target = "email", source = "userEmail")
    @Mapping(target = "name", source = "userName")
    @Mapping(target = "balanceUsd", expression = "java(java.math.BigDecimal.valueOf(wallet.getBalanceUsdCents()).divide(java.math.BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP))")
    @Mapping(target = "holdUsd", expression = "java(java.math.BigDecimal.valueOf(wallet.getHoldUsdCents()).divide(java.math.BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP))")
    @Mapping(target = "currency", expression = "java(wallet.getCurrencyDefault() != null ? wallet.getCurrencyDefault() : \"USD\")")
    @Mapping(target = "status", source = "status")
    AdminWalletRowDtoOut toRow(Wallet wallet);

    List<AdminWalletRowDtoOut> toRows(List<Wallet> wallets);
}
