package com.nexaplatform.dropshipping.infrastructure.security;

import com.nexaplatform.dropshipping.application.usecase.UserUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoginAuditListener {

    private final UserUseCase userUseCase;

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        String name = event.getAuthentication().getName();
        if (name != null) {
            userUseCase.recordSuccessfulLogin(name);
        }
    }

    /**
     * Aviso de seguridad de "inicio de sesión detectado". Se engancha a
     * {@link InteractiveAuthenticationSuccessEvent}, que se publica UNA sola vez por login
     * interactivo (formulario email/contraseña u OAuth) — nunca en la autenticación por JWT de
     * cada request, evitando así un correo por petición.
     */
    @EventListener
    public void onInteractiveSuccess(InteractiveAuthenticationSuccessEvent event) {
        Authentication auth = event.getAuthentication();
        String email = null;
        if (auth instanceof OAuth2AuthenticationToken oauth && oauth.getPrincipal() instanceof OAuth2User u) {
            Object attr = u.getAttributes().get("email");
            email = attr != null ? attr.toString() : null;
        } else if (auth != null) {
            email = auth.getName();
        }
        if (email != null && email.contains("@")) {
            userUseCase.notifyLoginDetected(email);
        }
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        Object principal = event.getAuthentication().getPrincipal();
        if (principal instanceof String s) {
            userUseCase.recordFailedLogin(s);
        }
    }
}
