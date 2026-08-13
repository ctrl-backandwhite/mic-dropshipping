package com.nexaplatform.dropshipping.api.exception;

import org.springframework.security.core.AuthenticationException;

/**
 * La contraseña es correcta pero la cuenta tiene 2FA activo y el login no incluyó el código.
 *
 * <p>Extiende {@link AuthenticationException} para mapear a 401, pero el
 * {@code GlobalExceptionHandler} lo trata aparte con el código {@code MFA_REQUIRED} para que el front
 * sepa que debe pedir el segundo factor. Revelar esto NO permite enumerar cuentas: solo se llega aquí
 * tras validar la contraseña, así que el atacante ya poseía la credencial de primer factor.
 */
public class TwoFactorRequiredException extends AuthenticationException {

    public TwoFactorRequiredException() {
        super("Two-factor authentication code required");
    }
}
