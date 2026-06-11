package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminWebhooksApi;
import com.nexaplatform.dropshipping.api.dto.in.WebhookSubscriptionCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.WebhookSubscriptionUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.WebhookDeliveryDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.WebhookSubscriptionDtoOut;
import com.nexaplatform.dropshipping.api.mapper.WebhookSubscriptionDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.WebhookSubscriptionUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin Webhooks subscriptions controller. Pure implementation of
 * {@link AdminWebhooksApi}: no business logic and no manual mapping — each
 * method maps the request DTO to the domain model, delegates to
 * {@link WebhookSubscriptionUseCase}, maps the result back to a DtoOut and wraps
 * it in a standardized {@link ResponseEntity}.
 */
@RestController
@RequestMapping("/api/admin/webhooks/subscriptions")
@RequiredArgsConstructor
public class AdminWebhooksController implements AdminWebhooksApi {

    private final WebhookSubscriptionDtoMapper mapper;
    private final WebhookSubscriptionUseCase useCase;

    @Override
    public ResponseEntity<List<WebhookSubscriptionDtoOut>> list() {
        return new ResponseEntity<>(mapper.toDtoOutList(useCase.findAll()), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<WebhookSubscriptionDtoOut> create(WebhookSubscriptionCreateDtoIn req) {
        return new ResponseEntity<>(mapper.toDtoOut(useCase.save(mapper.toDomain(req))), HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<WebhookSubscriptionDtoOut> update(UUID id, WebhookSubscriptionUpdateDtoIn req) {
        return new ResponseEntity<>(mapper.toDtoOut(useCase.update(mapper.toDomain(req), id)), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<WebhookSubscriptionDtoOut> rotate(UUID id) {
        return new ResponseEntity<>(mapper.toDtoOut(useCase.rotate(id)), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<Void> delete(UUID id) {
        useCase.delete(id);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @Override
    public ResponseEntity<WebhookSubscriptionDtoOut> fireTest(UUID id) {
        return new ResponseEntity<>(mapper.toDtoOut(useCase.fireTest(id)), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<List<WebhookDeliveryDtoOut>> deliveries(UUID id) {
        return new ResponseEntity<>(mapper.toDeliveryDtoOutList(useCase.deliveries(id)), HttpStatus.OK);
    }
}
