package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.AlertRequestDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AlertViewDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.TrendRowDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.WinningProductDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the Intelligence endpoints (DROP-8):
 * ad trends, sales trends, winning products and alerts. The controller only
 * implements these methods; all routing and Swagger documentation live here
 * (springdoc "API interface" pattern).
 */
@Tag(name = "Intelligence")
public interface IntelligenceApi {

    @Operation(summary = "List ad trends, optionally filtered by source")
    @GetMapping("/ad-trends")
    List<TrendRowDtoOut> adTrends(@RequestParam(required = false) String source,
            @RequestParam(defaultValue = "30") int limit);

    @Operation(summary = "List sales trends (best-selling products), optionally filtered by category")
    @GetMapping("/sales-trends")
    List<WinningProductDtoOut> salesTrends(@RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "es") String lang);

    @Operation(summary = "List winning products ranked by trend score")
    @GetMapping("/winning-products")
    List<WinningProductDtoOut> winning(@RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "es") String lang);

    @Operation(summary = "List the current user's active intelligence alerts")
    @GetMapping("/alerts")
    List<AlertViewDtoOut> alerts(Authentication auth);

    @Operation(summary = "Create an intelligence alert for the current user")
    @PostMapping("/alerts")
    AlertViewDtoOut createAlert(Authentication auth, @Valid @RequestBody AlertRequestDtoIn req);

    @Operation(summary = "Delete (deactivate) an intelligence alert by id")
    @DeleteMapping("/alerts/{id}")
    void deleteAlert(@PathVariable UUID id);
}
