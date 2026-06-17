package com.nexaplatform.dropshipping.infrastructure.security;

import com.nexaplatform.dropshipping.application.service.DeviceSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Aplica la revocación de dispositivos: si la sesión autenticada actual corresponde a un dispositivo
 * REVOCADO (cookie {@code nx_device} marcada como revocada por el dueño), cierra la sesión y devuelve 401
 * en su siguiente petición. Solo actúa sobre peticiones ya autenticadas; las anónimas pasan sin coste.
 */
@Component
@RequiredArgsConstructor
public class DeviceSessionRevocationFilter extends OncePerRequestFilter {

    private final DeviceSessionService deviceSessionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = auth != null && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken);
        if (authenticated && deviceSessionService.isRevoked(request)) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"SESSION_REVOKED\",\"message\":\"Sesión revocada\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
