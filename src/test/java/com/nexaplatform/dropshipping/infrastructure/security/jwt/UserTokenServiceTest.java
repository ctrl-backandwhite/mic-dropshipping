package com.nexaplatform.dropshipping.infrastructure.security.jwt;

import com.nexaplatform.dropshipping.infrastructure.security.jwk.JwkKeyService;
import com.nexaplatform.dropshipping.infrastructure.security.oauth.JwtRevocationService;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class UserTokenServiceTest {

    private static final String ISSUER = "https://auth.test.local";

    @Mock
    private JwtRevocationService revocationService;

    @Mock
    private JwkKeyService jwkKeyService;

    private String kid;
    private JwtEncoder jwtEncoder;
    private JwtDecoder jwtDecoder;
    private UserTokenService service;

    /**
     * El par RSA se genera UNA VEZ para toda la clase, no una por prueba.
     *
     * <p>Generar una clave de 2048 bits cuesta unos 0,7 s, y en `@BeforeEach` eso se pagaba en cada
     * una de las pruebas: 6,5 s de los 128 s de toda la batería se iban aquí. La clave es dato de
     * prueba inmutable —se firma y se valida con ella, no se modifica—, así que compartirla no
     * cambia ninguna aserción. El `kid` sí se sigue sorteando en cada prueba, que es lo único que
     * alguna comprueba que sea distinto.
     */
    private static final KeyPair PAR_RSA = generarParRsa();

    private static KeyPair generarParRsa() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("sin RSA no se puede firmar nada en las pruebas", e);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        // JWKSource RSA real en memoria → firma y validación reales (NimbusJwtEncoder/Decoder).
        KeyPair pair = PAR_RSA;
        kid = UUID.randomUUID().toString();
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID(kid)
                .build();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey));

        jwtEncoder = new NimbusJwtEncoder(jwkSource);
        jwtDecoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) pair.getPublic())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();

        service = new UserTokenService(jwtEncoder, jwtDecoder, revocationService, jwkKeyService);
        ReflectionTestUtils.setField(service, "issuer", ISSUER);
    }

    @Test
    void issue_buildsAccessAndRefreshWithExpectedClaims() {
        when(jwkKeyService.activeKid()).thenReturn(kid);
        UUID userId = UUID.randomUUID();

        UserTokenService.Tokens tokens = service.issue(
                userId, "user@test.local", "ADMIN", List.of("ROLE_ADMIN", "ROLE_USER"));

        assertThat(tokens.expiresInSeconds()).isEqualTo(3600L);

        // Access: typ=access, role, authorities, email, sub.
        org.springframework.security.oauth2.jwt.Jwt access = jwtDecoder.decode(tokens.accessToken());
        assertThat(access.getClaimAsString("typ")).isEqualTo("access");
        assertThat(access.getSubject()).isEqualTo(userId.toString());
        assertThat(access.getClaimAsString("email")).isEqualTo("user@test.local");
        assertThat(access.getClaimAsString("role")).isEqualTo("ADMIN");
        assertThat(access.getClaimAsStringList("authorities"))
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
        assertThat(access.getIssuer()).hasToString(ISSUER);

        // Refresh: typ=refresh, sub, con jti; sin role/authorities/email.
        org.springframework.security.oauth2.jwt.Jwt refresh = jwtDecoder.decode(tokens.refreshToken());
        assertThat(refresh.getClaimAsString("typ")).isEqualTo("refresh");
        assertThat(refresh.getSubject()).isEqualTo(userId.toString());
        assertThat(refresh.getId()).isNotBlank();
        assertThat(refresh.getClaimAsString("role")).isNull();
        assertThat(refresh.getClaimAsString("email")).isNull();
    }

    @Test
    void validateAndRotate_acceptsValidRefreshAndConsumesJti() {
        when(jwkKeyService.activeKid()).thenReturn(kid);
        UUID userId = UUID.randomUUID();
        String refreshToken = service.issue(userId, "u@t.local", "USER", List.of("ROLE_USER")).refreshToken();

        when(revocationService.isStillValid(anyString(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        when(revocationService.consumeRefreshJti(anyString())).thenReturn(true);

        UUID result = service.validateAndRotate(refreshToken);

        assertThat(result).isEqualTo(userId);
        // Consume el jti exactamente una vez (rotación de un solo uso).
        org.springframework.security.oauth2.jwt.Jwt refresh = jwtDecoder.decode(refreshToken);
        verify(revocationService).consumeRefreshJti(refresh.getId());
        verify(revocationService, never()).revokeAllForClient(anyString());
    }

    @Test
    void validateAndRotate_rejectsWhenTokenIsNotRefreshType() {
        when(jwkKeyService.activeKid()).thenReturn(kid);
        UUID userId = UUID.randomUUID();
        // El access token (typ=access) no debe aceptarse en /refresh.
        String accessToken = service.issue(userId, "u@t.local", "USER", List.of("ROLE_USER")).accessToken();

        assertThatThrownBy(() -> service.validateAndRotate(accessToken))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Not a refresh token");
    }

    @Test
    void validateAndRotate_rejectsWhenRevoked() {
        when(jwkKeyService.activeKid()).thenReturn(kid);
        UUID userId = UUID.randomUUID();
        String refreshToken = service.issue(userId, "u@t.local", "USER", List.of("ROLE_USER")).refreshToken();

        when(revocationService.isStillValid(anyString(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(false);

        assertThatThrownBy(() -> service.validateAndRotate(refreshToken))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    void validateAndRotate_panicRevokesAllWhenJtiReused() {
        when(jwkKeyService.activeKid()).thenReturn(kid);
        UUID userId = UUID.randomUUID();
        String refreshToken = service.issue(userId, "u@t.local", "USER", List.of("ROLE_USER")).refreshToken();

        when(revocationService.isStillValid(anyString(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        // jti ya consumido → reuso → revoca todo el sujeto y rechaza.
        when(revocationService.consumeRefreshJti(anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.validateAndRotate(refreshToken))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("reuse");
        verify(revocationService).revokeAllForClient(userId.toString());
    }

    @Test
    void validateAndRotate_rejectsBlankToken() {
        assertThatThrownBy(() -> service.validateAndRotate("  "))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Missing refresh token");
    }

    @Test
    void validateAndRotate_rejectsUndecodableToken() {
        assertThatThrownBy(() -> service.validateAndRotate("not-a-valid-jwt"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid or expired refresh token");
    }

    @Test
    void revokeAll_delegatesToRevocationService() {
        String subject = UUID.randomUUID().toString();

        service.revokeAll(subject);

        verify(revocationService).revokeAllForClient(subject);
    }
}
