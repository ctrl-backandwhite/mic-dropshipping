package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.MeCheckoutDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderDetailDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderRowDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the authenticated user's Orders resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "My Orders")
public interface MeOrderApi {

    @Operation(summary = "Checkout and create an order for the authenticated user")
    @ApiResponse(responseCode = "201", description = "Order created")
    @PostMapping("/checkout")
    ResponseEntity<MeOrderDetailDtoOut> checkout(Authentication auth, @Valid @RequestBody MeCheckoutDtoIn req,
            @Parameter(description = "Clave del INTENTO de compra, no de la petición: la misma en los "
                    + "reintentos del mismo carrito, distinta al comprar otra cosa. Sin ella cada POST "
                    + "crearía un pedido nuevo, así que un doble clic sería un segundo cobro.",
                    required = true) @RequestHeader(value = "Idempotency-Key", required = false) String idem);

    @Operation(summary = "List the authenticated user's orders")
    @ApiResponse(responseCode = "200", description = "Orders listed")
    @GetMapping
    ResponseEntity<List<MeOrderRowDtoOut>> list(Authentication auth);

    @Operation(summary = "Get the detail of one of the authenticated user's orders")
    @ApiResponse(responseCode = "200", description = "Order found")
    @GetMapping("/{id}")
    ResponseEntity<MeOrderDetailDtoOut> detail(Authentication auth, @PathVariable UUID id,
            @RequestParam(defaultValue = "es") String lang);

    @Operation(summary = "Cancel the authenticated user's order (only while PAID, not yet forwarded) and refund")
    @ApiResponse(responseCode = "200", description = "Order cancelled and refunded")
    @PostMapping("/{id}/cancel")
    ResponseEntity<MeOrderDetailDtoOut> cancel(Authentication auth, @PathVariable UUID id,
            @RequestParam(defaultValue = "es") String lang,
            @RequestParam(defaultValue = "true") boolean refundToWallet);
}
