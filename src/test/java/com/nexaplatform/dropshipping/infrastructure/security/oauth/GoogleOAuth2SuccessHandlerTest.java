package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import com.nexaplatform.dropshipping.application.usecase.GoogleLoginOutcome;
import com.nexaplatform.dropshipping.application.usecase.UserUseCase;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.infrastructure.security.jwt.UserTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleOAuth2SuccessHandlerTest {

    private static final String FRONT = "https://app.example.com";

    @Mock
    private UserUseCase userUseCase;

    @Mock
    private UserTokenService userTokenService;

    private GoogleOAuth2SuccessHandler handler;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        // Trailing slash must be trimmed by the handler.
        handler = new GoogleOAuth2SuccessHandler(userUseCase, userTokenService, FRONT + "/");
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    private OAuth2AuthenticationToken authToken(Map<String, Object> attributes) {
        OAuth2User principal = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"), attributes, "email");
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
    }

    private Map<String, Object> verifiedAttributes(String email) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("email", email);
        attrs.put("email_verified", Boolean.TRUE);
        attrs.put("given_name", "Jane");
        attrs.put("family_name", "Doe");
        return attrs;
    }

    private User user(UUID id, String email) {
        User u = User.builder().email(email).role(UserRole.USER).build();
        u.setId(id);
        return u;
    }

    @Test
    void new_or_linked_account_issues_tokens_and_redirects_to_callback_fragment() throws Exception {
        UUID userId = UUID.randomUUID();
        User user = user(userId, "jane@gmail.com");
        when(userUseCase.resolveGoogleLogin(eq("jane@gmail.com"), eq("Jane"), eq("Doe")))
                .thenReturn(new GoogleLoginOutcome(user, false, "jane@gmail.com"));
        when(userTokenService.issue(eq(userId), eq("jane@gmail.com"), eq("USER"), any()))
                .thenReturn(new UserTokenService.Tokens("ACCESS-T", "REFRESH-T", 3600L));

        handler.onAuthenticationSuccess(request, response, authToken(verifiedAttributes("jane@gmail.com")));

        assertThat(response.getRedirectedUrl())
                .isEqualTo(FRONT + "/auth/callback#token=ACCESS-T&refresh=REFRESH-T");
    }

    @Test
    void existing_local_account_stashes_email_in_session_and_redirects_to_link_required() throws Exception {
        when(userUseCase.resolveGoogleLogin(eq("owner@gmail.com"), anyString(), anyString()))
                .thenReturn(new GoogleLoginOutcome(null, true, "owner@gmail.com"));

        handler.onAuthenticationSuccess(request, response, authToken(verifiedAttributes("owner@gmail.com")));

        assertThat(response.getRedirectedUrl()).isEqualTo(FRONT + "/login?link=required");
        assertThat(request.getSession(false)).isNotNull();
        assertThat(request.getSession(false)
                .getAttribute(GoogleOAuth2SuccessHandler.PENDING_GOOGLE_LINK_EMAIL))
                .isEqualTo("owner@gmail.com");
        verify(userTokenService, never()).issue(any(), anyString(), anyString(), any());
    }

    @Test
    void missing_email_redirects_to_login_error_without_resolving() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("email_verified", Boolean.TRUE);
        // DefaultOAuth2User requires a non-null value for its name key, so supply a placeholder
        // and override email with blank to exercise the missing-email guard.
        attrs.put("email", "");

        handler.onAuthenticationSuccess(request, response, authTokenWithNameKey(attrs));

        assertThat(response.getRedirectedUrl()).isEqualTo(FRONT + "/login?error=google_no_email");
        verifyNoInteractions(userUseCase);
        verifyNoInteractions(userTokenService);
    }

    @Test
    void unverified_email_is_refused_and_redirects_to_unverified_error() throws Exception {
        Map<String, Object> attrs = verifiedAttributes("spoof@gmail.com");
        attrs.put("email_verified", Boolean.FALSE);

        handler.onAuthenticationSuccess(request, response, authToken(attrs));

        assertThat(response.getRedirectedUrl()).isEqualTo(FRONT + "/login?error=google_email_unverified");
        verifyNoInteractions(userUseCase);
        verifyNoInteractions(userTokenService);
    }

    /** Variant builder that keys the principal name on a present attribute (blank-email cases). */
    private OAuth2AuthenticationToken authTokenWithNameKey(Map<String, Object> attributes) {
        if (!attributes.containsKey("sub")) {
            attributes.put("sub", "google-sub-123");
        }
        OAuth2User principal = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"), attributes, "sub");
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
    }
}
