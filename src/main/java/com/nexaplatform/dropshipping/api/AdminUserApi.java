package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.CreateAdminUserDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminUserCreatedDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * API contract + OpenAPI documentation for the Admin Users creation resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Admin Users")
public interface AdminUserApi {

    @Operation(summary = "Create an admin user")
    @ApiResponse(responseCode = "201", description = "Admin user created")
    @PostMapping
    ResponseEntity<AdminUserCreatedDtoOut> create(@Valid @RequestBody CreateAdminUserDtoIn req);
}
