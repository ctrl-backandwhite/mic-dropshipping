package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import com.nexaplatform.dropshipping.application.usecase.GoogleLoginOutcome;
import com.nexaplatform.dropshipping.application.usecase.UserUseCase;
import com.nexaplatform.dropshipping.domain.model.User;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import java.io.IOException;
import java.util.List;

/**
 * Handles a successful Google OAuth2 login. Creates the user from the Google
 * profile when the email is unknown, or signs in an already-linked account, by
 * replacing the session authentication with a {@link UsernamePasswordAuthenticationToken}
 * whose principal is the user id and whose authority is the user's role — so the
 * social-login session behaves exactly like a form-login session for every
 * downstream check ({@code /api/me}, role-based authorization).
 *
 * <p>When a local (password) account already owns the email but is not yet linked
 * to Google, the login is NOT completed: the verified email is stashed in the
 * session under {@link #PENDING_GOOGLE_LINK_EMAIL} and the user is redirected to
 * the login page to confirm ownership with their password (see the auth use case,
 * which performs the link on the next successful password login). This prevents a
 * verified-but-unowned Google email from silently taking over an existing account.
 */
@Slf4j
public class GoogleOAuth2SuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    /** Session attribute holding the email of a Google identity awaiting password confirmation. */
    public static final String PENDING_GOOGLE_LINK_EMAIL = "PENDING_GOOGLE_LINK_EMAIL";

    private final UserUseCase userUseCase;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public GoogleOAuth2SuccessHandler(UserUseCase userUseCase) {
        this.userUseCase = userUseCase;
        setDefaultTargetUrl("/");
        setAlwaysUseDefaultTargetUrl(false);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        OAuth2User principal = (OAuth2User) authentication.getPrincipal();
        String email = principal.getAttribute("email");
        if (email == null || email.isBlank()) {
            log.warn("::> [GOOGLE-OAUTH2] Login failed: no email in OAuth2 response");
            response.sendRedirect("/login?error=google_no_email");
            return;
        }

        // Only trust the email if Google asserts it is verified. Otherwise a user could register a
        // Google account claiming someone else's address and silently take over the local account
        // that shares that email (find-or-create matches by email).
        if (!Boolean.TRUE.equals(principal.getAttribute("email_verified"))) {
            log.warn("::> [GOOGLE-OAUTH2] Login refused: email not verified by Google");
            response.sendRedirect("/login?error=google_email_unverified");
            return;
        }

        GoogleLoginOutcome outcome = userUseCase.resolveGoogleLogin(email, principal.getAttribute("given_name"),
                principal.getAttribute("family_name"));

        if (outcome.isLinkRequired()) {
            // Existing local account: stash the verified email and ask for password confirmation
            // instead of signing in. The link is completed on the next successful password login.
            request.getSession(true).setAttribute(PENDING_GOOGLE_LINK_EMAIL, outcome.getEmail());
            log.info("::> [GOOGLE-OAUTH2] Link confirmation required, redirecting to login");
            response.sendRedirect("/login?link=required");
            return;
        }

        User user = outcome.getUser();
        UsernamePasswordAuthenticationToken sessionAuth = new UsernamePasswordAuthenticationToken(
                user.getId().toString(), null, List.of(new SimpleGrantedAuthority(user.getRole().authority())));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(sessionAuth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        log.info("::> [GOOGLE-OAUTH2] Login success userId={}", user.getId());
        super.onAuthenticationSuccess(request, response, sessionAuth);
    }
}
