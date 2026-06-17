package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.ConflictException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.mapper.UserUpdateMapper;
import com.nexaplatform.dropshipping.application.service.PasswordPolicy;
import com.nexaplatform.dropshipping.application.usecase.GoogleLoginOutcome;
import com.nexaplatform.dropshipping.application.usecase.UserUseCase;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PasswordResetTokenEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PasswordResetTokenRepository;
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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Default implementation of {@link UserUseCase}: holds all the logic moved out
 * of the former {@code AuthService} and {@code AdminUserQueryService}. Operates
 * on the {@link User} domain model through the {@link UserRepository} port and
 * keeps {@code PasswordPolicy} / {@code AuditLogger} / {@code EmailQueueService}
 * as collaborators. The password-reset token table is a secondary nested concern
 * persisted through its legacy Spring Data repository (resolving the managed
 * {@code UserEntity} by id).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserUseCaseImpl implements UserUseCase {

    public static final int MAX_FAILED_LOGINS = 5;
    public static final int LOCKOUT_MINUTES = 15;
    public static final int ACTIVATION_TTL_HOURS = 24;
    public static final int RESET_TTL_MINUTES = 30;

    private static final SecureRandom RNG = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository userJpaRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final EmailQueueService emailQueueService;
    private final AuditLogger auditLogger;
    private final UserUpdateMapper userUpdateMapper;

    /* ============ Registration / activation ============ */

    @Override
    @Transactional
    public User register(User user, String rawPassword) {
        String email = normalizeEmail(user.getEmail());
        if (userRepository.existsByEmail(email)) {
            // Same response shape as success path to mitigate user enumeration.
            throw new ConflictException("Account creation failed");
        }
        passwordPolicy.validate(rawPassword);

        String activationCode = randomToken(32);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(UserRole.USER);
        user.setActive(false);
        user.setActivationCode(activationCode);
        user.setActivationCodeExpiresAt(Instant.now().plus(ACTIVATION_TTL_HOURS, ChronoUnit.HOURS));
        user.setLanguage(user.getLanguage() != null ? user.getLanguage() : "es");

        User saved = userRepository.save(user);

        emailQueueService.enqueue(email, "Confirma tu cuenta NexaDrop", "emails/welcome",
                Map.of("displayName", saved.getDisplayName() != null ? saved.getDisplayName() : "", "dashboardUrl",
                        "http://localhost:3003/activate?code=" + activationCode));

        auditLogger.log("auth.register", email, Map.of("userId", saved.getId(), "role", saved.getRole().name()));
        return saved;
    }

    @Override
    @Transactional
    public User activate(String code) {
        User user = userRepository.findByActivationCode(code)
                .orElseThrow(() -> new BusinessException("Invalid or expired activation code"));
        if (user.getActivationCodeExpiresAt() == null || user.getActivationCodeExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException("Invalid or expired activation code");
        }
        user.setActive(true);
        user.setActivationCode(null);
        user.setActivationCodeExpiresAt(null);
        User saved = userRepository.update(user);
        auditLogger.log("auth.activate", saved.getEmail(), Map.of("userId", saved.getId()));
        return saved;
    }

    /* ============ Login-failure tracking ============ */

    @Override
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
            userRepository.update(u);
        });
        auditLogger.log("auth.login_fail", normalized, Map.of());
    }

    @Override
    @Transactional
    public void recordSuccessfulLogin(String email) {
        String normalized = normalizeEmail(email);
        userRepository.findByEmail(normalized).ifPresent(u -> {
            u.setFailedLoginCount(0);
            u.setLockedUntil(null);
            u.setLastLogin(Instant.now());
            userRepository.update(u);
        });
        auditLogger.log("auth.login_ok", normalized, Map.of());
    }

    /* ============ Password reset / change ============ */

    @Override
    @Transactional
    public void requestPasswordReset(String email) {
        String normalized = normalizeEmail(email);
        // Always behave the same regardless of existence (no user enumeration).
        userRepository.findByEmail(normalized).ifPresent(user -> {
            String raw = randomToken(48);
            String hash = sha256(raw);
            UserEntity managed = userJpaRepository.findById(user.getId())
                    .orElseThrow(() -> new NotFoundException("User not found"));
            resetTokenRepository.save(PasswordResetTokenEntity.builder().user(managed).tokenHash(hash)
                    .expiresAt(Instant.now().plus(RESET_TTL_MINUTES, ChronoUnit.MINUTES)).build());
            emailQueueService.enqueue(normalized, "Restablece tu contraseña NexaDrop", "emails/welcome",
                    Map.of("displayName", user.getDisplayName() != null ? user.getDisplayName() : "", "dashboardUrl",
                            "http://localhost:3003/password-reset?token=" + raw));
        });
        auditLogger.log("auth.password_reset.request", normalized, Map.of());
    }

    @Override
    @Transactional
    public void confirmPasswordReset(String token, String newPassword) {
        passwordPolicy.validate(newPassword);
        String hash = sha256(token);
        PasswordResetTokenEntity prt = resetTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new BusinessException("Invalid or expired token"));
        if (prt.getConsumedAt() != null || prt.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException("Invalid or expired token");
        }
        User user = userRepository.getById(prt.getUser().getId());
        if (Objects.isNull(user)) {
            throw new BusinessException("Invalid or expired token");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        prt.setConsumedAt(Instant.now());
        userRepository.update(user);
        resetTokenRepository.save(prt);
        auditLogger.log("auth.password_reset.confirm", user.getEmail(), Map.of("userId", user.getId()));
    }

    @Override
    @Transactional
    public void changePassword(User user, String newPassword) {
        passwordPolicy.validate(newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.update(user);
        auditLogger.log("auth.password_change", user.getEmail(), Map.of("userId", user.getId()));
    }

    /* ============ Lookups ============ */

    @Override
    @Transactional(readOnly = true)
    public User findByEmail(String email) {
        return userRepository.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public User findById(UUID id) {
        User user = userRepository.getById(id);
        if (Objects.isNull(user)) {
            throw new NotFoundException("User not found");
        }
        return user;
    }

    @Override
    @Transactional
    public User updateUser(User user) {
        return userRepository.update(user);
    }

    /* ============ Admin user management ============ */

    @Override
    @Transactional
    public User createAdminUser(User user, String rawPassword, String role) {
        String email = normalizeEmail(user.getEmail());
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email already registered");
        }
        passwordPolicy.validate(rawPassword);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(UserRole.valueOf(role));
        user.setActive(true);
        user.setLanguage("es");
        User saved = userRepository.save(user);
        auditLogger.log("admin.user_created", email, Map.of("role", saved.getRole().name()));
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<User> listUsers(String role, String q, String country, int page, int size) {
        List<User> all = filtered(role, q, country);
        int total = all.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        return all.subList(from, to);
    }

    @Override
    @Transactional(readOnly = true)
    public int countUsers(String role, String q, String country) {
        return filtered(role, q, country).size();
    }

    @Override
    @Transactional
    public User changeRole(UUID id, String role) {
        if (role == null) {
            throw new BusinessException("role required");
        }
        User u = findById(id);
        u.setRole(UserRole.valueOf(role.toUpperCase()));
        return userRepository.update(u);
    }

    /**
     * DROP-586: inline edit of a user's basic fields from the admin panel.
     * Only the fields present (non-null) in the patch are changed; the
     * {@code active} flag is applied only when explicitly provided.
     */
    @Override
    @Transactional
    public User editUser(UUID id, User patch, Boolean active) {
        User u = findById(id);
        // Partial update: null source fields are ignored by the update mapper.
        userUpdateMapper.updateFromModel(patch, u);
        if (active != null)
            u.setActive(active);
        return userRepository.update(u);
    }

    @Override
    @Transactional
    public User lock(UUID id, int minutes) {
        User u = findById(id);
        u.setLockedUntil(Instant.now().plus(minutes, ChronoUnit.MINUTES));
        return userRepository.update(u);
    }

    @Override
    @Transactional
    public User unlock(UUID id) {
        User u = findById(id);
        u.setLockedUntil(null);
        u.setFailedLoginCount(0);
        return userRepository.update(u);
    }

    @Override
    @Transactional
    public User forceActivate(UUID id) {
        User u = findById(id);
        u.setActive(true);
        u.setActivationCode(null);
        u.setActivationCodeExpiresAt(null);
        return userRepository.update(u);
    }

    /* ============ helpers ============ */

    /** Applies the admin-list role/query/country filters and newest-first ordering. */
    private List<User> filtered(String role, String q, String country) {
        String needle = q == null ? "" : q.trim().toLowerCase();
        return userRepository.findAll().stream()
                .filter(u -> role == null || role.isBlank() || u.getRole().name().equalsIgnoreCase(role))
                .filter(u -> country == null || country.isBlank()
                        || (u.getCountry() != null && u.getCountry().equalsIgnoreCase(country)))
                .filter(u -> needle.isEmpty() || (u.getEmail() != null && u.getEmail().toLowerCase().contains(needle))
                        || (u.getDisplayName() != null && u.getDisplayName().toLowerCase().contains(needle))
                        || (u.getCompanyName() != null && u.getCompanyName().toLowerCase().contains(needle)))
                .sorted(Comparator.comparing(User::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private static String normalizeEmail(String email) {
        if (email == null)
            return null;
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

    @Override
    @Transactional
    public GoogleLoginOutcome resolveGoogleLogin(String email, String firstName, String lastName) {
        String normalized = normalizeEmail(email);
        Optional<User> existing = userRepository.findByEmail(normalized);
        if (existing.isPresent()) {
            User user = existing.get();
            if (user.isGoogleLinked()) {
                return new GoogleLoginOutcome(user, false, normalized);
            }
            // A local (password) account already owns this email: do NOT sign in via Google.
            // The caller must confirm ownership with the account password before linking.
            log.info("::> [GOOGLE-OAUTH2] Existing local account, link confirmation required userId={}",
                    user.getId());
            return new GoogleLoginOutcome(null, true, normalized);
        }
        String display = ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();
        User user = User.builder()
                .email(normalized)
                .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                .role(UserRole.USER)
                .active(true)
                .googleLinked(true)
                .displayName(display.isBlank() ? normalized.split("@")[0] : display)
                .language("es")
                .build();
        User saved = userRepository.save(user);
        auditLogger.log("auth.google.register", normalized, Map.of("userId", saved.getId()));
        log.info("::> [GOOGLE-OAUTH2] New user registered userId={}", saved.getId());
        return new GoogleLoginOutcome(saved, false, normalized);
    }

    @Override
    @Transactional
    public void linkGoogleAccount(UUID id) {
        User user = findById(id);
        if (!user.isGoogleLinked()) {
            user.setGoogleLinked(true);
            userRepository.save(user);
            auditLogger.log("auth.google.link", user.getEmail(), Map.of("userId", id));
            log.info("::> [GOOGLE-OAUTH2] Google identity linked to account userId={}", id);
        }
    }


    @Override
    @Transactional
    public void adminResetPassword(UUID id) {
        User user = findById(id);
        requestPasswordReset(user.getEmail());
        auditLogger.log("auth.admin.password_reset", user.getEmail(), Map.of("userId", id));
    }

    @Override
    @Transactional
    public void deleteUser(UUID id) {
        User user = findById(id);
        if (user.getRole() == UserRole.ADMIN) {
            throw new BusinessException("No se puede eliminar una cuenta de administrador");
        }
        userRepository.delete(id);
        auditLogger.log("auth.admin.delete", user.getEmail(), Map.of("userId", id));
    }

    @Override
    @Transactional
    public User inviteUser(String email, String role) {
        String normalized = normalizeEmail(email);
        if (userRepository.existsByEmail(normalized)) {
            throw new ConflictException("Ya existe un usuario con ese correo");
        }
        UserRole r;
        try {
            r = role != null && !role.isBlank() ? UserRole.valueOf(role.trim().toUpperCase()) : UserRole.USER;
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Rol invalido: " + role);
        }
        String activationCode = randomToken(32);
        User user = User.builder()
                .email(normalized)
                .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                .role(r)
                .active(false)
                .activationCode(activationCode)
                .activationCodeExpiresAt(Instant.now().plus(ACTIVATION_TTL_HOURS, ChronoUnit.HOURS))
                .language("es")
                .build();
        User saved = userRepository.save(user);
        emailQueueService.enqueue(normalized, "Te han invitado a NexaDrop", "emails/welcome",
                Map.of("displayName", "", "dashboardUrl",
                        "http://localhost:3003/activate?code=" + activationCode));
        auditLogger.log("auth.admin.invite", normalized, Map.of("userId", saved.getId(), "role", r.name()));
        return saved;
    }

}
