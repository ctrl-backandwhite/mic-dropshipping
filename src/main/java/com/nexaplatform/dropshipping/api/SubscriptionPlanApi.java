package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.SubscriptionPlanDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.SubscriptionPlanDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the Subscription Plans resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern). Extends the
 * generic {@link BaseApi} CRUD contract.
 */
@Tag(name = "Admin · Subscription Plans", description = "CRUD operations for subscription plans")
public interface SubscriptionPlanApi extends BaseApi<SubscriptionPlanDtoIn, SubscriptionPlanDtoOut, UUID> {

    @Override
    @Operation(summary = "Create a subscription plan")
    @ApiResponse(responseCode = "201", description = "Plan created")
    @PostMapping
    ResponseEntity<SubscriptionPlanDtoOut> create(@Valid @RequestBody SubscriptionPlanDtoIn dto);

    @Override
    @Operation(summary = "Update a subscription plan by id")
    @ApiResponse(responseCode = "200", description = "Plan updated")
    @PutMapping("/{id}")
    ResponseEntity<SubscriptionPlanDtoOut> update(@Valid @RequestBody SubscriptionPlanDtoIn dto, @PathVariable UUID id);

    @Override
    @Operation(summary = "Get a subscription plan by id")
    @ApiResponse(responseCode = "200", description = "Plan found")
    @GetMapping("/{id}")
    ResponseEntity<SubscriptionPlanDtoOut> getById(@PathVariable UUID id);

    @Override
    @Operation(summary = "List all subscription plans")
    @ApiResponse(responseCode = "200", description = "Plans listed")
    @GetMapping
    ResponseEntity<List<SubscriptionPlanDtoOut>> findAll();

    @Override
    @Operation(summary = "Delete a subscription plan by id")
    @ApiResponse(responseCode = "204", description = "Plan deleted")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id);
}
