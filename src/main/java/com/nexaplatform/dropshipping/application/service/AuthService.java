package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.AuthDtos.CreateAdminUserRequest;
import com.nexaplatform.dropshipping.api.dto.AuthDtos.RegisterRequest;
import com.nexaplatform.dropshipping.api.dto.out.AdminUserCreatedDtoOut;
import com.nexaplatform.dropshipping.api.mapper.AdminUserCreatedDtoMapper;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.ConflictException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PasswordResetTokenEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PasswordResetTokenRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    public static final int MAX_FAILED_LOGINS = 5;
    public static final int LOCKOUT_MINUTES = 15;
    public static final int ACTIVATION_TTL_HOURS = 24;
    public static final int RESET_TTL_MINUTES = 30;

    private static final SecureRandom RNG = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final EmailQueueService emailQueueService;
    private final AuditLogger auditLogger;
    private final AdminUserCreatedDtoMapper adminUserCreatedDtoMapper;

    /* ============ Registration ============ */

    @Transactional
    public UserEntity register(RegisterRequest req) {
        String email = normalizeEmail(req.email());
        if (userRepository.existsByEmail(email)) {
            // Same response shape as success path to mitigate user enumeration.
            // Throw a generic conflict so the caller knows but doesn't get specifics.
            throw new ConflictException("Account creation failed");
        }
        passwordPolicy.validate(req.password());

        String activationCode = randomToken(32);
        UserEntity user = UserEntity.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(req.password()))
                .role(UserRole.USER)
                .active(false)
                .activationCode(activationCode)
                .activationCodeExpiresAt(Instant.now().plus(ACTIVATION_TTL_HOURS, ChronoUnit.HOURS))
                .displayName(req.displayName())
                .companyName(req.companyName())
                .country(req.country())
                .language(req.language() != null ? req.language() : "es")
                .build();
        user = userRepository.save(user);

        emailQueueService.enqueue(
                email,
                "Confirma tu cuenta NexaDrop",
                "emails/welcome",
                Map.of(
                        "displayName", user.getDisplayName() != null ? user.getDisplayName() : "",
                        "dashboardUrl", "http://localhost:3003/activate?code=" + activationCode));

        auditLogger.log("auth.register", email, Map.of("userId", user.getId(), "role", user.getRole().name()));
        return user;
    }

    @Transactional
    public UserEntity activate(String code) {
        UserEntity user = userRepository.findByActivationCode(code)
                .orElseThrow(() -> new BusinessException("Invalid or expired activation code"));
        if (user.getActivationCodeExpiresAt() == null || user.getActivationCodeExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException("Invalid or expired activation code");
        }
        user.setActive(true);
        user.setActivationCode(null);
        user.setActivationCodeExpiresAt(null);
        userRepository.save(user);
        auditLogger.log("auth.activate", user.getEmail(), Map.of("userId", user.getId()));
        return user;
    }

    /* ============ Login failure tracking ============ */

    @Transactional
    public void recordFailedLogin(String email) {
        String normalized = normalizeEmail(email);
        userRepository.findByEmail(normalized).ifPresent(u -> {
            int next = u.getFailedLoginCount() + 1;
            u.setFailedLoginCount(next);
            if (next >= MAX_FAILED_LOGINS) {
                u.setLockedUntil(Instant.now().plus(LOCKOUT_MINUTES, ChronoUnit.MINUTES));
                auditLogger.log("auth.lockout", normalized, Map.of("until", u.getLockedUntil()));
            }
            userRepository.save(u);
        });
        auditLogger.log("auth.login_fail", normalized, Map.of());
    }

    @Transactional
    public void recordSuccessfulLogin(String email) {
        String normalized = normalizeEmail(email);
        userRepository.findByEmail(normalized).ifPresent(u -> {
            u.setFailedLoginCount(0);
            u.setLockedUntil(null);
            u.setLastLogin(Instant.now());
            userRepository.save(u);
        });
        auditLogger.log("auth.login_ok", normalized, Map.of());
    }

    /* ============ Password reset ============ */

    @Transactional
    public void requestPasswordReset(String email) {
        String normalized = normalizeEmail(email);
        Optional<UserEntity> userOpt = userRepository.findByEmail(normalized);
        // Always behave the same regardless of existence (no user enumeration).
        if (userOpt.isPresent()) {
            UserEntity user = userOpt.get();
            String raw = randomToken(48);
            String hash = sha256(raw);
            resetTokenRepository.save(PasswordResetTokenEntity.builder()
                    .user(user)
                    .tokenHash(hash)
                    .expiresAt(Instant.now().plus(RESET_TTL_MINUTES, ChronoUnit.MINUTES))
                    .build());
            emailQueueService.enqueue(normalized, "Restablece tu contraseña NexaDrop", "emails/welcome",
                    Map.of("displayName", user.getDisplayName() != null ? user.getDisplayName() : "",
                            "dashboardUrl", "http://localhost:3003/password-reset?token=" + raw));
        }
        auditLogger.log("auth.password_reset.request", normalized, Map.of());
    }

    @Transactional
    public void confirmPasswordReset(String token, String newPassword) {
        passwordPolicy.validate(newPassword);
        String hash = sha256(token);
        PasswordResetTokenEntity prt = resetTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new BusinessException("Invalid or expired token"));
        if (prt.getConsumedAt() != null || prt.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException("Invalid or expired token");
        }
        UserEntity user = prt.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        prt.setConsumedAt(Instant.now());
        userRepository.save(user);
        resetTokenRepository.save(prt);
        auditLogger.log("auth.password_reset.confirm", user.getEmail(), Map.of("userId", user.getId()));
    }

    @Transactional
    public void changePassword(UserEntity user, String newPassword) {
        passwordPolicy.validate(newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        auditLogger.log("auth.password_change", user.getEmail(), Map.of("userId", user.getId()));
    }

    /* ============ Admin user mgmt ============ */

    @Transactional
    public UserEntity createAdminUser(CreateAdminUserRequest req) {
        String email = normalizeEmail(req.email());
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email already registered");
        }
        passwordPolicy.validate(req.password());
        UserEntity user = UserEntity.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(req.password()))
                .role(UserRole.valueOf(req.role()))
                .active(true)
                .displayName(req.displayName())
                .language("es")
                .build();
        user = userRepository.save(user);
        auditLogger.log("admin.user_created", email, Map.of("role", user.getRole().name()));
        return user;
    }

    /**
     * Creates an admin user and returns its identifier as a transport DTO.
     * Thin wrapper over {@link #createAdminUser(CreateAdminUserRequest)} used by
     * the controller so it carries no mapping logic.
     */
    @Transactional
    public AdminUserCreatedDtoOut createAdminUserDto(CreateAdminUserRequest req) {
        return adminUserCreatedDtoMapper.toDtoOut(createAdminUser(req));
    }

    @Transactional(readOnly = true)
    public UserEntity findByEmail(String email) {
        return userRepository.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    @Transactional(readOnly = true)
    public UserEntity findById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    /* ============ helpers ============ */

    private static String normalizeEmail(String email) {
        if (email == null) return null;
        return email.trim().toLowerCase();
    }

    private static String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
