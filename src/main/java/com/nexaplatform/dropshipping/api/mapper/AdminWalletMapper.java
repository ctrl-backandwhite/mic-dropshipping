package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.AdminWalletTxResultDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminWalletTxRowDtoOut;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WalletTransactionEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

/**
 * MapStruct mapper for the Admin Wallets API boundary. Converts wallet
 * transaction entities into their DtoOut row/result shapes. The aggregated
 * wallet listing rows (with BigDecimal money conversion + user joins) are built
 * in {@code WalletService} since they require per-row computation.
 */
@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface AdminWalletMapper {

    @Mapping(target = "amountCents", source = "amountUsdCents")
    AdminWalletTxRowDtoOut toTxRow(WalletTransactionEntity tx);

    List<AdminWalletTxRowDtoOut> toTxRows(List<WalletTransactionEntity> txs);

    @Mapping(target = "transactionId", source = "id")
    @Mapping(target = "balanceAfter", source = "balanceAfterCents")
    @Mapping(target = "amountCents", source = "amountUsdCents")
    AdminWalletTxResultDtoOut toResult(WalletTransactionEntity tx);
}
