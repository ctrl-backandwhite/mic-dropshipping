package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Crea el ADMIN inicial si no existe. La contraseña NO está en el código: se lee de
 * {@code nexadrop.bootstrap.admin-password} (variable de entorno).
 *
 * <ul>
 *   <li><b>Perfil local</b>: si no se define la variable, usa una clave de desarrollo
 *       conocida (comodidad para el seed local). Nunca usar este perfil en remoto.</li>
 *   <li><b>dev/pro</b>: la clave es OBLIGATORIA por entorno; si falta, NO se crea ningún
 *       admin (evita un backdoor con credenciales públicas). La clave nunca se loguea.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrapListener {

    /** Solo para el perfil local: clave de desarrollo cuando no se define por entorno. */
    private static final String LOCAL_DEV_PASSWORD = "Nx036Admin!2026";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    @Value("${nexadrop.bootstrap.admin-email:admin@nx036.local}")
    private String adminEmail;

    @Value("${nexadrop.bootstrap.admin-password:}")
    private String adminPassword;

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapAdmin() {
        if (userRepository.existsByEmail(adminEmail)) {
            return;
        }

        boolean isLocal = Arrays.asList(environment.getActiveProfiles()).contains("local");
        String password = adminPassword;
        if (password == null || password.isBlank()) {
            if (isLocal) {
                password = LOCAL_DEV_PASSWORD;
            } else {
                log.warn("No bootstrap admin password configured (nexadrop.bootstrap.admin-password) — "
                        + "skipping admin creation. Set it via env to create the initial admin.");
                return;
            }
        }

        UserEntity admin = UserEntity.builder().email(adminEmail).passwordHash(passwordEncoder.encode(password))
                .role(UserRole.ADMIN).active(true).displayName("NX036 Admin").language("es").build();
        userRepository.save(admin);
        // Nunca se loguea la contraseña.
        log.warn("Bootstrap admin created: {} (password set via configuration)", adminEmail);
    }
}
