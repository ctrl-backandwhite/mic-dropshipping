package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/** Input payload to request a password reset email. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetRequestDtoIn {

    @NotBlank
    @Email
    @Schema(description = "Account email", example = "user@example.com")
    private String email;
}
