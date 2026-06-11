package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.TotpDisableDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.TotpVerifyDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.TotpBackupCodesDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.TotpEnableDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.TotpSetupDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.TotpStatusDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * API contract + OpenAPI documentation for the TOTP two-factor authentication resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Me · 2FA", description = "TOTP-based two-factor authentication")
public interface TotpApi {

    @Operation(summary = "Start 2FA setup — returns secret + otpauth URL for QR")
    @PostMapping("/setup")
    ResponseEntity<TotpSetupDtoOut> setup(Authentication auth);

    @Operation(summary = "Verify the OTP and enable 2FA — returns backup codes once")
    @PostMapping("/verify")
    ResponseEntity<TotpEnableDtoOut> verifyAndEnable(Authentication auth, @Valid @RequestBody TotpVerifyDtoIn req);

    @Operation(summary = "Disable 2FA (requires password re-entry)")
    @PostMapping("/disable")
    ResponseEntity<Void> disable(Authentication auth, @Valid @RequestBody TotpDisableDtoIn req);

    @Operation(summary = "Regenerate the 10 backup codes (previous become invalid)")
    @PostMapping("/backup-codes/regenerate")
    ResponseEntity<TotpBackupCodesDtoOut> regenerate(Authentication auth);

    @Operation(summary = "Check whether 2FA is currently enabled for the user")
    @GetMapping("/status")
    ResponseEntity<TotpStatusDtoOut> status(Authentication auth);
}
