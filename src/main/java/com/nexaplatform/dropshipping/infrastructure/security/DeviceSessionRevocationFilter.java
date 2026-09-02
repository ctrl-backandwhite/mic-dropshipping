package com.nexaplatform.dropshipping.infrastructure.security;

import com.nexaplatform.dropshipping.application.service.DeviceSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Aplica la revocación de dispositivos: si la sesión autenticada actual corresponde a un dispositivo
 * REVOCADO (cookie {@code nx_device} marcada como revocada por el dueño), rechaza la petición con 401.
 * Solo actúa sobre peticiones ya autenticadas; las anónimas pasan sin coste.
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
            // NO se invalida la sesión aquí. Se hacía, y rompía cualquier OTRA petición que la
            // compartiera: una ficha de producto lanza cinco a la vez, y las que ya estaban en vuelo
            // reventaban al terminar con «Session was invalidated». El daño no acababa ahí — el
            // manejador global de errores tampoco podía escribir su JSON sobre una sesión muerta, así
            // que el navegador recibía una respuesta corrupta y enseñaba «no se ha podido cargar este
            // producto» en lugar de mandar a identificarse.
            //
            // Rechazar con 401 y limpiar el contexto basta: este filtro corta TODAS las peticiones
            // mientras el dispositivo siga revocado, así que invalidar no añadía seguridad. La sesión
            // caduca sola por su tiempo de vida.
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"SESSION_REVOKED\",\"message\":\"Sesión revocada\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
