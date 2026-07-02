package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Carga el perfil OAuth2 de GitHub. GitHub sólo incluye el email en {@code /user} si el usuario
 * lo ha hecho público; por eso siempre se consulta {@code /user/emails} (scope {@code user:email})
 * y se toma el email <b>primario verificado</b>. El resultado añade los atributos {@code email} y
 * {@code email_verified} para que {@link GoogleOAuth2SuccessHandler} los trate igual que en Google.
 * Para proveedores que no sean GitHub delega en el servicio por defecto sin cambios.
 */
@Slf4j
@Component
public class GithubOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private static final String GITHUB = "github";
    private static final String EMAILS_URL = "https://api.github.com/user/emails";

    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
    private final RestClient restClient = RestClient.create();

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) {
        OAuth2User user = delegate.loadUser(userRequest);
        if (!GITHUB.equals(userRequest.getClientRegistration().getRegistrationId())) {
            return user;
        }

        Map<String, Object> attributes = new HashMap<>(user.getAttributes());
        EmailInfo resolved = resolveEmail(userRequest.getAccessToken().getTokenValue(),
                (String) attributes.get("email"));
        attributes.put("email", resolved == null ? null : resolved.email());
        attributes.put("email_verified", resolved != null && resolved.verified());

        String nameAttributeKey = userRequest.getClientRegistration().getProviderDetails()
                .getUserInfoEndpoint().getUserNameAttributeName();
        Collection<? extends GrantedAuthority> authorities = user.getAuthorities();
        return new DefaultOAuth2User(authorities, attributes, nameAttributeKey);
    }

    /**
     * Prefiere el email primario verificado; si no hay primario, cualquiera verificado; como último
     * recurso, el email público de {@code /user} (marcado como no verificado, se rechazará arriba).
     */
    private EmailInfo resolveEmail(String accessToken, String publicEmail) {
        List<Map<String, Object>> emails = fetchEmails(accessToken);
        if (emails != null) {
            for (Map<String, Object> entry : emails) {
                if (Boolean.TRUE.equals(entry.get("primary")) && Boolean.TRUE.equals(entry.get("verified"))) {
                    return new EmailInfo((String) entry.get("email"), true);
                }
            }
            for (Map<String, Object> entry : emails) {
                if (Boolean.TRUE.equals(entry.get("verified"))) {
                    return new EmailInfo((String) entry.get("email"), true);
                }
            }
        }
        if (publicEmail != null && !publicEmail.isBlank()) {
            return new EmailInfo(publicEmail, false);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchEmails(String accessToken) {
        try {
            return restClient.get()
                    .uri(EMAILS_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .retrieve()
                    .body(List.class);
        } catch (RuntimeException ex) {
            log.warn("::> [GITHUB-OAUTH2] No se pudo consultar /user/emails: {}", ex.getMessage());
            return null;
        }
    }

    private record EmailInfo(String email, boolean verified) {
    }
}
