package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.MeWalletApi;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.in.MeWalletRechargeDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletPaymentStatusDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletRechargeDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletTxDtoOut;
import com.nexaplatform.dropshipping.api.mapper.MeWalletDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.PaymentUseCase;
import com.nexaplatform.dropshipping.application.usecase.RechargeOptions;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.model.Payment;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Authenticated user's wallet controller. Pure implementation of {@link MeWalletApi}:
 * injects the use cases + DtoMapper; no business logic and no manual field mapping.
 */
@RestController
@RequestMapping("/api/me/wallet")
@RequiredArgsConstructor
public class MeWalletController implements MeWalletApi {

    private final WalletUseCase walletUseCase;
    private final PaymentUseCase paymentUseCase;
    private final MeWalletDtoMapper meWalletDtoMapper;
    private final CurrencyRateService currencyRateService;

    @Override
    public ResponseEntity<MeWalletDtoOut> wallet(Authentication auth) {
        return ResponseEntity
                .ok(meWalletDtoMapper.toWalletDtoOut(walletUseCase.getMyWallet(UUID.fromString(auth.getName()))));
    }

    @Override
    public ResponseEntity<PageResponse<MeWalletTxDtoOut>> transactions(Authentication auth, int page, int size) {
        UUID userId = UUID.fromString(auth.getName());
        int capped = Math.min(size, 100);
        List<MeWalletTxDtoOut> items = meWalletDtoMapper
                .toTxDtoOutList(walletUseCase.getMyTransactions(userId, page, capped));
        long total = walletUseCase.countMyTransactions(userId);
        var pageable = PageRequest.of(page, capped);
        return ResponseEntity.ok(PageResponse.from(new PageImpl<>(items, pageable, total)));
    }

    @Override
    public ResponseEntity<MeWalletRechargeDtoOut> recharge(Authentication auth, MeWalletRechargeDtoIn req,
            String idem) {
        UUID userId = UUID.fromString(auth.getName());
        Payment p = paymentUseCase.initiateRecharge(userId, PaymentMethod.valueOf(req.getMethod()),
                req.getAmountUsdCents(), req.getCurrencyDisplay(), req.getAmountDisplay(), idem, req.getCryptoChain());
        MeWalletRechargeDtoOut dto = meWalletDtoMapper.toRechargeDtoOut(p);
        // Importe de cobro formateado por el backend en su moneda (EUR/USD): lo que se cargará realmente.
        dto.setChargeCurrency(p.getSettlementCurrency());
        if (p.getSettlementAmount() != null && p.getSettlementCurrency() != null) {
            dto.setChargeFormatted(currencyRateService.formatDisplay(p.getSettlementAmount(), p.getSettlementCurrency()));
        }
        return ResponseEntity.ok(dto);
    }

    @Override
    public ResponseEntity<RechargeOptions> rechargeOptions(
            String currency) {
        return ResponseEntity.ok(paymentUseCase.rechargeOptions(currency));
    }

    @Override
    public ResponseEntity<MeWalletPaymentStatusDtoOut> confirmRecharge(Authentication auth, UUID paymentId) {
        UUID userId = UUID.fromString(auth.getName());
        Payment p = paymentUseCase.confirmRecharge(userId, paymentId);
        return ResponseEntity.ok(MeWalletPaymentStatusDtoOut.builder().paymentId(p.getId()).status(p.getStatus().name())
                .balanceUsdCents(walletUseCase.getOrCreate(userId).getBalanceUsdCents()).build());
    }

    @Override
    public ResponseEntity<MeWalletPaymentStatusDtoOut> capturePayPal(UUID paymentId) {
        Payment p = paymentUseCase.capturePayPal(paymentId);
        return ResponseEntity
                .ok(MeWalletPaymentStatusDtoOut.builder().paymentId(p.getId()).status(p.getStatus().name()).build());
    }

    @Override
    public ResponseEntity<MeWalletPaymentStatusDtoOut> confirmMock(Authentication auth, UUID paymentId) {
        UUID userId = UUID.fromString(auth.getName());
        Payment p = paymentUseCase.confirmMockRecharge(userId, paymentId);
        return ResponseEntity.ok(MeWalletPaymentStatusDtoOut.builder().paymentId(p.getId()).status(p.getStatus().name())
                .balanceUsdCents(walletUseCase.getOrCreate(userId).getBalanceUsdCents()).build());
    }
}
