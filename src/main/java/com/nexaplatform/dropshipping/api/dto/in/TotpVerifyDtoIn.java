package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/** Input payload to verify a one-time code and enable 2FA. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TotpVerifyDtoIn {

    @NotBlank
    @Schema(description = "One-time code from the authenticator app", example = "123456")
    private String otp;
}
