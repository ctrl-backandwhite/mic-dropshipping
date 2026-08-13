package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Payload para reenviar el código de activación a un email. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResendActivationDtoIn {

    @NotBlank
    @Email
    @Schema(description = "Email de la cuenta a reactivar", example = "user@example.com")
    private String email;
}
