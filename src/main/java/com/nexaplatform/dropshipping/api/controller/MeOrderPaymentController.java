package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.MeOrderPaymentApi;
import com.nexaplatform.dropshipping.api.dto.in.OrderPaymentIntentDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.SavedCardPayDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.OrderPaymentDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SavedCardPayDtoOut;
import com.nexaplatform.dropshipping.api.mapper.OrderPaymentDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.PaymentUseCase;
import com.stripe.exception.StripeException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * DROP-442: pago de una orden por el dueño de la cuenta (B2C). Pure implementation of
 * {@link MeOrderPaymentApi}: injects the use case + DtoMapper; no business logic.
 * Soporta los 4 métodos: WALLET, CARD, PAYPAL, USDT.
 */
@RestController
@RequestMapping("/api/me/orders")
@RequiredArgsConstructor
public class MeOrderPaymentController implements MeOrderPaymentApi {

    private final PaymentUseCase paymentUseCase;
    private final OrderPaymentDtoMapper orderPaymentDtoMapper;

    @Override
    public ResponseEntity<OrderPaymentDtoOut> initiate(Authentication auth, UUID orderId, OrderPaymentIntentDtoIn req,
            String idempotencyKey) {
        UUID userId = UUID.fromString(auth.getName());
        return new ResponseEntity<>(orderPaymentDtoMapper.toDtoOut(paymentUseCase.initiateMeOrderPayment(userId,
                orderId, req.isWallet(), req.isWallet() ? null : req.toPaymentMethod(), idempotencyKey)),
                HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<OrderPaymentDtoOut> confirmMock(Authentication auth, UUID orderId, UUID paymentId) {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity
                .ok(orderPaymentDtoMapper.toDtoOut(paymentUseCase.confirmMockOrderPayment(userId, orderId, paymentId)));
    }

    @Override
    public ResponseEntity<OrderPaymentDtoOut> confirm(Authentication auth, UUID orderId, UUID paymentId) {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity
                .ok(orderPaymentDtoMapper.toDtoOut(paymentUseCase.confirmOrderPayment(userId, orderId, paymentId)));
    }

    @Override
    public ResponseEntity<SavedCardPayDtoOut> paySavedCard(Authentication auth, UUID orderId, SavedCardPayDtoIn req,
            String idempotencyKey) throws StripeException {
        UUID userId = UUID.fromString(auth.getName());
        PaymentUseCase.SavedCardPayResult r = paymentUseCase.payOrderWithSavedCard(userId, orderId,
                req.getPaymentMethodId(), idempotencyKey);
        return new ResponseEntity<>(new SavedCardPayDtoOut(r.status(), r.clientSecret(), r.paymentId()),
                HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<OrderPaymentDtoOut> confirmSavedCard(Authentication auth, UUID orderId, UUID paymentId)
            throws StripeException {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(orderPaymentDtoMapper
                .toDtoOut(paymentUseCase.confirmSavedCardPayment(userId, orderId, paymentId)));
    }
}
