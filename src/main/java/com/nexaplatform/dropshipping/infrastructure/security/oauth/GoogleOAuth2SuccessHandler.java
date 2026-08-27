package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import com.nexaplatform.dropshipping.application.service.DeviceSessionService;
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
    private final DeviceSessionService deviceSessionService;
    private final com.nexaplatform.dropshipping.application.service.TotpService totpService;
    private final OAuthRedirectResolver redirects;

    public GoogleOAuth2SuccessHandler(UserUseCase userUseCase, UserTokenService userTokenService,
            DeviceSessionService deviceSessionService,
            com.nexaplatform.dropshipping.application.service.TotpService totpService, String frontBaseUrl) {
        this(userUseCase, userTokenService, deviceSessionService, totpService,
                new OAuthRedirectResolver(frontBaseUrl, ""));
    }

    public GoogleOAuth2SuccessHandler(UserUseCase userUseCase, UserTokenService userTokenService,
            DeviceSessionService deviceSessionService,
            com.nexaplatform.dropshipping.application.service.TotpService totpService,
            OAuthRedirectResolver redirects) {
        this.userUseCase = userUseCase;
        this.userTokenService = userTokenService;
        this.deviceSessionService = deviceSessionService;
        this.totpService = totpService;
        this.redirects = redirects;
    }


    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        OAuth2User principal = (OAuth2User) authentication.getPrincipal();
        String provider = authentication instanceof OAuth2AuthenticationToken token
                ? token.getAuthorizedClientRegistrationId() : "oauth2";
        // Quién arrancó el flujo (web o aplicación móvil). Se anotó en la sesión al iniciarlo, porque el
        // parámetro original se pierde en el viaje de ida y vuelta al proveedor.
        OAuthClientTarget target = OAuthClientTargetFilter.resolve(request);
        String email = principal.getAttribute("email");
        if (email == null || email.isBlank()) {
            log.warn("::> [OAUTH2 {}] Login failed: no email in provider response", provider);
            response.sendRedirect(redirects.error(target, "google_no_email"));
            return;
        }

        // Only trust the email if the provider asserts it is verified. Otherwise a user could register
        // a social account claiming someone else's address and silently take over the local account
        // that shares that email (find-or-create matches by email). GitHub's verified primary email is
        // resolved in GithubOAuth2UserService; Google asserts email_verified in its OIDC token.
        if (!Boolean.TRUE.equals(principal.getAttribute("email_verified"))) {
            log.warn("::> [OAUTH2 {}] Login refused: email not verified by provider", provider);
            response.sendRedirect(redirects.error(target, "google_email_unverified"));
            return;
        }

        // Nombre para el alta: GitHub trae el nombre completo en "name"; Google, given/family.
        String firstName = "github".equals(provider) ? principal.getAttribute("name")
                : principal.getAttribute("given_name");
        String lastName = "github".equals(provider) ? null : principal.getAttribute("family_name");
        // País por IP del CDN (Cloudflare CF-IPCountry, etc.) para prerrellenar el país del alta social.
        // Solo se usa al CREAR la cuenta; a un usuario ya existente no se le toca el país.
        GoogleLoginOutcome outcome = userUseCase.resolveGoogleLogin(email, firstName, lastName, ipCountry(request));

        if (outcome.isLinkRequired()) {
            // Existing local account: stash the verified email and ask for password confirmation
            // instead of signing in. The link is completed on the next successful password login.
            request.getSession(true).setAttribute(PENDING_GOOGLE_LINK_EMAIL, outcome.getEmail());
            log.info("::> [OAUTH2 {}] Link confirmation required, redirecting to login", provider);
            response.sendRedirect(redirects.linkRequired(target));
            return;
        }

        User user = outcome.getUser();
        // 2FA: si la cuenta tiene segundo factor activo, el login social NO puede emitir tokens (saltaría el
        // OTP que sí exige el login por contraseña). Se rechaza y se pide entrar con contraseña + OTP.
        if (totpService.isEnabled(user.getId())) {
            log.info("::> [OAUTH2 {}] Login social rechazado: la cuenta tiene 2FA activo, se exige OTP", provider);
            response.sendRedirect(redirects.error(target, "2fa_required"));
            return;
        }
        UserTokenService.Tokens tokens = userTokenService.issue(user.getId(), user.getEmail(), user.getRole().name(),
                Set.of(user.getRole().authority()));
        log.info("::> [OAUTH2 {}] Login success userId={}", provider, user.getId());
        // Registrar la sesión/dispositivo también en el login social: sin esto, las cuentas que entran por
        // Google/GitHub no aparecían en "Sesiones activas" del perfil (solo lo hacía el login por contraseña).
        deviceSessionService.recordLogin(user.getId(), request, response);
        // El aviso de acceso se emite AQUÍ y no en LoginAuditListener: allí el
        // evento llega antes de que este manejador cree al usuario, así que en un
        // primer acceso con Google no había a quién avisar y el correo se perdía.
        userUseCase.notifyLoginDetected(user.getEmail());
        // Tokens en el fragmento (#) — no llega al servidor ni a los logs del proxy.
        response.sendRedirect(redirects.success(target, tokens.accessToken(), tokens.refreshToken()));
    }

    /** Cabeceras de país por IP que inyectan los CDN/proxys (mismas que usa PricingCountryFilter). */
    private static final String[] GEO_HEADERS = { "CF-IPCountry", "X-Vercel-IP-Country", "X-Geo-Country",
            "X-Country-Code" };

    /** País ISO-2 del CDN, o {@code null} si no viene o es "XX"/"T1" (país desconocido / Tor). */
    private static String ipCountry(HttpServletRequest request) {
        for (String h : GEO_HEADERS) {
            String v = request.getHeader(h);
            if (v != null && v.length() == 2 && !"XX".equalsIgnoreCase(v) && !"T1".equalsIgnoreCase(v)) {
                return v;
            }
        }
        return null;
    }
}
