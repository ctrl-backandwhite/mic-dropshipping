package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.WebhookSubscriptionCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.WebhookSubscriptionUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.WebhookDeliveryDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.WebhookSubscriptionDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the Admin Webhooks subscriptions resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Admin Webhooks")
public interface AdminWebhooksApi {

    @Operation(summary = "List webhook subscriptions")
    @ApiResponse(responseCode = "200", description = "Subscriptions listed")
    @GetMapping
    ResponseEntity<List<WebhookSubscriptionDtoOut>> list();

    @Operation(summary = "Create a webhook subscription")
    @ApiResponse(responseCode = "201", description = "Subscription created")
    @PostMapping
    ResponseEntity<WebhookSubscriptionDtoOut> create(@Valid @RequestBody WebhookSubscriptionCreateDtoIn req);

    @Operation(summary = "Update a webhook subscription by id")
    @ApiResponse(responseCode = "200", description = "Subscription updated")
    @PutMapping("/{id}")
    ResponseEntity<WebhookSubscriptionDtoOut> update(@PathVariable UUID id,
                                                     @RequestBody WebhookSubscriptionUpdateDtoIn req);

    @Operation(summary = "Rotate the signing secret of a webhook subscription")
    @ApiResponse(responseCode = "200", description = "Secret rotated")
    @PostMapping("/{id}/rotate-secret")
    ResponseEntity<WebhookSubscriptionDtoOut> rotate(@PathVariable UUID id);

    @Operation(summary = "Delete a webhook subscription by id")
    @ApiResponse(responseCode = "204", description = "Subscription deleted")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id);

    @Operation(summary = "Fire a test delivery for a webhook subscription")
    @ApiResponse(responseCode = "200", description = "Test delivery fired")
    @PostMapping("/{id}/test")
    ResponseEntity<WebhookSubscriptionDtoOut> fireTest(@PathVariable UUID id);

    @Operation(summary = "List recent deliveries for a webhook subscription")
    @ApiResponse(responseCode = "200", description = "Deliveries listed")
    @GetMapping("/{id}/deliveries")
    ResponseEntity<List<WebhookDeliveryDtoOut>> deliveries(@PathVariable UUID id);
}
