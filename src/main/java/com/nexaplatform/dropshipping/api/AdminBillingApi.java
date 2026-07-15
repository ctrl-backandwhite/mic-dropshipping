package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.OperationResponseDtoOut;
import com.nexaplatform.dropshipping.api.dto.in.AdminPlanUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminPlanDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminSubscriptionDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Tag(name = "Admin Billing")
public interface AdminBillingApi {

    @Operation(summary = "List admin subscriptions, optionally filtered by status")
    @ApiResponse(responseCode = "200", description = "Subscriptions listed")
    @GetMapping("/subscriptions")
    ResponseEntity<List<AdminSubscriptionDtoOut>> subscriptions(@RequestParam(required = false) String status);

    @Operation(summary = "List admin subscription plans")
    @ApiResponse(responseCode = "200", description = "Plans listed")
    @GetMapping("/plans")
    ResponseEntity<List<AdminPlanDtoOut>> plans();

    @Operation(summary = "Update a subscription plan by code")
    @ApiResponse(responseCode = "200", description = "Plan updated")
    @PutMapping("/plans/{code}")
    ResponseEntity<OperationResponseDtoOut> updatePlan(@PathVariable String code,
            @Valid @RequestBody AdminPlanUpdateDtoIn body);
}
