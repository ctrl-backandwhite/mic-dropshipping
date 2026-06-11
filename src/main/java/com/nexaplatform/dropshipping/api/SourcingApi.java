package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.SourcingCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.SourcingQuoteDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.SourcingAgentDetailDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SourcingAgentLiteDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SourcingQuoteDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SourcingRequestDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
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
 * API contract + OpenAPI documentation for the Sourcing resource (requests,
 * agents marketplace and competing quotes). The controller only implements
 * these methods; all routing and Swagger documentation live here (springdoc
 * "API interface" pattern).
 */
@Tag(name = "Sourcing")
public interface SourcingApi {

    @Operation(summary = "List the authenticated user's sourcing requests")
    @GetMapping("/requests")
    ResponseEntity<List<SourcingRequestDtoOut>> myRequests(Authentication auth);

    @Operation(summary = "Create a sourcing request")
    @PostMapping("/requests")
    ResponseEntity<SourcingRequestDtoOut> create(Authentication auth, @Valid @RequestBody SourcingCreateDtoIn body);

    @Operation(summary = "Get a sourcing request by id")
    @GetMapping("/requests/{id}")
    ResponseEntity<SourcingRequestDtoOut> detail(Authentication auth, @PathVariable UUID id);

    @Operation(summary = "Cancel a sourcing request")
    @PostMapping("/requests/{id}/cancel")
    ResponseEntity<SourcingRequestDtoOut> cancel(Authentication auth, @PathVariable UUID id);

    @Operation(summary = "Delete a sourcing request")
    @DeleteMapping("/requests/{id}")
    ResponseEntity<Void> delete(Authentication auth, @PathVariable UUID id);

    @Operation(summary = "List the quotes of a sourcing request")
    @GetMapping("/requests/{id}/quotes")
    ResponseEntity<List<SourcingQuoteDtoOut>> quotes(Authentication auth, @PathVariable UUID id);

    @Operation(summary = "Submit a quote for a sourcing request")
    @PostMapping("/requests/{id}/quotes")
    ResponseEntity<SourcingQuoteDtoOut> submitQuote(@PathVariable UUID id, @Valid @RequestBody SourcingQuoteDtoIn q,
                          @RequestParam(required = false) UUID asAgent);

    @Operation(summary = "Select the winning quote for a sourcing request")
    @PostMapping("/requests/{id}/select-quote/{quoteId}")
    ResponseEntity<SourcingRequestDtoOut> selectQuote(Authentication auth, @PathVariable UUID id, @PathVariable UUID quoteId);

    @Operation(summary = "List the active sourcing agents")
    @GetMapping("/agents")
    ResponseEntity<List<SourcingAgentLiteDtoOut>> agentsList();

    @Operation(summary = "Get a sourcing agent profile by id")
    @GetMapping("/agents/{id}")
    ResponseEntity<SourcingAgentDetailDtoOut> agentDetail(@PathVariable UUID id);
}
