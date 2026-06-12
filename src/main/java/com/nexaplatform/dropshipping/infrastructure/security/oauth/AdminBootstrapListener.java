package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrapListener {

    private static final String DEFAULT_ADMIN_EMAIL = "admin@nx036.local";
    private static final String DEFAULT_ADMIN_PASSWORD = "Nx036Admin!2026";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapAdmin() {
        if (userRepository.existsByEmail(DEFAULT_ADMIN_EMAIL)) {
            return;
        }
        UserEntity admin = UserEntity.builder().email(DEFAULT_ADMIN_EMAIL)
                .passwordHash(passwordEncoder.encode(DEFAULT_ADMIN_PASSWORD)).role(UserRole.ADMIN).active(true)
                .displayName("NX036 Admin").language("es").build();
        userRepository.save(admin);
        log.warn("Bootstrap admin created: {} / {} — CHANGE PASSWORD IMMEDIATELY", DEFAULT_ADMIN_EMAIL,
                DEFAULT_ADMIN_PASSWORD);
    }
}
