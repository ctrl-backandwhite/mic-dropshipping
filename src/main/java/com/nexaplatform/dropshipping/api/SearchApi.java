package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.out.SearchResultDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * API contract + OpenAPI documentation for the Storefront Search resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Storefront Search")
public interface SearchApi {

    @Operation(summary = "Search products with typed results")
    @GetMapping
    ResponseEntity<SearchResultDtoOut> search(@RequestParam(required = false) String q,
                              @RequestParam(defaultValue = "es") String lang,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "24") int size);
}
