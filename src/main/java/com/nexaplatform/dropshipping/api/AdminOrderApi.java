package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.out.AdminOrderDetailDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminOrderRowDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PartnerOrderDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the Admin Orders resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Admin Orders")
public interface AdminOrderApi {

    @Operation(summary = "List admin orders with paging and optional status/query filters")
    @ApiResponse(responseCode = "200", description = "Orders listed")
    @GetMapping
    ResponseEntity<PageResponse<AdminOrderRowDtoOut>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size);

    @Operation(summary = "Get admin order detail by id")
    @ApiResponse(responseCode = "200", description = "Order found")
    @GetMapping("/{id}")
    ResponseEntity<AdminOrderDetailDtoOut> detail(@PathVariable UUID id);

    @Operation(summary = "Forward an order to the supplier")
    @ApiResponse(responseCode = "200", description = "Order forwarded")
    @PostMapping("/{id}/forward")
    ResponseEntity<AdminOrderRowDtoOut> forward(@PathVariable UUID id);

    @Operation(summary = "Mark an order as shipped")
    @ApiResponse(responseCode = "200", description = "Order shipped")
    @PostMapping("/{id}/ship")
    ResponseEntity<AdminOrderRowDtoOut> ship(@PathVariable UUID id);

    @Operation(summary = "Mark an order as delivered")
    @ApiResponse(responseCode = "200", description = "Order delivered")
    @PostMapping("/{id}/deliver")
    ResponseEntity<AdminOrderRowDtoOut> deliver(@PathVariable UUID id);

    @Operation(summary = "Cancel an order")
    @ApiResponse(responseCode = "200", description = "Order cancelled")
    @PostMapping("/{id}/cancel")
    ResponseEntity<AdminOrderRowDtoOut> cancel(@PathVariable UUID id);

    @Operation(summary = "Refund an order")
    @ApiResponse(responseCode = "200", description = "Order refunded")
    @PostMapping("/{id}/refund")
    ResponseEntity<AdminOrderRowDtoOut> refund(@PathVariable UUID id);

    @Operation(summary = "Create a demo order")
    @ApiResponse(responseCode = "201", description = "Demo order created")
    @PostMapping("/demo")
    ResponseEntity<PartnerOrderDtoOut> createDemo();
}
