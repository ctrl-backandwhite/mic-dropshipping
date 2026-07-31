package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import com.nexaplatform.dropshipping.application.service.Texts;
import com.nexaplatform.dropshipping.application.usecase.GoogleLoginOutcome;
import com.nexaplatform.dropshipping.application.usecase.UserUseCase;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.infrastructure.security.jwt.UserTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;
import java.util.Set;

/**
 * Maneja un login social exitoso (Google y GitHub) bajo el modelo de auth por <b>token</b>: en vez de
 * abrir sesión, emite el par de tokens Bearer y redirige al SPA del frontend a
 * {@code <front>/auth/callback#token=…&refresh=…} (en el fragmento de la URL, que no viaja
 * al servidor). El SPA lee el fragmento, guarda los tokens y llama a {@code /api/me}.
 *
 * <p>Si el email ya pertenece a una cuenta local no vinculada a Google, NO se completa el
 * login: el email verificado se guarda en la sesión del flujo OAuth (la cadena por defecto
 * sigue siendo con sesión) bajo {@link #PENDING_GOOGLE_LINK_EMAIL} y se redirige al login del
 * front para confirmar con contraseña. Así un email verificado-pero-no-propio no toma una
 * cuenta existente.
 */
@Slf4j
public class GoogleOAuth2SuccessHandler implements AuthenticationSuccessHandler {

    /** Session attribute holding the email of a Google identity awaiting password confirmation. */
    public static final String PENDING_GOOGLE_LINK_EMAIL = "PENDING_GOOGLE_LINK_EMAIL";

    private final UserUseCase userUseCase;
    private final UserTokenService userTokenService;
    private final String frontBaseUrl;

    public GoogleOAuth2SuccessHandler(UserUseCase userUseCase, UserTokenService userTokenService, String frontBaseUrl) {
        this.userUseCase = userUseCase;
        this.userTokenService = userTokenService;
        this.frontBaseUrl = frontBaseUrl == null ? "" : Texts.stripTrailingSlashes(frontBaseUrl);
    }


    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        OAuth2User principal = (OAuth2User) authentication.getPrincipal();
        String provider = authentication instanceof OAuth2AuthenticationToken token
                ? token.getAuthorizedClientRegistrationId() : "oauth2";
        String email = principal.getAttribute("email");
        if (email == null || email.isBlank()) {
            log.warn("::> [OAUTH2 {}] Login failed: no email in provider response", provider);
            response.sendRedirect(frontBaseUrl + "/login?error=google_no_email");
            return;
        }

        // Only trust the email if the provider asserts it is verified. Otherwise a user could register
        // a social account claiming someone else's address and silently take over the local account
        // that shares that email (find-or-create matches by email). GitHub's verified primary email is
        // resolved in GithubOAuth2UserService; Google asserts email_verified in its OIDC token.
        if (!Boolean.TRUE.equals(principal.getAttribute("email_verified"))) {
            log.warn("::> [OAUTH2 {}] Login refused: email not verified by provider", provider);
            response.sendRedirect(frontBaseUrl + "/login?error=google_email_unverified");
            return;
        }

        // Nombre para el alta: GitHub trae el nombre completo en "name"; Google, given/family.
        String firstName = "github".equals(provider) ? principal.getAttribute("name")
                : principal.getAttribute("given_name");
        String lastName = "github".equals(provider) ? null : principal.getAttribute("family_name");
        GoogleLoginOutcome outcome = userUseCase.resolveGoogleLogin(email, firstName, lastName);

        if (outcome.isLinkRequired()) {
            // Existing local account: stash the verified email and ask for password confirmation
            // instead of signing in. The link is completed on the next successful password login.
            request.getSession(true).setAttribute(PENDING_GOOGLE_LINK_EMAIL, outcome.getEmail());
            log.info("::> [OAUTH2 {}] Link confirmation required, redirecting to login", provider);
            response.sendRedirect(frontBaseUrl + "/login?link=required");
            return;
        }

        User user = outcome.getUser();
        UserTokenService.Tokens tokens = userTokenService.issue(user.getId(), user.getEmail(), user.getRole().name(),
                Set.of(user.getRole().authority()));
        log.info("::> [OAUTH2 {}] Login success userId={}", provider, user.getId());
        // Tokens en el fragmento (#) — no llega al servidor ni a los logs del proxy.
        response.sendRedirect(frontBaseUrl + "/auth/callback#token=" + tokens.accessToken() + "&refresh="
                + tokens.refreshToken());
    }
}
