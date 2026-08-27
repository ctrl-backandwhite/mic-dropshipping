package com.nexaplatform.dropshipping.api.exception;

import org.springframework.security.core.AuthenticationException;

/**
 * Contraseña correcta y 2FA activo, pero el código TOTP / de recuperación aportado es inválido.
 *
 * <p>Mapea a 401 con código {@code MFA_INVALID}. El primer factor ya se validó, por lo que distinguir
 * este caso del "código requerido" no abre un oráculo de enumeración de cuentas. El límite de tasa por
 * IP de {@code /api/auth/login} acota los intentos de fuerza bruta sobre el código.
 */
public class TwoFactorInvalidException extends AuthenticationException {

    public TwoFactorInvalidException() {
        super("Invalid two-factor authentication code");
    }
}
