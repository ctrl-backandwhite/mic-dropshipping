package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.WalletDtos.RechargeResponse;
import com.nexaplatform.dropshipping.api.dto.WalletDtos.WalletTxView;
import com.nexaplatform.dropshipping.api.dto.WalletDtos.WalletView;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletRechargeDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletTxDtoOut;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * API-layer mapper translating the internal wallet view records (produced by
 * {@code WalletService}/{@code PaymentService}) into the transport DtoOut classes.
 * Every field is mapped explicitly. Replaces the controller returning the legacy
 * {@code WalletDtos.*} records directly.
 */
@Mapper(componentModel = "spring")
public interface MeWalletDtoMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "balanceUsdCents", source = "balanceUsdCents")
    @Mapping(target = "holdUsdCents", source = "holdUsdCents")
    @Mapping(target = "availableUsdCents", source = "availableUsdCents")
    @Mapping(target = "currencyDefault", source = "currencyDefault")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "balanceDisplay", source = "balanceDisplay")
    @Mapping(target = "displayCurrency", source = "displayCurrency")
    @Mapping(target = "displaySymbol", source = "displaySymbol")
    MeWalletDtoOut toWalletDtoOut(WalletView view);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "kind", source = "kind")
    @Mapping(target = "amountUsdCents", source = "amountUsdCents")
    @Mapping(target = "balanceAfterCents", source = "balanceAfterCents")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "paymentId", source = "paymentId")
    @Mapping(target = "orderId", source = "orderId")
    @Mapping(target = "createdAt", source = "createdAt")
    MeWalletTxDtoOut toTxDtoOut(WalletTxView view);

    @Mapping(target = "paymentId", source = "paymentId")
    @Mapping(target = "method", source = "method")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "amountUsdCents", source = "amountUsdCents")
    @Mapping(target = "provider", source = "provider")
    @Mapping(target = "providerRef", source = "providerRef")
    @Mapping(target = "clientSecret", source = "clientSecret")
    @Mapping(target = "approveUrl", source = "approveUrl")
    @Mapping(target = "cryptoAddress", source = "cryptoAddress")
    @Mapping(target = "cryptoChain", source = "cryptoChain")
    @Mapping(target = "qrUrl", source = "qrUrl")
    @Mapping(target = "expiresAt", source = "expiresAt")
    MeWalletRechargeDtoOut toRechargeDtoOut(RechargeResponse response);
}
