package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.ChangePasswordDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.UpdateProfileDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.MeDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * API contract + OpenAPI documentation for the authenticated user's Profile resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Profile")
public interface MeApi {

    @Operation(summary = "Get the authenticated user's profile")
    @GetMapping
    ResponseEntity<MeDtoOut> me(Authentication authentication);

    @Operation(summary = "Change the authenticated user's password")
    @PostMapping("/password")
    ResponseEntity<Void> changePassword(Authentication authentication, @Valid @RequestBody ChangePasswordDtoIn req);

    @Operation(summary = "Update the authenticated user's profile")
    @PutMapping
    ResponseEntity<MeDtoOut> updateProfile(Authentication authentication, @Valid @RequestBody UpdateProfileDtoIn req);
}
