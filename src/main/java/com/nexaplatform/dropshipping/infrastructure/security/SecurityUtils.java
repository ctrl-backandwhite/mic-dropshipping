package com.nexaplatform.dropshipping.infrastructure.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Utilidades de lectura del contexto de seguridad. Se usan para decidir, en la capa de aplicación/api,
 * qué información sensible (coste, margen/ganancia) se devuelve según el rol del solicitante.
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        String target = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (target.equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /** Solo el ADMIN puede ver coste y margen/ganancia. OPERATOR (soporte) y USER no. */
    public static boolean isAdmin() {
        return hasRole("ADMIN");
    }

    /** Subject (id) del usuario autenticado, o null si no hay sesión. */
    public static String currentSubject() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        return auth.getName();
    }
}
