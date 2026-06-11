package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.MeOrderPaymentApi;
import com.nexaplatform.dropshipping.api.dto.in.OrderPaymentIntentDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.OrderPaymentDtoOut;
import com.nexaplatform.dropshipping.application.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * DROP-442: pago de una orden por el dueño de la cuenta (B2C). Mismo motor que
 * PartnerPaymentController pero accedido vía cookie session (no JWT). Soporta
 * los 4 métodos: WALLET, CARD, PAYPAL, USDT.
 */
@RestController
@RequestMapping("/api/me/orders")
@RequiredArgsConstructor
public class MeOrderPaymentController implements MeOrderPaymentApi {

    private final PaymentService paymentService;

    @Override
    public ResponseEntity<OrderPaymentDtoOut> initiate(
            Authentication auth,
            UUID orderId,
            OrderPaymentIntentDtoIn req,
            String idempotencyKey) {
        UUID userId = UUID.fromString(auth.getName());
        return new ResponseEntity<>(
                paymentService.initiateMeOrderPayment(userId, orderId, req, idempotencyKey),
                HttpStatus.CREATED);
    }

    /**
     * Dev/mock-mode helper: marca un pago de orden como SUCCEEDED sin requerir
     * webhook real de Stripe/PayPal/Coinbase. Idempotente.
     */
    @Override
    public ResponseEntity<OrderPaymentDtoOut> confirmMock(
            Authentication auth,
            UUID orderId,
            UUID paymentId) {
        return ResponseEntity.ok(paymentService.confirmMockOrderPayment(orderId, paymentId));
    }
}
