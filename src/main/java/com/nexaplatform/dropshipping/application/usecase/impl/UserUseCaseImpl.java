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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import com.nexaplatform.dropshipping.domain.enums.AuthEmailLabel;
import com.nexaplatform.dropshipping.domain.enums.InvoiceLabel;
import com.nexaplatform.dropshipping.domain.enums.OrderEmailLabel;

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
    public static final int DELETION_CODE_TTL_MINUTES = 30;

    private static final SecureRandom RNG = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository userJpaRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final EmailQueueService emailQueueService;
    private final AuditLogger auditLogger;
    private final UserUpdateMapper userUpdateMapper;

    /**
     * Base URL pública del storefront para los enlaces de los emails
     * (activación / reset). Configurable por entorno ({@code STOREFRONT_BASE_URL});
     * en producción debe apuntar al dominio real, NO a localhost.
     */
    @Value("${nexadrop.storefront.base-url:http://localhost:3003}")
    private String storefrontBaseUrl;

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
        // El nombre se captura en partes (nombre + primer/segundo apellido). Componemos el displayName
        // (nombre completo) cuando no venga informado, para que nav/emails/perfil muestren el nombre real.
        user.setDisplayName(resolveDisplayName(user));

        User saved = userRepository.save(user);

        String confirmLang = InvoiceLabel.lang(saved.getLanguage());
        emailQueueService.enqueue(email, AuthEmailLabel.CONFIRM_SUBJECT.of(confirmLang), "emails/welcome",
                Map.of("title", AuthEmailLabel.CONFIRM_TITLE.of(confirmLang),
                        "bodyHtml", AuthEmailLabel.CONFIRM_BODY.of(confirmLang, saved.getDisplayName()),
                        "ctaLabel", AuthEmailLabel.CONFIRM_CTA.of(confirmLang),
                        "ctaUrl", storefrontBaseUrl + "/activate?code=" + activationCode,
                        "icon", "circle-check",
                        "footerNote", OrderEmailLabel.AUTO_NOTE.of(confirmLang)));

        auditLogger.log("auth.register", email, Map.of("userId", saved.getId(), "role", saved.getRole().name()));
        return saved;
    }

    /**
     * Resuelve el nombre a mostrar (nombre completo). Si el usuario ya trae un displayName informado lo
     * respeta; si no, lo compone concatenando nombre + primer apellido + segundo apellido (sin vacíos).
     */
    private String resolveDisplayName(User user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName().trim();
        }
        StringBuilder sb = new StringBuilder();
        for (String part : new String[]{user.getFirstName(), user.getLastName1(), user.getLastName2()}) {
            if (part != null && !part.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(part.trim());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
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

    private static final DateTimeFormatter LOGIN_DATE_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    @Override
    public void notifyLoginDetected(String email) {
        String normalized = normalizeEmail(email);
        userRepository.findByEmail(normalized).ifPresent(u -> {
            String lang = InvoiceLabel.lang(u.getLanguage());
            String when = LOGIN_DATE_FMT.format(Instant.now());
            String body = AuthEmailLabel.LOGIN_BODY.of(lang, u.getDisplayName()).replace("{date}", when);
            emailQueueService.enqueue(normalized, AuthEmailLabel.LOGIN_SUBJECT.of(lang), "emails/welcome",
                    Map.of("title", AuthEmailLabel.LOGIN_TITLE.of(lang),
                            "bodyHtml", body,
                            "ctaLabel", AuthEmailLabel.LOGIN_CTA.of(lang),
                            "ctaUrl", storefrontBaseUrl + "/password-reset",
                            "icon", "circle-check",
                            "footerNote", OrderEmailLabel.AUTO_NOTE.of(lang)));
        });
        auditLogger.log("auth.login_notify", normalized, Map.of());
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
            String resetLang = InvoiceLabel.lang(user.getLanguage());
            emailQueueService.enqueue(normalized, AuthEmailLabel.RESET_SUBJECT.of(resetLang), "emails/welcome",
                    Map.of("title", AuthEmailLabel.RESET_TITLE.of(resetLang),
                            "bodyHtml", AuthEmailLabel.RESET_BODY.of(resetLang, user.getDisplayName()),
                            "ctaLabel", AuthEmailLabel.RESET_CTA.of(resetLang),
                            "ctaUrl", storefrontBaseUrl + "/password-reset?token=" + raw,
                            "icon", "circle-check",
                            "footerNote", OrderEmailLabel.AUTO_NOTE.of(resetLang)));
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

    /* ============ Account deletion (soft delete) ============ */

    @Override
    @Transactional
    public void requestAccountDeletion(UUID userId) {
        User user = findById(userId);
        // Código numérico de 6 dígitos, fácil de teclear desde el email. SecureRandom (no Math.random).
        String code = String.format("%06d", RNG.nextInt(1_000_000));
        user.setDeletionCode(code);
        user.setDeletionCodeExpiresAt(Instant.now().plus(DELETION_CODE_TTL_MINUTES, ChronoUnit.MINUTES));
        userRepository.update(user);
        emailQueueService.enqueue(user.getEmail(),
                "Confirma la eliminación de tu cuenta — NX036 Dropshipping", "emails/account-deletion-code",
                Map.of("title", "Confirma la eliminación de tu cuenta",
                        "displayName", user.getDisplayName() != null ? user.getDisplayName() : "",
                        "code", code,
                        "footerNote", OrderEmailLabel.AUTO_NOTE.of(InvoiceLabel.lang(user.getLanguage()))));
        auditLogger.log("auth.account.delete.request", user.getEmail(), Map.of("userId", userId));
    }

    @Override
    @Transactional
    public void confirmAccountDeletion(UUID userId, String code) {
        User user = findById(userId);
        String provided = code == null ? null : code.trim();
        if (user.getDeletionCode() == null || provided == null || !user.getDeletionCode().equals(provided)
                || user.getDeletionCodeExpiresAt() == null
                || user.getDeletionCodeExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException("DELETION_CODE_INVALID",
                    "El código de eliminación no es válido o ha expirado.");
        }
        // BORRADO LÓGICO: la fila NO se borra físicamente. Se marca deletedAt, se desactiva (el login ya
        // bloquea active=false) y se limpia el código de confirmación.
        user.setDeletedAt(Instant.now());
        user.setActive(false);
        user.setDeletionCode(null);
        user.setDeletionCodeExpiresAt(null);
        userRepository.update(user);
        auditLogger.log("auth.account.delete", user.getEmail(), Map.of("userId", userId));
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
            // Una cuenta local (con contraseña) ya posee este email: NO se inicia sesión vía social sin más.
            // El usuario debe demostrar que controla la cuenta LOCAL (login con su contraseña) antes de
            // vincular. El vínculo se completa en ese login por contraseña (flag linkSocial), no por sesión.
            log.info("::> [OAUTH2] Existing local account, link confirmation required userId={}", user.getId());
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
        String inviteLang = InvoiceLabel.lang(saved.getLanguage());
        emailQueueService.enqueue(normalized, AuthEmailLabel.INVITE_SUBJECT.of(inviteLang), "emails/welcome",
                Map.of("title", AuthEmailLabel.INVITE_TITLE.of(inviteLang),
                        "bodyHtml", AuthEmailLabel.INVITE_BODY.of(inviteLang, ""),
                        "ctaLabel", AuthEmailLabel.INVITE_CTA.of(inviteLang),
                        "ctaUrl", storefrontBaseUrl + "/activate?code=" + activationCode,
                        "icon", "circle-check",
                        "footerNote", OrderEmailLabel.AUTO_NOTE.of(inviteLang)));
        auditLogger.log("auth.admin.invite", normalized, Map.of("userId", saved.getId(), "role", r.name()));
        return saved;
    }

}
