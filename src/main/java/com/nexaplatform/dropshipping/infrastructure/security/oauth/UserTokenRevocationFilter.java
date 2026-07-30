package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import com.nimbusds.jwt.JWTClaimsSet;
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
 * Revocación inmediata de los tokens de USUARIO del SPA (los que emite {@code UserTokenService}, con
 * {@code sub = userId}). Rechaza con 401 cualquier access token cuyo {@code iat} sea anterior al instante
 * de revocación registrado para ese usuario en {@link JwtRevocationService}.
 *
 * <p><b>Por qué existe.</b> El access token vive 60 min y lleva el rol como claim. Sin esta comprobación,
 * degradar a un usuario de ADMIN a USER lo dejaba con acceso de administrador hasta una hora —su token
 * seguía diciendo ADMIN—. Al revocar en el cambio de rol, la siguiente petición con el token viejo se
 * corta y el SPA tiene que renovar, obteniendo el rol nuevo.
 *
 * <p>Hermano de {@link JwtRevocationFilter}, que hace lo mismo para los tokens de partner en
 * {@code /api/v1/partner/**}. Se separan porque cada uno protege su propia cadena de seguridad; aquí solo
 * se miran las rutas autenticadas del SPA ({@code /api/me/**} y {@code /api/admin/**}). Las rutas públicas
 * pasan sin tocar para no penalizar la navegación anónima.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserTokenRevocationFilter extends OncePerRequestFilter {

    private final JwtRevocationService revocationService;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (!path.startsWith("/api/me/") && !path.startsWith("/api/admin/")) {
            chain.doFilter(req, res);
            return;
        }
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            chain.doFilter(req, res);
            return;
        }
        try {
            JWTClaimsSet claims = JWTParser.parse(auth.substring(7)).getJWTClaimsSet();
            String sub = claims.getSubject();
            // Nimbus expone el claim "iat" como java.util.Date y no ofrece accesor java.time; se convierte
            // aquí mismo para que el resto del filtro trabaje solo con segundos de época.
            Date iat = claims.getIssueTime();
            if (sub != null && iat != null
                    && !revocationService.isStillValid(sub, iat.toInstant().getEpochSecond())) {
                res.setStatus(401);
                res.setCharacterEncoding("UTF-8");
                res.setHeader("WWW-Authenticate",
                        "Bearer error=\"invalid_token\", error_description=\"Token revoked (role or account "
                                + "changed). Refresh via /api/auth/refresh.\"");
                res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write(
                        "{\"code\":\"TOKEN_REVOKED\",\"message\":\"Tu sesión se ha actualizado. Vuelve a "
                                + "iniciar sesión para continuar.\"}");
                return;
            }
        } catch (Exception e) {
            // JWT malformado: que lo rechace el ResourceServer con su propio mensaje.
            if (log.isDebugEnabled()) {
                log.debug("JWT parse failed in user revocation filter: {}", e.getMessage());
            }
        }
        chain.doFilter(req, res);
    }
}
