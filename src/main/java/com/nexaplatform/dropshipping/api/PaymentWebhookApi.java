package com.nexaplatform.dropshipping.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * API contract + OpenAPI documentation for the payment provider webhooks.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Payment Webhooks", description = "Signed payment provider callbacks (Stripe, PayPal, Coinbase)")
public interface PaymentWebhookApi {

    @Operation(summary = "Receive a Stripe webhook event (signature verified)")
    @PostMapping(value = "/stripe", consumes = "application/json")
    ResponseEntity<String> stripe(@RequestBody String payload,
                                  @RequestHeader(value = "Stripe-Signature", required = false) String sig);

    @Operation(summary = "Receive a PayPal webhook event (HMAC verified)")
    @PostMapping("/paypal")
    ResponseEntity<String> paypal(@RequestBody String payload,
                                  @RequestHeader(value = "PayPal-Transmission-Sig", required = false) String sig);

    @Operation(summary = "Receive a Coinbase Commerce webhook event (HMAC verified)")
    @PostMapping("/coinbase")
    ResponseEntity<String> coinbase(@RequestBody String payload,
                                    @RequestHeader(value = "X-CC-Webhook-Signature", required = false) String sig);
}
