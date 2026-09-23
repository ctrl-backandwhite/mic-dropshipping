package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validación del Bearer de usuario que monta {@link BffSecurityConfig}.
 *
 * <p>Dos controles sostienen el login por encima de la validación estándar (firma + caducidad + issuer):
 * <ul>
 *   <li>solo vale un token de <b>acceso</b>: un refresh (14 días) usado como Bearer sería una sesión
 *       eterna que ni el logout ni un cambio de rol podrían cortar;</li>
 *   <li>el token <b>revocado</b> deja de servir en el acto: si no, tras cerrar sesión o cambiar de rol el
 *       token viejo seguiría abriendo el admin hasta que caducase solo.</li>
 * </ul>
 */
class Cov04BffJwtDecoderTest {

    private static final String ISSUER = "https://api.test";

    private static RSAKey firmante;
    private static JWKSource<SecurityContext> jwkSource;

    private JwtRevocationService revocation;
    private NimbusJwtDecoder decoder;

    @BeforeAll
    static void generarClaves() throws Exception {
        firmante = new RSAKeyGenerator(2048).keyID("k1").generate();
        jwkSource = new ImmutableJWKSet<>(new JWKSet(firmante));
    }

    @BeforeEach
    void setUp() {
        // Sin Redis el servicio de revocación usa su respaldo en memoria: suficiente para fijar la regla.
        revocation = new JwtRevocationService(null);
        decoder = (NimbusJwtDecoder) ReflectionTestUtils.invokeMethod(new BffSecurityConfig(), "userJwtDecoder",
                jwkSource, revocation, ISSUER);
    }

    private String token(String typ, String subject, Instant issuedAt) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject)
                .issueTime(Date.from(issuedAt)).expirationTime(Date.from(issuedAt.plus(Duration.ofHours(1))));
        if (typ != null) {
            claims.claim("typ", typ);
        }
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").type(JOSEObjectType.JWT).build(), claims.build());
        jwt.sign(new RSASSASigner(firmante));
        return jwt.serialize();
    }

    @Test
    void unTokenDeAccesoValidoSeAcepta() throws Exception {
        Jwt jwt = decoder.decode(token("access", "usuario-1", Instant.now()));

        assertThat(jwt.getSubject()).isEqualTo("usuario-1");
        assertThat(jwt.getClaimAsString("typ")).isEqualTo("access");
    }

    @Test
    void unRefreshTokenNoSirveComoBearerDeAcceso() throws Exception {
        String refresh = token("refresh", "usuario-1", Instant.now());

        assertThatThrownBy(() -> decoder.decode(refresh)).isInstanceOf(JwtException.class)
                .hasMessageContaining("Access token required");
    }

    @Test
    void unTokenSinTipoDeclaradoTampocoSirve() throws Exception {
        // Un token de partner o cualquier otro emitido por la misma clave no puede colarse como acceso.
        String sinTipo = token(null, "usuario-1", Instant.now());

        assertThatThrownBy(() -> decoder.decode(sinTipo)).isInstanceOf(JwtException.class)
                .hasMessageContaining("Access token required");
    }

    @Test
    void alRevocarUnSujetoSusTokensYaEmitidosDejanDeValerEnElActo() throws Exception {
        String emitidoAntes = token("access", "usuario-1", Instant.now().minus(Duration.ofMinutes(5)));
        revocation.revokeAllForClient("usuario-1");

        assertThatThrownBy(() -> decoder.decode(emitidoAntes)).isInstanceOf(JwtException.class)
                .hasMessageContaining("Token revoked");
    }

    @Test
    void laRevocacionNoAlcanzaAOtrosUsuariosNiALosTokensPosteriores() throws Exception {
        revocation.revokeAllForClient("usuario-1");
        String deOtro = token("access", "usuario-2", Instant.now());
        // Emitido después de la revocación: es el token nuevo que se entrega al volver a entrar.
        String posterior = token("access", "usuario-1", Instant.now().plus(Duration.ofSeconds(30)));

        assertThat(decoder.decode(deOtro).getSubject()).isEqualTo("usuario-2");
        assertThat(decoder.decode(posterior).getSubject()).isEqualTo("usuario-1");
    }

    @Test
    void unTokenDeOtroEmisorNoSeAcepta() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("https://malo.test").subject("usuario-1")
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plus(Duration.ofHours(1))))
                .claim("typ", "access").build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(firmante));
        String ajeno = jwt.serialize();

        assertThatThrownBy(() -> decoder.decode(ajeno)).isInstanceOf(JwtException.class);
    }

    @Test
    void unTokenCaducadoNoSeAcepta() throws Exception {
        String caducado = token("access", "usuario-1", Instant.now().minus(Duration.ofHours(3)));

        assertThatThrownBy(() -> decoder.decode(caducado)).isInstanceOf(JwtException.class);
    }
}
