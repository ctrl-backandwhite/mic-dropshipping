package com.nexaplatform.dropshipping.infrastructure.security;

import com.nexaplatform.dropshipping.application.usecase.UserUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * El aviso de acceso NO se emite desde aquí cuando la entrada es social.
 *
 * <p>Spring dispara este evento en cuanto el proveedor valida la identidad, y eso
 * ocurre ANTES de que el manejador de éxito cree al usuario. En un primer acceso
 * con Google no había a quién avisar: el correo se perdía y el registro de
 * auditoría se escribía igual, así que parecía enviado. Ocurrió el 27-ago-2026.
 */
@DisplayName("Aviso de acceso · el social lo emite el manejador, no el oyente")
class LoginAuditListenerSocialTest {

    private final UserUseCase userUseCase = mock(UserUseCase.class);
    private final LoginAuditListener oyente = new LoginAuditListener(userUseCase);

    @Test
    @DisplayName("acceso con Google: registra la entrada pero NO avisa desde aquí")
    void social_no_avisa() {
        OAuth2User principal = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of("email", "cliente@nx036.com", "sub", "123"), "sub");
        Authentication auth = new OAuth2AuthenticationToken(principal,
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");

        oyente.onInteractiveSuccess(new InteractiveAuthenticationSuccessEvent(auth, getClass()));

        verify(userUseCase).recordSuccessfulLogin("cliente@nx036.com");
        // Lo emite GoogleOAuth2SuccessHandler, cuando el usuario ya existe.
        verify(userUseCase, never()).notifyLoginDetected(anyString());
    }

    @Test
    @DisplayName("acceso con contraseña: sí avisa desde aquí, como siempre")
    void con_contrasena_si_avisa() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "cliente@nx036.com", "n/a", AuthorityUtils.createAuthorityList("ROLE_USER"));

        oyente.onInteractiveSuccess(new InteractiveAuthenticationSuccessEvent(auth, getClass()));

        verify(userUseCase).recordSuccessfulLogin("cliente@nx036.com");
        verify(userUseCase).notifyLoginDetected("cliente@nx036.com");
    }
}
