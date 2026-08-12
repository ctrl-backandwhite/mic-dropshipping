package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.PartnerPaymentApi;
import com.nexaplatform.dropshipping.api.dto.in.OrderPaymentIntentDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.OrderPaymentDtoOut;
import com.nexaplatform.dropshipping.api.mapper.OrderPaymentDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.PaymentUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Pago de una orden dropship desde la plataforma del partner. Pure implementation of
 * {@link PartnerPaymentApi}: injects the use case + DtoMapper; no business logic.
 *
 * Cuatro métodos soportados (WALLET inmediato a PAID; CARD/PAYPAL/USDT quedan PENDING
 * hasta el webhook del proveedor). Idempotency-Key honored por el use case.
 */
@RestController
@RequestMapping("/api/v1/partner/orders")
@RequiredArgsConstructor
public class PartnerPaymentController implements PartnerPaymentApi {

    private final PaymentUseCase paymentUseCase;
    private final OrderPaymentDtoMapper orderPaymentDtoMapper;

    @Override
    public ResponseEntity<OrderPaymentDtoOut> initiate(Jwt jwt, UUID orderId, OrderPaymentIntentDtoIn req,
            String idempotencyKey) {
        return new ResponseEntity<>(orderPaymentDtoMapper.toDtoOut(paymentUseCase.initiatePartnerOrderPayment(jwt,
                orderId, req.isWallet(), req.isWallet() ? null : req.toPaymentMethod(), idempotencyKey)),
                HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<List<OrderPaymentDtoOut>> list(Jwt jwt, UUID orderId) {
        return ResponseEntity.ok(
                orderPaymentDtoMapper.toDtoOutList(paymentUseCase.listOrderPaymentsForPartner(jwt, orderId)));
    }

    @Override
    public ResponseEntity<OrderPaymentDtoOut> get(Jwt jwt, UUID orderId, UUID paymentId) {
        return ResponseEntity.ok(
                orderPaymentDtoMapper.toDtoOut(paymentUseCase.getOrderPaymentForPartner(jwt, orderId, paymentId)));
    }
}
