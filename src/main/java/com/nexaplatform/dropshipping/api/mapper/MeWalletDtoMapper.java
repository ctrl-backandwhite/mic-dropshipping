package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.MeWalletDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletRechargeDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletTxDtoOut;
import com.nexaplatform.dropshipping.domain.model.Payment;
import com.nexaplatform.dropshipping.domain.model.Wallet;
import com.nexaplatform.dropshipping.domain.model.WalletTransaction;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper translating the wallet domain models into the transport DtoOut
 * classes for the authenticated user. Injected in the controller. The display
 * conversion fields are read-only enrichment filled by the use case; the recharge
 * provider metadata (clientSecret/approveUrl/crypto*) is projected from the
 * {@link Payment} provider response map.
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
    MeWalletDtoOut toWalletDtoOut(Wallet model);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "kind", source = "kind")
    @Mapping(target = "amountUsdCents", source = "amountUsdCents")
    @Mapping(target = "balanceAfterCents", source = "balanceAfterCents")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "paymentId", source = "paymentId")
    @Mapping(target = "orderId", source = "orderId")
    @Mapping(target = "createdAt", source = "createdAt")
    MeWalletTxDtoOut toTxDtoOut(WalletTransaction model);

    List<MeWalletTxDtoOut> toTxDtoOutList(List<WalletTransaction> models);

    @Mapping(target = "paymentId", source = "id")
    @Mapping(target = "method", expression = "java(model.getMethod() != null ? model.getMethod().name() : null)")
    @Mapping(target = "status", expression = "java(model.getStatus() != null ? model.getStatus().name() : null)")
    @Mapping(target = "amountUsdCents", source = "amountUsdCents")
    @Mapping(target = "provider", source = "provider")
    @Mapping(target = "providerRef", source = "providerRef")
    @Mapping(target = "clientSecret", expression = "java(meta(model, \"clientSecret\"))")
    @Mapping(target = "approveUrl", expression = "java(meta(model, \"approveUrl\"))")
    @Mapping(target = "cryptoAddress", expression = "java(meta(model, \"cryptoAddress\"))")
    @Mapping(target = "cryptoChain", expression = "java(meta(model, \"cryptoChain\"))")
    @Mapping(target = "qrUrl", expression = "java(meta(model, \"qrUrl\"))")
    @Mapping(target = "expiresAt", source = "cryptoExpiresAt")
    MeWalletRechargeDtoOut toRechargeDtoOut(Payment model);

    /** Reads a string value from the payment provider-response metadata map. */
    default String meta(Payment model, String key) {
        if (model == null || model.getProviderResponse() == null) {
            return null;
        }
        Object v = model.getProviderResponse().get(key);
        return v != null ? v.toString() : null;
    }
}
