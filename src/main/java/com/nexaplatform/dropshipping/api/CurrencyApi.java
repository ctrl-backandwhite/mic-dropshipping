package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.UpdateRateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.CurrencyDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CurrencySyncResultDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * API contract + OpenAPI documentation for the Currency resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Currency")
public interface CurrencyApi {

    @Operation(summary = "List active currency rates")
    @GetMapping({"/api/storefront/currency/rates", "/api/admin/currency/rates"})
    ResponseEntity<List<CurrencyDtoOut>> listActive();

    @Operation(summary = "Get a single currency by code")
    @GetMapping("/api/storefront/currency/{code}")
    ResponseEntity<CurrencyDtoOut> one(@PathVariable String code);

    @Operation(summary = "Override a currency rate and optional active flag")
    @PutMapping("/api/admin/currency/{code}")
    ResponseEntity<CurrencyDtoOut> updateRate(@PathVariable String code, @Valid @RequestBody UpdateRateDtoIn req);

    @Operation(summary = "Sync currency rates from the external provider")
    @PostMapping("/api/admin/currency/sync")
    ResponseEntity<CurrencySyncResultDtoOut> sync();
}
