package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.MeWalletApi;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.in.MeWalletRechargeDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletPaymentStatusDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletRechargeDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletTxDtoOut;
import com.nexaplatform.dropshipping.application.service.PaymentService;
import com.nexaplatform.dropshipping.application.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Authenticated user's wallet controller. Pure implementation of {@link MeWalletApi}:
 * no business logic and no manual mapping — delegates to {@link WalletService}/
 * {@link PaymentService} and wraps the result in a {@link ResponseEntity}.
 */
@RestController
@RequestMapping("/api/me/wallet")
@RequiredArgsConstructor
public class MeWalletController implements MeWalletApi {

    private final WalletService walletService;
    private final PaymentService paymentService;

    @Override
    public ResponseEntity<MeWalletDtoOut> wallet(Authentication auth) {
        return ResponseEntity.ok(walletService.getWalletDtoOut(UUID.fromString(auth.getName())));
    }

    @Override
    public ResponseEntity<PageResponse<MeWalletTxDtoOut>> transactions(Authentication auth, int page, int size) {
        return ResponseEntity.ok(walletService.getMyTransactionDtos(UUID.fromString(auth.getName()), page, size));
    }

    @Override
    public ResponseEntity<MeWalletRechargeDtoOut> recharge(Authentication auth, MeWalletRechargeDtoIn req, String idem) {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(paymentService.rechargeWalletDto(userId, req, idem));
    }

    @Override
    public ResponseEntity<MeWalletPaymentStatusDtoOut> capturePayPal(UUID paymentId) {
        return ResponseEntity.ok(paymentService.capturePayPalResult(paymentId));
    }

    @Override
    public ResponseEntity<MeWalletPaymentStatusDtoOut> confirmMock(Authentication auth, UUID paymentId) {
        return ResponseEntity.ok(paymentService.confirmMockRecharge(UUID.fromString(auth.getName()), paymentId));
    }
}
