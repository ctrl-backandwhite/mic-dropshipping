package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.PartnerPaymentApi;
import com.nexaplatform.dropshipping.api.dto.in.OrderPaymentIntentDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.OrderPaymentDtoOut;
import com.nexaplatform.dropshipping.application.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Pago de una orden dropship desde la plataforma del partner.
 *
 * Cuatro métodos soportados:
 *   - WALLET → debita la wallet NX036 del partner (atómico, inmediato)
 *   - CARD   → Stripe PaymentIntent → devuelve clientSecret para Stripe.js
 *   - PAYPAL → PayPal OrderV2     → devuelve approveUrl
 *   - USDT   → Crypto deposit     → devuelve cryptoAddress + qrUrl + cryptoChain
 *
 * En CARD/PAYPAL/USDT la orden queda en estado PENDING hasta que el webhook
 * del proveedor confirma. Para WALLET la orden pasa a PAID inmediatamente.
 *
 * Idempotency-Key (header opcional pero recomendado): la misma key devuelve
 * el mismo Payment sin volver a llamar al proveedor.
 */
@RestController
@RequestMapping("/api/v1/partner/orders")
@RequiredArgsConstructor
public class PartnerPaymentController implements PartnerPaymentApi {

    private final PaymentService paymentService;

    @Override
    public ResponseEntity<OrderPaymentDtoOut> initiate(
            Jwt jwt,
            UUID orderId,
            OrderPaymentIntentDtoIn req,
            String idempotencyKey) {
        return new ResponseEntity<>(
                paymentService.initiatePartnerOrderPayment(jwt, orderId, req, idempotencyKey),
                HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<List<OrderPaymentDtoOut>> list(UUID orderId) {
        return ResponseEntity.ok(paymentService.listOrderPayments(orderId));
    }

    @Override
    public ResponseEntity<OrderPaymentDtoOut> get(UUID orderId, UUID paymentId) {
        return ResponseEntity.ok(paymentService.getOrderPayment(orderId, paymentId));
    }
}
