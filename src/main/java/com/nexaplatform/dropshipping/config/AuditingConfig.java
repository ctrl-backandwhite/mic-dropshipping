package com.nexaplatform.dropshipping.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Supplies the current auditor for JPA auditing ({@code @CreatedBy}/{@code @LastModifiedBy}).
 * Resolves the authenticated principal name; falls back to {@code "system"} for
 * unauthenticated/background flows (seed, schedulers, webhooks).
 */
@Configuration
public class AuditingConfig {

    private static final String SYSTEM = "system";

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
                return Optional.of(SYSTEM);
            }
            return Optional.ofNullable(auth.getName()).filter(n -> !n.isBlank()).or(() -> Optional.of(SYSTEM));
        };
    }
}
