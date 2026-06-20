package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Petición para canjear un refresh token por un nuevo par de tokens. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefreshTokenDtoIn {

    @Schema(description = "Refresh token emitido en el login")
    @NotBlank
    private String refreshToken;
}
