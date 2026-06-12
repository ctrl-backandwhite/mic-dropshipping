package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.ActivateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.LoginDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.PasswordResetConfirmDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.PasswordResetRequestDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.RegisterDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.MeDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.RegisterDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * API contract + OpenAPI documentation for the Authentication resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Auth")
public interface AuthApi {

    @Operation(summary = "Register a new account")
    @PostMapping("/register")
    ResponseEntity<RegisterDtoOut> register(@Valid @RequestBody RegisterDtoIn req);

    @Operation(summary = "Authenticate and open a session")
    @PostMapping("/login")
    ResponseEntity<MeDtoOut> login(@Valid @RequestBody LoginDtoIn req, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse);

    @Operation(summary = "Activate an account with an activation code")
    @PostMapping("/activate")
    ResponseEntity<Void> activate(@Valid @RequestBody ActivateDtoIn req);

    @Operation(summary = "Request a password reset email")
    @PostMapping("/password-reset/request")
    ResponseEntity<Void> requestReset(@Valid @RequestBody PasswordResetRequestDtoIn req);

    @Operation(summary = "Confirm a password reset using a token")
    @PostMapping("/password-reset/confirm")
    ResponseEntity<Void> confirmReset(@Valid @RequestBody PasswordResetConfirmDtoIn req);
}
