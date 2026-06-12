package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.out.RateLimitPolicyDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * API contract + OpenAPI documentation for the public rate-limit policies
 * endpoint. The controller only implements these methods; all routing and
 * Swagger documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Storefront", description = "Public rate-limit policies (no auth required)")
public interface RateLimitDocsApi {

    @Operation(summary = "List all rate-limit policies", description = """
            Returns one entry per policy, including:
              - `name`     — internal identifier returned in `X-RateLimit-Policy` headers
              - `path`     — matched URI prefix
              - `scope`    — bucket key (per client_id, per IP, per shop)
              - `capacity` — max requests in the window
              - `period`   — window length

            Every API response also includes `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` so clients can react in real time without polling this endpoint.
            """)
    @GetMapping
    ResponseEntity<List<RateLimitPolicyDtoOut>> policies();
}
