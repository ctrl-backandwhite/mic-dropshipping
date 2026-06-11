package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.PartnerOrderApi;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.CreateOrderRequest;
import com.nexaplatform.dropshipping.api.dto.out.PartnerOrderDtoOut;
import com.nexaplatform.dropshipping.application.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Partner orders controller. Pure implementation of {@link PartnerOrderApi}:
 * no business logic and no manual mapping — delegates to {@link OrderService}
 * (which resolves the partner from the JWT and maps to DTOs) and wraps the
 * result in a standardized {@link ResponseEntity}.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/partner/orders")
public class PartnerOrderController implements PartnerOrderApi {

    private final OrderService orderService;

    @Override
    public ResponseEntity<PartnerOrderDtoOut> create(Jwt jwt, CreateOrderRequest req) {
        return new ResponseEntity<>(orderService.createOrderForPartner(jwt, req), HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<List<PartnerOrderDtoOut>> list(Jwt jwt) {
        return new ResponseEntity<>(orderService.listForPartner(jwt), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<PartnerOrderDtoOut> get(UUID id) {
        return new ResponseEntity<>(orderService.getPartnerOrder(id), HttpStatus.OK);
    }
}
