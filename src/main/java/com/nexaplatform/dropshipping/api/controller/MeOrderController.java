package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.MeOrderApi;
import com.nexaplatform.dropshipping.api.dto.in.MeCheckoutDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderDetailDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderRowDtoOut;
import com.nexaplatform.dropshipping.application.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Authenticated user's orders controller. Pure implementation of {@link MeOrderApi}:
 * no business logic and no manual mapping — delegates to {@link OrderService}
 * and wraps the result in a {@link ResponseEntity}.
 */
@RestController
@RequestMapping("/api/me/orders")
@RequiredArgsConstructor
public class MeOrderController implements MeOrderApi {

    private final OrderService orderService;

    @Override
    public ResponseEntity<MeOrderDetailDtoOut> checkout(Authentication auth, MeCheckoutDtoIn req, String idem) {
        UUID userId = UUID.fromString(auth.getName());
        return new ResponseEntity<>(orderService.checkout(userId, req, idem), HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<List<MeOrderRowDtoOut>> list(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(orderService.listMyOrders(userId));
    }

    @Override
    public ResponseEntity<MeOrderDetailDtoOut> detail(Authentication auth, UUID id, String lang) {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(orderService.getMyOrderDetail(userId, id, lang));
    }
}
