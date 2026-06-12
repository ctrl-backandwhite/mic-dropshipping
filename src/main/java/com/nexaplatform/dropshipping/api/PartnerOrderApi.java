package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.PartnerDtos.CreateOrderRequest;
import com.nexaplatform.dropshipping.api.dto.out.PartnerOrderDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the partner orders resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Partner Orders")
@SecurityRequirement(name = "bearer-jwt")
public interface PartnerOrderApi {

    @Operation(summary = "Create a dropship order for the authenticated partner")
    @ApiResponse(responseCode = "201", description = "Order created")
    @PostMapping
    ResponseEntity<PartnerOrderDtoOut> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateOrderRequest req);

    @Operation(summary = "List orders belonging to the authenticated partner")
    @ApiResponse(responseCode = "200", description = "Orders listed")
    @GetMapping
    ResponseEntity<List<PartnerOrderDtoOut>> list(@AuthenticationPrincipal Jwt jwt);

    @Operation(summary = "Get an order by id")
    @ApiResponse(responseCode = "200", description = "Order found")
    @GetMapping("/{id}")
    ResponseEntity<PartnerOrderDtoOut> get(@PathVariable UUID id);
}
