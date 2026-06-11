package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.AdminUserEditDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminUserRoleUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminUserDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminUserPageDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the Admin Users list/lock/role resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Admin Users (list/lock/role)")
public interface AdminUsersListApi {

    @Operation(summary = "List users with optional filters and pagination")
    @ApiResponse(responseCode = "200", description = "Users listed")
    @GetMapping
    ResponseEntity<AdminUserPageDtoOut> list(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String country,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size);

    @Operation(summary = "Change a user's role")
    @ApiResponse(responseCode = "200", description = "Role changed")
    @PutMapping("/{id}/role")
    ResponseEntity<AdminUserDtoOut> changeRole(@PathVariable UUID id,
                                               @Valid @RequestBody AdminUserRoleUpdateDtoIn body);

    @Operation(summary = "Inline edit of a user's basic fields")
    @ApiResponse(responseCode = "200", description = "User updated")
    @PutMapping("/{id}")
    ResponseEntity<AdminUserDtoOut> editUser(@PathVariable UUID id,
                                             @Valid @RequestBody AdminUserEditDtoIn body);

    @Operation(summary = "Lock a user for a number of minutes")
    @ApiResponse(responseCode = "200", description = "User locked")
    @PostMapping("/{id}/lock")
    ResponseEntity<AdminUserDtoOut> lock(@PathVariable UUID id, @RequestParam(defaultValue = "60") int minutes);

    @Operation(summary = "Unlock a user")
    @ApiResponse(responseCode = "200", description = "User unlocked")
    @PostMapping("/{id}/unlock")
    ResponseEntity<AdminUserDtoOut> unlock(@PathVariable UUID id);

    @Operation(summary = "Force-activate a user")
    @ApiResponse(responseCode = "200", description = "User activated")
    @PostMapping("/{id}/activate")
    ResponseEntity<AdminUserDtoOut> forceActivate(@PathVariable UUID id);
}
