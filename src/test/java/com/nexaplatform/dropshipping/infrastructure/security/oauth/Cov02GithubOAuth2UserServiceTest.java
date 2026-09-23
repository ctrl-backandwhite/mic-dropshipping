package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Reglas del login con GitHub: de dónde sale el correo con el que se crea la cuenta. GitHub sólo
 * publica el email en {@code /user} si el usuario lo ha hecho público, así que el servicio consulta
 * {@code /user/emails} y elige el PRIMARIO VERIFICADO. Equivocarse aquí significa crear la cuenta con
 * un correo ajeno o sin verificar.
 */
@ExtendWith(MockitoExtension.class)
class Cov02GithubOAuth2UserServiceTest {

    private static final String EMAILS_URL = "https://api.github.com/user/emails";
    private static final String TOKEN = "gho_token";

    @Mock
    private DefaultOAuth2UserService delegate;

    private GithubOAuth2UserService service;
    private MockRestServiceServer github;

    @BeforeEach
    void setUp() {
        service = new GithubOAuth2UserService();
        RestClient.Builder builder = RestClient.builder();
        github = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(service, "delegate", delegate);
        ReflectionTestUtils.setField(service, "restClient", builder.build());
    }

    private static ClientRegistration registro(String registrationId, String userNameAttribute) {
        ClientRegistration.Builder b = ClientRegistration.withRegistrationId(registrationId).clientId("cid")
                .clientSecret("secreto").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/login/oauth2/code/" + registrationId)
                .authorizationUri("https://github.com/login/oauth/authorize")
                .tokenUri("https://github.com/login/oauth/access_token").userInfoUri("https://api.github.com/user");
        if (userNameAttribute != null) {
            b.userNameAttributeName(userNameAttribute);
        }
        return b.build();
    }

    private static OAuth2UserRequest peticion(ClientRegistration registro) {
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, TOKEN, Instant.now(),
                Instant.now().plusSeconds(3600));
        return new OAuth2UserRequest(registro, token);
    }

    private void delegaEn(Map<String, Object> atributos) {
        OAuth2User usuario = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), atributos, "id");
        when(delegate.loadUser(any(OAuth2UserRequest.class))).thenReturn(usuario);
    }

    private void githubResponde(String json) {
        github.expect(requestTo(EMAILS_URL)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    void conOtroProveedorElPerfilSeDevuelveIntacto() {
        // Google ya trae email verificado en el propio perfil: aquí no se toca nada.
        delegaEn(Map.of("id", 42, "email", "ana@gmail.com"));

        OAuth2User usuario = service.loadUser(peticion(registro("google", "sub")));

        assertThat(usuario.<Object>getAttribute("email")).isEqualTo("ana@gmail.com");
        assertThat(usuario.<Object>getAttribute("email_verified")).isNull();
    }

    @Test
    void seEligeElCorreoPrimarioVerificadoAunqueHayaOtrosVerificados() {
        delegaEn(Map.of("id", 42, "login", "ana"));
        githubResponde("""
                [{"email":"otro@nx.com","primary":false,"verified":true},
                 {"email":"principal@nx.com","primary":true,"verified":true}]
                """);

        OAuth2User usuario = service.loadUser(peticion(registro("github", "id")));

        assertThat(usuario.<Object>getAttribute("email")).isEqualTo("principal@nx.com");
        assertThat(usuario.<Object>getAttribute("email_verified")).isEqualTo(Boolean.TRUE);
        github.verify();
    }

    @Test
    void sinPrimarioValeCualquierCorreoVerificado() {
        // Hay cuentas de GitHub sin correo marcado como primario; sin este respaldo el alta fallaría.
        delegaEn(Map.of("id", 42));
        githubResponde("""
                [{"email":"sinverificar@nx.com","primary":true,"verified":false},
                 {"email":"verificado@nx.com","primary":false,"verified":true}]
                """);

        OAuth2User usuario = service.loadUser(peticion(registro("github", "id")));

        assertThat(usuario.<Object>getAttribute("email")).isEqualTo("verificado@nx.com");
        assertThat(usuario.<Object>getAttribute("email_verified")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void sinNingunCorreoVerificadoSeUsaElPublicoPeroMarcadoComoNoVerificado() {
        // El manejador de éxito rechaza los no verificados: la marca es la que evita el alta.
        delegaEn(Map.of("id", 42, "email", "publico@nx.com"));
        githubResponde("""
                [{"email":"sinverificar@nx.com","primary":true,"verified":false}]
                """);

        OAuth2User usuario = service.loadUser(peticion(registro("github", "id")));

        assertThat(usuario.<Object>getAttribute("email")).isEqualTo("publico@nx.com");
        assertThat(usuario.<Object>getAttribute("email_verified")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void siGithubNoRespondeSeCaeAlCorreoPublicoSinRomperElLogin() {
        delegaEn(Map.of("id", 42, "email", "publico@nx.com"));
        github.expect(requestTo(EMAILS_URL)).andRespond(withServerError());

        OAuth2User usuario = service.loadUser(peticion(registro("github", "id")));

        assertThat(usuario.<Object>getAttribute("email")).isEqualTo("publico@nx.com");
        assertThat(usuario.<Object>getAttribute("email_verified")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void sinCorreoNiVerificadoNiPublicoElAtributoQuedaNuloYNoVerificado() {
        delegaEn(Map.of("id", 42));
        githubResponde("[]");

        OAuth2User usuario = service.loadUser(peticion(registro("github", "id")));

        assertThat(usuario.<Object>getAttribute("email")).isNull();
        assertThat(usuario.<Object>getAttribute("email_verified")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void siElRegistroNoDeclaraAtributoDeNombreSeUsaIdComoRespaldo() {
        // Sin respaldo, un registro mal configurado haría reventar el constructor de DefaultOAuth2User.
        delegaEn(Map.of("id", 42));
        githubResponde("[]");

        OAuth2User usuario = service.loadUser(peticion(registro("github", null)));

        assertThat(usuario.getName()).isEqualTo("42");
    }

    @Test
    void lasAutoridadesDelDelegadoSeConservan() {
        delegaEn(Map.of("id", 42));
        githubResponde("[]");

        OAuth2User usuario = service.loadUser(peticion(registro("github", "id")));

        assertThat(usuario.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
    }
}
