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

    /** Solo el ADMIN puede ver coste y margen/ganancia. OPERATOR (soporte), REVIEWER y USER no. */
    public static boolean isAdmin() {
        return hasRole("ADMIN");
    }

    /**
     * Quien revisa el material gráfico de las fichas.
     *
     * <p>Se comprueba aparte de {@link #isAdmin()} a propósito: el revisor ve el ORIGEN —enlace,
     * identificador y proveedor, para cotejar las fotos contra la oferta— pero NO los importes
     * internos. Son dos recortes distintos de la misma ficha, y colapsarlos en un solo booleano
     * «es interno» le acabaría enseñando el margen.
     */
    public static boolean isReviewer() {
        return hasRole("REVIEWER");
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
