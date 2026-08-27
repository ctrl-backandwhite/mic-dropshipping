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
        // Este evento se dispara también por cada request autenticado por JWT, donde el "name" es el
        // UUID del subject (no un email). Solo procesamos cuando el principal es un email real, para no
        // generar ruido de auditoría por petición ni buscar por un identificador que no es email.
        String name = event.getAuthentication().getName();
        if (name != null && name.contains("@")) {
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
        // El evento siempre lleva su Authentication (es el "source" del ApplicationEvent, que nunca es
        // nulo), así que el único caso a distinguir es el login social: ahí el email va en los atributos
        // del proveedor y el "name" es el identificador de la cuenta remota, no un correo.
        Authentication auth = event.getAuthentication();
        String email;
        if (auth instanceof OAuth2AuthenticationToken oauth && oauth.getPrincipal() instanceof OAuth2User u) {
            Object attr = u.getAttributes().get("email");
            email = attr != null ? attr.toString() : null;
        } else {
            email = auth.getName();
        }
        if (email == null || !email.contains("@")) {
            return;
        }
        userUseCase.recordSuccessfulLogin(email);
        if (auth instanceof OAuth2AuthenticationToken) {
            // En el acceso social NO se avisa desde aquí.
            //
            // Spring dispara este evento en cuanto el proveedor valida la identidad,
            // y eso ocurre ANTES de que el manejador de éxito cree al usuario en la
            // base. En un primer acceso con Google, buscarlo aquí no lo encuentra y
            // el aviso se pierde sin dejar rastro: el registro de auditoría sí se
            // escribe, así que parecía enviado.
            //
            // Lo emite el manejador de éxito, que es quien tiene el usuario ya
            // creado. Ver GoogleOAuth2SuccessHandler.
            return;
        }
        userUseCase.notifyLoginDetected(email);
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        Object principal = event.getAuthentication().getPrincipal();
        if (principal instanceof String s) {
            userUseCase.recordFailedLogin(s);
        }
    }
}
