package com.nexaplatform.dropshipping.infrastructure.security.jwt;

import com.nexaplatform.dropshipping.infrastructure.security.jwk.JwkKeyService;
import com.nexaplatform.dropshipping.infrastructure.security.oauth.JwtRevocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;

/**
 * Emite y valida los JWT de USUARIO usados por el SPA (auth Bearer, sin cookies).
 *
 * <p>Firma con el mismo {@code JWKSource} RSA del authorization server (rotado por
 * {@code JwkKeyService}) — así el {@link JwtDecoder} compartido valida estos tokens.
 * Emite dos tokens:
 * <ul>
 *   <li><b>access</b> (claim {@code typ=access}, TTL 60 min): lleva {@code sub}=userId,
 *       {@code email}, {@code role} y {@code authorities} (p.ej. {@code ROLE_ADMIN}).</li>
 *   <li><b>refresh</b> (claim {@code typ=refresh}, TTL 14 días): solo {@code sub}; se canjea
 *       en {@code /api/auth/refresh} por un nuevo par.</li>
 * </ul>
 * El logout llama a {@link #revokeAll(String)} (revocación por {@code sub} en Redis vía
 * {@link JwtRevocationService}); el refresh comprueba esa revocación antes de renovar.
 */
@Service
@RequiredArgsConstructor
public class UserTokenService {

    private static final Duration ACCESS_TTL = Duration.ofMinutes(60);
    private static final Duration REFRESH_TTL = Duration.ofDays(14);

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final JwtRevocationService revocationService;
    private final JwkKeyService jwkKeyService;

    @Value("${nexadrop.oauth.issuer}")
    private String issuer;

    /** Par de tokens emitido al hacer login o refresh. */
    public record Tokens(String accessToken, String refreshToken, long expiresInSeconds) {
    }

    /** Emite un nuevo par access+refresh para el usuario autenticado. */
    public Tokens issue(UUID userId, String email, String role, Collection<String> authorities) {
        Instant now = Instant.now();
        JwtClaimsSet access = JwtClaimsSet.builder().issuer(issuer).issuedAt(now).expiresAt(now.plus(ACCESS_TTL))
                .subject(userId.toString()).claim("email", email).claim("role", role)
                .claim("authorities", new ArrayList<>(authorities)).claim("typ", "access").build();
        // `jti` único por refresh → permite detectar reuso (rotación) en /api/auth/refresh.
        JwtClaimsSet refresh = JwtClaimsSet.builder().issuer(issuer).issuedAt(now).expiresAt(now.plus(REFRESH_TTL))
                .subject(userId.toString()).id(UUID.randomUUID().toString()).claim("typ", "refresh").build();
        // Firmar indicando el `kid` de la clave ACTIVA: tras una rotación hay >1 clave en el
        // JWKSource (las viejas siguen para validar) y, sin `kid`, el encoder no sabe cuál usar.
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(jwkKeyService.activeKid()).build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(header, access)).getTokenValue();
        String refreshToken = jwtEncoder.encode(JwtEncoderParameters.from(header, refresh)).getTokenValue();
        return new Tokens(accessToken, refreshToken, ACCESS_TTL.toSeconds());
    }

    /**
     * Valida un refresh token (firma, expiración, tipo, revocación) y lo CONSUME (rotación):
     * cada refresh es de un solo uso. Si llega un refresh ya canjeado (mismo {@code jti}),
     * se asume robo → se revocan TODOS los tokens del sujeto y se rechaza. Devuelve el
     * {@code sub} (userId). Lanza {@link BadCredentialsException} si algo no cuadra.
     */
    public UUID validateAndRotate(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BadCredentialsException("Missing refresh token");
        }
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(refreshToken);
        } catch (JwtException e) {
            throw new BadCredentialsException("Invalid or expired refresh token");
        }
        if (!"refresh".equals(jwt.getClaimAsString("typ"))) {
            throw new BadCredentialsException("Not a refresh token");
        }
        long issuedAt = jwt.getIssuedAt() != null ? jwt.getIssuedAt().getEpochSecond() : 0L;
        if (!revocationService.isStillValid(jwt.getSubject(), issuedAt)) {
            throw new BadCredentialsException("Refresh token revoked");
        }
        // Detección de reuso: el jti solo puede canjearse una vez. Si ya estaba consumido,
        // es un refresh robado reutilizado → "panic": revoca todo el sujeto (cierra la sesión
        // del atacante y del usuario legítimo, que tendrá que volver a entrar).
        if (!revocationService.consumeRefreshJti(jwt.getId())) {
            revocationService.revokeAllForClient(jwt.getSubject());
            throw new BadCredentialsException("Refresh token reuse detected");
        }
        return UUID.fromString(jwt.getSubject());
    }

    /** Revoca todos los tokens del usuario (logout). Efecto inmediato sobre access y refresh. */
    public void revokeAll(String subject) {
        revocationService.revokeAllForClient(subject);
    }
}
