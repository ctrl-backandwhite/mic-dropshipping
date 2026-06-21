package com.nexaplatform.dropshipping.config;

import com.nexaplatform.dropshipping.infrastructure.security.jwk.JwkKeyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Genera JWT válidos para los tests de integración firmando con el MISMO {@code JWKSource} RSA de la
 * app (vía el {@link JwtEncoder} real, rotado por {@link JwkKeyService}). Así los tokens pasan el
 * {@code JwtDecoder} de producción sin mocks: firma + issuer + {@code typ=access} + no-revocado.
 *
 * <p>Réplica de {@code UserTokenService.issue}: claims {@code sub}, {@code email}, {@code role},
 * {@code authorities} (p.ej. {@code ROLE_ADMIN}) y {@code typ=access}; cabecera con el {@code kid}
 * de la clave activa. Para la Partner API hay {@link #partnerToken(List)} con el claim {@code scope}.
 */
public class JwtTestUtil {

    private static final Duration TTL = Duration.ofMinutes(60);

    private final JwtEncoder jwtEncoder;
    private final JwkKeyService jwkKeyService;
    private final String issuer;

    public JwtTestUtil(JwtEncoder jwtEncoder, JwkKeyService jwkKeyService,
            @Value("${nexadrop.oauth.issuer}") String issuer) {
        this.jwtEncoder = jwtEncoder;
        this.jwkKeyService = jwkKeyService;
        this.issuer = issuer;
    }

    /** Access token de usuario con un {@code sub} aleatorio y los roles dados (sin prefijo: "ADMIN"). */
    public String userToken(String... roles) {
        return userToken(UUID.randomUUID(), "test@nx036.local", roles);
    }

    /** Access token de usuario con {@code sub} y email explícitos. */
    public String userToken(UUID userId, String email, String... roles) {
        List<String> authorities = List.of(roles).stream().map(r -> "ROLE_" + r).toList();
        String primaryRole = roles.length > 0 ? roles[0] : "USER";
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).issuedAt(now).expiresAt(now.plus(TTL))
                .subject(userId.toString()).claim("email", email).claim("role", primaryRole)
                .claim("authorities", authorities).claim("typ", "access").build();
        return encode(claims);
    }

    /** Token de Partner API: claim {@code scope} (p.ej. catalog.read, orders.write, shop.sync). */
    public String partnerToken(List<String> scopes) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).issuedAt(now).expiresAt(now.plus(TTL))
                .subject("pk_test_" + UUID.randomUUID()).claim("scope", scopes).claim("typ", "access").build();
        return encode(claims);
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(jwkKeyService.activeKid()).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
