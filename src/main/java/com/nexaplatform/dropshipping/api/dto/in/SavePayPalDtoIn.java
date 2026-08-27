package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Guardar una cuenta PayPal como método de pago. El correo se persiste CIFRADO. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SavePayPalDtoIn {

    @NotBlank
    @Email
    @Schema(description = "Correo de la cuenta de PayPal", example = "user@example.com")
    private String email;
}
