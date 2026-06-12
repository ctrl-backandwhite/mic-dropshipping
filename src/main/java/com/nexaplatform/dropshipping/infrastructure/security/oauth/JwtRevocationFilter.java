package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import com.nimbusds.jwt.JWTParser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Date;

/**
 * Rechaza con 401 los JWT cuyo `iat` sea anterior al timestamp de revocación
 * registrado para el `sub` (client_id). Se ejecuta antes del filtro de scope,
 * por eso lo ponemos al inicio en el partner chain.
 *
 * Sólo aplica a paths /api/v1/partner/** — el resto pasa sin tocar.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtRevocationFilter extends OncePerRequestFilter {

    private final JwtRevocationService revocationService;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (!path.startsWith("/api/v1/partner/")) {
            chain.doFilter(req, res);
            return;
        }
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            chain.doFilter(req, res);
            return;
        }
        try {
            var claims = JWTParser.parse(auth.substring(7)).getJWTClaimsSet();
            String sub = claims.getSubject();
            Date iat = claims.getIssueTime();
            if (sub != null && iat != null && !revocationService.isStillValid(sub, iat.toInstant().getEpochSecond())) {
                res.setStatus(401);
                res.setHeader("WWW-Authenticate",
                        "Bearer error=\"invalid_token\", error_description=\"Token revoked by issuer (plan change or credential removed). Request a new token via /oauth2/token.\"");
                res.setContentType("application/json");
                res.getWriter().write(
                        "{\"code\":\"TOKEN_REVOKED\",\"message\":\"This token was revoked. Request a new one via /oauth2/token.\"}");
                return;
            }
        } catch (Exception e) {
            // JWT inválido o malformado — dejamos que el ResourceServer lo rechace con su mensaje propio.
            if (log.isDebugEnabled())
                log.debug("JWT parse failed in revocation filter: {}", e.getMessage());
        }
        chain.doFilter(req, res);
    }
}
