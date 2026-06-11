package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminWebhooksApi;
import com.nexaplatform.dropshipping.api.dto.in.WebhookSubscriptionCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.WebhookSubscriptionUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.WebhookDeliveryDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.WebhookSubscriptionDtoOut;
import com.nexaplatform.dropshipping.application.service.AdminWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin Webhooks subscriptions controller. Pure implementation of
 * {@link AdminWebhooksApi}: no business logic and no manual mapping —
 * delegates to {@link AdminWebhookService} and wraps every result in a
 * standardized {@link ResponseEntity}.
 */
@RestController
@RequestMapping("/api/admin/webhooks/subscriptions")
@RequiredArgsConstructor
public class AdminWebhooksController implements AdminWebhooksApi {

    private final AdminWebhookService service;

    @Override
    public ResponseEntity<List<WebhookSubscriptionDtoOut>> list() {
        return new ResponseEntity<>(service.list(), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<WebhookSubscriptionDtoOut> create(WebhookSubscriptionCreateDtoIn req) {
        return new ResponseEntity<>(service.create(req), HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<WebhookSubscriptionDtoOut> update(UUID id, WebhookSubscriptionUpdateDtoIn req) {
        return new ResponseEntity<>(service.update(id, req), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<WebhookSubscriptionDtoOut> rotate(UUID id) {
        return new ResponseEntity<>(service.rotate(id), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<Void> delete(UUID id) {
        service.delete(id);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @Override
    public ResponseEntity<WebhookSubscriptionDtoOut> fireTest(UUID id) {
        return new ResponseEntity<>(service.fireTest(id), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<List<WebhookDeliveryDtoOut>> deliveries(UUID id) {
        return new ResponseEntity<>(service.deliveries(id), HttpStatus.OK);
    }
}
