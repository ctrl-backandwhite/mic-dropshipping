package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the inbound shop webhook resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Partner · Shop", description = "Inbound order webhook signed with shop connection secret")
@SecurityRequirement(name = "webhookHmac")
public interface InboundShopWebhookApi {

    @PostMapping(path = "/{shopId}/orders", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Inbound order from a connected shop", description = "Your e-commerce posts the customer's order here. NexaDrop verifies the HMAC, "
            + "maps the payload to its canonical order shape and creates the dropship order. "
            + "Replay-safe via the `Idempotency-Key` header (24 h window).", requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = Object.class), examples = {
                    @ExampleObject(name = "Canonical", value = """
                            {
                              "externalOrderId": "shop-1234",
                              "items": [
                                { "productId": "9c0e2c1c-...-...", "variantId": null, "quantity": 2 }
                              ],
                              "shippingAddress": {
                                "fullName": "Ada Lovelace", "line1": "1 Babbage Way",
                                "city": "London", "postalCode": "EC1A1", "country": "GB"
                              },
                              "notes": "Leave at door"
                            }
                            """), @ExampleObject(name = "Shopify-like", value = """
                            {
                              "id": "shop-1234",
                              "line_items": [
                                { "sku": "NX-SKU-001", "quantity": 2 }
                              ],
                              "shipping_address": {
                                "name": "Ada Lovelace", "address1": "1 Babbage Way",
                                "city": "London", "zip": "EC1A1", "country_code": "GB"
                              }
                            }
                            """)})))
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Order created"),
            @ApiResponse(responseCode = "401", description = "Signature missing or invalid"),
            @ApiResponse(responseCode = "404", description = "Unknown shop connection"),
            @ApiResponse(responseCode = "409", description = "Replay of an order already accepted (idempotency hit)"),
            @ApiResponse(responseCode = "422", description = "Cannot map at least one line item to a NexaDrop product")})
    ResponseEntity<OrderView> receiveOrder(@PathVariable UUID shopId,
            @Parameter(description = "Hex HMAC-SHA256 of the raw body, signed with the shop connection's inbound secret.", required = true) @RequestHeader("X-NX-Signature") String signature,
            @Parameter(description = "Optional external order id used for deduplication (24 h window).") @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody byte[] rawBody);
}
