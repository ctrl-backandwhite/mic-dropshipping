package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/** Input payload to confirm a password reset using a token. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetConfirmDtoIn {

    @NotBlank
    @Schema(description = "Password reset token from the email")
    private String token;

    @NotBlank
    @Size(min = 12, max = 128)
    @Schema(description = "New account password (min 12 chars)")
    private String newPassword;
}
