package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/** Input payload to authenticate and open a session. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginDtoIn {

    @NotBlank
    @Email
    @Schema(description = "Account email", example = "user@example.com")
    private String email;

    @NotBlank
    @Schema(description = "Account password")
    private String password;

    /**
     * Si es true, tras autenticar con contraseña se VINCULA el login social a esta cuenta (el usuario
     * acaba de probar que controla la cuenta local). Lo envía el front cuando el usuario llega desde el
     * flujo OAuth con {@code ?link=required}. Sustituye al vínculo por sesión (que no viaja cross-origin).
     */
    @Schema(description = "Link the pending social identity after a successful password login")
    private boolean linkSocial;

    /**
     * Segundo factor (TOTP) o código de recuperación. Solo se exige cuando la cuenta tiene 2FA
     * activo: en ese caso el primer intento (sin {@code otp}) responde 401 {@code MFA_REQUIRED} y el
     * front reenvía el login con este campo relleno. Se ignora si la cuenta no tiene 2FA.
     */
    @Schema(description = "TOTP code or recovery code (required only when the account has 2FA enabled)", example = "123456")
    private String otp;
}
