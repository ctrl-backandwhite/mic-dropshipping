package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.CurrencyBulkActiveDtoIn;
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
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * API contract + OpenAPI documentation for the Currency resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Currency")
public interface CurrencyApi {

    @Operation(summary = "List active currency rates")
    @GetMapping({"/api/currency/rates", "/api/admin/currency/rates"})
    ResponseEntity<List<CurrencyDtoOut>> listActive();

    @Operation(summary = "List ALL currencies (active and inactive) — admin")
    @GetMapping("/api/admin/currency/all")
    ResponseEntity<List<CurrencyDtoOut>> listAll();

    @Operation(summary = "Toggle a currency active flag without changing its rate — admin")
    @PutMapping("/api/admin/currency/{code}/active")
    ResponseEntity<CurrencyDtoOut> setActive(@PathVariable String code, @RequestParam boolean active);

    @Operation(summary = "Activate/deactivate several currencies at once — admin")
    @PutMapping("/api/admin/currency/bulk-active")
    ResponseEntity<Map<String, Object>> bulkActive(@Valid @RequestBody CurrencyBulkActiveDtoIn req);

    @Operation(summary = "Get a single currency by code")
    @GetMapping("/api/currency/{code}")
    ResponseEntity<CurrencyDtoOut> one(@PathVariable String code);

    @Operation(summary = "Override a currency rate and optional active flag")
    @PutMapping("/api/admin/currency/{code}")
    ResponseEntity<CurrencyDtoOut> updateRate(@PathVariable String code, @Valid @RequestBody UpdateRateDtoIn req);

    @Operation(summary = "Sync currency rates from the external provider")
    @PostMapping("/api/admin/currency/sync")
    ResponseEntity<CurrencySyncResultDtoOut> sync();
}
