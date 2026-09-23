package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.PartnerOrderApi;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.CreateOrderRequest;
import com.nexaplatform.dropshipping.api.dto.out.PartnerOrderDtoOut;
import com.nexaplatform.dropshipping.api.mapper.PartnerOrderDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.OrderUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Partner orders controller. Pure implementation of {@link PartnerOrderApi}: injects
 * the use case (which resolves the partner from the JWT) and the DtoMapper; no business
 * logic and no manual mapping.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/partner/orders")
public class PartnerOrderController implements PartnerOrderApi {

    private final OrderUseCase orderUseCase;
    private final PartnerOrderDtoMapper partnerOrderDtoMapper;

    @Override
    public ResponseEntity<PartnerOrderDtoOut> create(Jwt jwt, CreateOrderRequest req) {
        return new ResponseEntity<>(partnerOrderDtoMapper.toDtoOut(orderUseCase.createOrderForPartner(jwt, req)),
                HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<List<PartnerOrderDtoOut>> list(Jwt jwt) {
        return new ResponseEntity<>(partnerOrderDtoMapper.toDtoOutList(orderUseCase.listForPartner(jwt)),
                HttpStatus.OK);
    }

    @Override
    public ResponseEntity<PartnerOrderDtoOut> get(Jwt jwt, UUID id) {
        return new ResponseEntity<>(partnerOrderDtoMapper.toDtoOut(orderUseCase.getPartnerOrder(jwt, id)),
                HttpStatus.OK);
    }
}
