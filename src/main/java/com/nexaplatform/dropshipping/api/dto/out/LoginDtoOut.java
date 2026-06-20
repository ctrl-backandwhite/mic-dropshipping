package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Respuesta del login por token: el par de tokens Bearer + el perfil del usuario.
 * El SPA guarda {@code token}/{@code refreshToken} (localStorage) y manda
 * {@code Authorization: Bearer <token>} en cada petición.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginDtoOut {

    @Schema(description = "Access token (JWT) — enviar en Authorization: Bearer")
    private String token;

    @Schema(description = "Refresh token (JWT) — canjear en /api/auth/refresh cuando el access expire")
    private String refreshToken;

    @Schema(description = "Tipo de token", example = "Bearer")
    private String tokenType;

    @Schema(description = "Segundos hasta que expira el access token")
    private long expiresIn;

    @Schema(description = "Perfil del usuario autenticado")
    private MeDtoOut user;
}
