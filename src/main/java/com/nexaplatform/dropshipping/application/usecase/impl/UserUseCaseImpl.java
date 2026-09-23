package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.ConflictException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.mapper.UserUpdateMapper;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.service.PasswordPolicy;
import com.nexaplatform.dropshipping.application.usecase.GoogleLoginOutcome;
import com.nexaplatform.dropshipping.application.usecase.UserUseCase;
import com.nexaplatform.dropshipping.domain.enums.AuthEmailLabel;
import com.nexaplatform.dropshipping.domain.enums.BrandTagline;
import com.nexaplatform.dropshipping.domain.enums.InvoiceLabel;
import com.nexaplatform.dropshipping.domain.enums.OrderEmailLabel;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PasswordResetTokenEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PasswordResetTokenRepository;
import com.nexaplatform.dropshipping.infrastructure.security.SecurityUtils;
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

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String EMAILS_WELCOME = "emails/welcome";
    private static final String CIRCLE_CHECK = "circle-check";
    private static final String FOOTERNOTE = "footerNote";
    /** Descriptor de la cabecera del correo: «NX036 · Moda y complementos», en el idioma de quien lee. */
    private static final String TAGLINE = "tagline";
    private static final String BODYHTML = "bodyHtml";
    private static final String CTALABEL = "ctaLabel";
    private static final String USERID = "userId";
    private static final String CTAURL = "ctaUrl";
    private static final String TITLE = "title";

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
    private final com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserAddressRepository userAddressJpaRepository;
    /** Para invalidar en caliente los tokens de un usuario cuando cambia su rol (o su acceso). */
    private final com.nexaplatform.dropshipping.infrastructure.security.oauth.JwtRevocationService jwtRevocationService;

    /**
     * Base URL pública del storefront para los enlaces de los emails
     * (activación / reset). Configurable por entorno ({@code STOREFRONT_BASE_URL});
     * en producción debe apuntar al dominio real, NO a localhost.
     */
    @Value("${nexadrop.storefront.base-url:http://localhost:3003}")
    private String storefrontBaseUrl;

    /**
     * Versión vigente de los textos legales, en formato fecha. Es lo que se guarda como constancia de QUÉ
     * aceptó cada usuario, y no solo de que aceptó algo: sin versión, el día que el texto cambie no hay
     * forma de saber a qué redacción dio su consentimiento.
     *
     * <p>Vive aquí porque el alta social no recibe nada del cliente —quien redirige es el proveedor de
     * identidad, que no conoce nuestros textos—, así que el servidor tiene que saber cuál está publicada.
     * Al actualizar los textos hay que subir también este valor y el del escaparate a la vez.
     */
    @Value("${nexadrop.legal.version:2026-07-31}")
    private String legalVersion;

    /* ============ Registration / activation ============ */

    @Override
    @Transactional
    public User register(User user, String rawPassword) {
        String email = normalizeEmail(user.getEmail());
        passwordPolicy.validate(rawPassword);
        if (userRepository.existsByEmail(email)) {
            // Anti-enumeración REAL: mismo status (200) y mismo cuerpo que el alta correcta. Antes se
            // lanzaba ConflictException → 409, que permitía distinguir "email registrado" de "libre". No
            // creamos ni reenviamos nada; devolvemos un id efímero para que la respuesta sea idéntica.
            auditLogger.log("auth.register.duplicate", email, Map.of());
            return User.builder().id(UUID.randomUUID()).email(email).role(UserRole.USER).active(false).build();
        }

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
        emailQueueService.enqueue(email, AuthEmailLabel.CONFIRM_SUBJECT.of(confirmLang), EMAILS_WELCOME,
                Map.of(TITLE, AuthEmailLabel.CONFIRM_TITLE.of(confirmLang), BODYHTML,
                        AuthEmailLabel.CONFIRM_BODY.of(confirmLang, saved.getDisplayName()), CTALABEL,
                        AuthEmailLabel.CONFIRM_CTA.of(confirmLang), CTAURL,
                        storefrontBaseUrl + "/activate?code=" + activationCode, "icon", CIRCLE_CHECK, FOOTERNOTE,
                        OrderEmailLabel.AUTO_NOTE.of(confirmLang), TAGLINE, BrandTagline.of(confirmLang)));

        auditLogger.log("auth.register", email, Map.of(USERID, saved.getId(), "role", saved.getRole().name()));
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
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(part.trim());
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    @Override
    @Transactional
    public void resendActivation(String email) {
        String normalized = normalizeEmail(email);
        // Respuesta NEUTRA (anti-enumeración): el controlador siempre devuelve 204. Aquí solo reenviamos si
        // la cuenta existe, aún no está activada y no está borrada; en cualquier otro caso, no hacemos nada.
        userRepository.findByEmail(normalized).ifPresent(user -> {
            if (user.isActive() || user.getDeletedAt() != null) {
                return;
            }
            String activationCode = randomToken(32);
            user.setActivationCode(activationCode);
            user.setActivationCodeExpiresAt(Instant.now().plus(ACTIVATION_TTL_HOURS, ChronoUnit.HOURS));
            userRepository.update(user);
            String confirmLang = InvoiceLabel.lang(user.getLanguage());
            emailQueueService.enqueue(user.getEmail(), AuthEmailLabel.CONFIRM_SUBJECT.of(confirmLang), EMAILS_WELCOME,
                    Map.of(TITLE, AuthEmailLabel.CONFIRM_TITLE.of(confirmLang), BODYHTML,
                            AuthEmailLabel.CONFIRM_BODY.of(confirmLang, user.getDisplayName()), CTALABEL,
                            AuthEmailLabel.CONFIRM_CTA.of(confirmLang), CTAURL,
                            storefrontBaseUrl + "/activate?code=" + activationCode, "icon", CIRCLE_CHECK, FOOTERNOTE,
                            OrderEmailLabel.AUTO_NOTE.of(confirmLang), TAGLINE,
                            BrandTagline.of(user.getRole(), confirmLang)));
            auditLogger.log("auth.activation.resend", user.getEmail(), Map.of(USERID, user.getId()));
        });
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
        auditLogger.log("auth.activate", saved.getEmail(), Map.of(USERID, saved.getId()));
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

    private static final DateTimeFormatter LOGIN_DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    @Override
    public void notifyLoginDetected(String email) {
        String normalized = normalizeEmail(email);
        userRepository.findByEmail(normalized).ifPresent(u -> {
            String lang = InvoiceLabel.lang(u.getLanguage());
            String when = LOGIN_DATE_FMT.format(Instant.now());
            String body = AuthEmailLabel.LOGIN_BODY.of(lang, u.getDisplayName()).replace("{date}", when);
            emailQueueService.enqueue(normalized, AuthEmailLabel.LOGIN_SUBJECT.of(lang), EMAILS_WELCOME,
                    Map.of(TITLE, AuthEmailLabel.LOGIN_TITLE.of(lang), BODYHTML, body, CTALABEL,
                            AuthEmailLabel.LOGIN_CTA.of(lang), CTAURL, storefrontBaseUrl + "/password-reset", "icon",
                            CIRCLE_CHECK, FOOTERNOTE, OrderEmailLabel.AUTO_NOTE.of(lang), TAGLINE,
                            BrandTagline.of(u.getRole(), lang)));
        });
        auditLogger.log("auth.login_notify", normalized, Map.of());
    }

    /* ============ Password reset / change ============ */

    @Override
    @Transactional
    public void requestPasswordReset(String email) {
        doRequestPasswordReset(email);
    }

    /**
     * Cuerpo real del reset. Se separa del método anotado porque {@code adminResetPassword} lo invocaba
     * con {@code this}: el proxy de Spring no intercepta esa llamada, así que el {@code @Transactional}
     * del método público no pintaba nada ahí. La anotación queda solo en el punto de entrada.
     */
    private void doRequestPasswordReset(String email) {
        String normalized = normalizeEmail(email);
        // Always behave the same regardless of existence (no user enumeration).
        userRepository.findByEmail(normalized).ifPresent(user -> {
            String raw = randomToken(48);
            String hash = sha256(raw);
            UserEntity managed = userJpaRepository.findById(user.getId())
                    .orElseThrow(() -> new NotFoundException("User not found"));
            // Al emitir un token nuevo, invalidamos los anteriores del usuario: no deben quedar varios
            // enlaces de reset válidos a la vez (reduce la ventana de un enlace filtrado).
            resetTokenRepository.consumeAllActiveForUser(managed.getId(), Instant.now());
            resetTokenRepository.save(PasswordResetTokenEntity.builder().user(managed).tokenHash(hash)
                    .expiresAt(Instant.now().plus(RESET_TTL_MINUTES, ChronoUnit.MINUTES)).build());
            String resetLang = InvoiceLabel.lang(user.getLanguage());
            emailQueueService.enqueue(normalized, AuthEmailLabel.RESET_SUBJECT.of(resetLang), EMAILS_WELCOME,
                    Map.of(TITLE, AuthEmailLabel.RESET_TITLE.of(resetLang), BODYHTML,
                            AuthEmailLabel.RESET_BODY.of(resetLang, user.getDisplayName()), CTALABEL,
                            AuthEmailLabel.RESET_CTA.of(resetLang), CTAURL,
                            storefrontBaseUrl + "/password-reset?token=" + raw, "icon", CIRCLE_CHECK, FOOTERNOTE,
                            OrderEmailLabel.AUTO_NOTE.of(resetLang), TAGLINE,
                            BrandTagline.of(user.getRole(), resetLang)));
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
        // Cambiar la contraseña cierra TODAS las sesiones activas: si la cuenta estaba comprometida, el
        // token del atacante (access hasta 60 min, refresh hasta 14 días) dejaría de servir de inmediato.
        jwtRevocationService.revokeAllForClient(user.getId().toString());
        auditLogger.log("auth.password_reset.confirm", user.getEmail(), Map.of(USERID, user.getId()));
    }

    @Override
    @Transactional
    public void changePassword(User user, String newPassword) {
        passwordPolicy.validate(newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.update(user);
        // Igual que el reset: cambiar la contraseña revoca las sesiones/tokens previos.
        jwtRevocationService.revokeAllForClient(user.getId().toString());
        auditLogger.log("auth.password_change", user.getEmail(), Map.of(USERID, user.getId()));
    }

    /* ============ Account deletion (soft delete) ============ */

    @Override
    @Transactional
    public void requestAccountDeletion(UUID userId) {
        User user = loadUser(userId);
        // Código numérico de 6 dígitos, fácil de teclear desde el email. SecureRandom (no Math.random).
        String code = String.format("%06d", RNG.nextInt(1_000_000));
        user.setDeletionCode(code);
        user.setDeletionCodeExpiresAt(Instant.now().plus(DELETION_CODE_TTL_MINUTES, ChronoUnit.MINUTES));
        userRepository.update(user);
        // EN EL IDIOMA DE LA CUENTA, como el resto de correos: el aviso de borrado salía siempre en
        // español, de modo que quien se registró en inglés recibía instrucciones que no entiende justo
        // en el correo que le pide un código para borrarse.
        String deleteLang = InvoiceLabel.lang(user.getLanguage());
        emailQueueService.enqueue(user.getEmail(), AuthEmailLabel.DELETE_SUBJECT.of(deleteLang),
                "emails/account-deletion-code",
                Map.of(TITLE, AuthEmailLabel.DELETE_TITLE.of(deleteLang), "greeting",
                        AuthEmailLabel.DELETE_GREETING.of(deleteLang, user.getDisplayName()), "intro",
                        AuthEmailLabel.DELETE_INTRO.of(deleteLang), "expires",
                        AuthEmailLabel.DELETE_EXPIRES.of(deleteLang), "ignoreNote",
                        AuthEmailLabel.DELETE_IGNORE.of(deleteLang), "preheader",
                        AuthEmailLabel.DELETE_PREHEADER.of(deleteLang), "code", code, FOOTERNOTE,
                        OrderEmailLabel.AUTO_NOTE.of(deleteLang), TAGLINE,
                        BrandTagline.of(user.getRole(), deleteLang)));
        auditLogger.log("auth.account.delete.request", user.getEmail(), Map.of(USERID, userId));
    }

    @Override
    @Transactional
    public void confirmAccountDeletion(UUID userId, String code) {
        User user = loadUser(userId);
        String provided = code == null ? null : code.trim();
        boolean fresh = user.getDeletionCodeExpiresAt() != null
                && !user.getDeletionCodeExpiresAt().isBefore(Instant.now());
        // Comparación en tiempo CONSTANTE del código.
        boolean codeOk = user.getDeletionCode() != null && provided != null
                && java.security.MessageDigest.isEqual(provided.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        user.getDeletionCode().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!(fresh && codeOk)) {
            // Un intento con código ERRÓNEO (dentro de la ventana) QUEMA el código, para que no se pueda
            // forzar por fuerza bruta el código de 6 dígitos durante su validez. Hay que solicitarlo de nuevo.
            if (fresh && !codeOk && user.getDeletionCode() != null) {
                user.setDeletionCode(null);
                user.setDeletionCodeExpiresAt(null);
                userRepository.update(user);
            }
            throw new BusinessException("DELETION_CODE_INVALID",
                    "El código de eliminación no es válido o ha expirado.");
        }
        String emailOriginal = user.getEmail();
        // A efectos del usuario es una ELIMINACIÓN (no puede acceder ni verla), pero por dentro solo se
        // DESACTIVA para poder recuperarla si decide volver: se conservan sus datos (perfil, direcciones,
        // pedidos). active=false hace que el login responda "cuenta desactivada" (UserDetails.disabled);
        // deleted_at la oculta de los listados. La reactivación la hace un ADMIN (active=true, deleted_at=null).
        user.setActive(false);
        user.setDeletedAt(Instant.now());
        // Y se corta el acceso SOCIAL: el login con Google no pasa por donde se evalúa el estado de la
        // cuenta, así que dejando el vínculo puesto se volvía a entrar con un clic — y con deleted_at la
        // cuenta ya no sale en el panel, de modo que nadie podía cerrarla otra vez. anonymise() (el borrado
        // del administrador) ya lo hacía; faltaba aquí.
        user.setGoogleLinked(false);
        user.setDeletionCode(null);
        user.setDeletionCodeExpiresAt(null);
        userRepository.update(user);
        // Ninguna sesión abierta puede sobrevivir a la desactivación.
        jwtRevocationService.revokeAllForClient(userId.toString());
        auditLogger.log("auth.account.deactivate", emailOriginal, Map.of(USERID, userId));
    }

    /**
     * Deja la cuenta sin datos que identifiquen a nadie.
     *
     * <p>Antes esto era una desactivación con otro nombre: se marcaba {@code deletedAt}, se ponía
     * {@code active = false} y nombre, email, teléfono y direcciones seguían en la base de datos
     * indefinidamente. Quien ejerce el derecho de supresión (art. 17 RGPD) tiene derecho a que se
     * supriman, no a que se oculten.
     *
     * <p>La fila se conserva porque los pedidos la referencian y borrarla rompería la contabilidad. Lo
     * que se va es el contenido personal. El email se sustituye por uno irrepetible del dominio
     * reservado {@code deleted.invalid} —que por RFC 2606 no puede existir— para no chocar con la
     * restricción de unicidad y para que el correo no pueda entregarse a nadie por accidente; de paso
     * queda libre para registrarse de nuevo.
     *
     * <p>Lo que NO se toca: los datos de facturación ya emitidos. Conservarlos no es una excepción que
     * nos inventemos, es una obligación legal (art. 17.3.b) y su plazo es el fiscal y mercantil.
     */
    static void anonymise(User user, String unusableHash) {
        user.setDeletedAt(Instant.now());
        user.setActive(false);
        user.setEmail("deleted-" + UUID.randomUUID() + "@deleted.invalid");
        user.setPasswordHash(unusableHash);
        user.setDisplayName(null);
        user.setFirstName(null);
        user.setLastName1(null);
        user.setLastName2(null);
        user.setCompanyName(null);
        user.setPhone(null);
        user.setAvatarUrl(null);
        user.setGoogleLinked(false);
        user.setDeletionCode(null);
        user.setDeletionCodeExpiresAt(null);
        user.setActivationCode(null);
        user.setActivationCodeExpiresAt(null);
        user.setLastLogin(null);
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
        return loadUser(id);
    }

    /**
     * Carga sin anotar para el uso interno de la clase: las llamadas a {@code findById} desde otros
     * métodos iban por {@code this}, así que su {@code @Transactional(readOnly)} nunca se aplicaba. Con
     * esto la anotación queda solo donde de verdad actúa, en la entrada por proxy.
     */
    private User loadUser(UUID id) {
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
        // NADIE SE CAMBIA EL ROL A SÍ MISMO. Tres líneas más abajo se revocan TODOS los tokens del
        // usuario —hace falta, o el rol viejo seguiría vivo en su token hasta una hora—, así que un
        // administrador que se degrade queda expulsado en el acto y sin forma de volver a entrar a
        // deshacerlo: la única salida sería tocar la base de datos a mano. Y si era el último
        // administrador, la plataforma se queda sin nadie que pueda administrarla.
        //
        // `deleteUser` ya se niega a borrar cuentas de administrador por lo mismo; esto cierra la otra
        // puerta, que lleva al mismo sitio y encima no tiene vuelta atrás desde la aplicación.
        String enSesion = SecurityUtils.currentSubject();
        if (enSesion != null && enSesion.equals(id.toString())) {
            throw new BusinessException("No puedes cambiar tu propio rol: perderías el acceso al panel "
                    + "en el acto y no podrías deshacerlo. Pídeselo a otro administrador.");
        }
        User u = loadUser(id);
        u.setRole(UserRole.valueOf(role.toUpperCase()));
        User updated = userRepository.update(u);
        // El rol viaja como claim en el access token (60 min). Sin revocar, un usuario degradado de ADMIN
        // conservaría los permisos de administrador hasta que su token caducara. Se invalidan TODOS sus
        // tokens (sub = userId): la próxima petición se corta con 401 y el SPA renueva con el rol nuevo.
        jwtRevocationService.revokeAllForClient(id.toString());
        auditLogger.log("admin.user_role_changed", u.getEmail(), Map.of("role", updated.getRole().name()));
        return updated;
    }

    /**
     * DROP-586: inline edit of a user's basic fields from the admin panel.
     * Only the fields present (non-null) in the patch are changed; the
     * {@code active} flag is applied only when explicitly provided.
     */
    @Override
    @Transactional
    public User editUser(UUID id, User patch, Boolean active) {
        User u = loadUser(id);
        // Partial update: null source fields are ignored by the update mapper.
        userUpdateMapper.updateFromModel(patch, u);
        if (active != null) {
            u.setActive(active);
            if (Boolean.FALSE.equals(active)) {
                // Desactivar sin revocar no expulsaba a nadie: el token seguía sirviendo hasta caducar y el
                // de refresco se canjeaba en un endpoint público por otro par. Reactivar no revoca nada.
                jwtRevocationService.revokeAllForClient(id.toString());
            }
        }
        return userRepository.update(u);
    }

    @Override
    @Transactional
    public User lock(UUID id, int minutes) {
        User u = loadUser(id);
        u.setLockedUntil(Instant.now().plus(minutes, ChronoUnit.MINUTES));
        User guardado = userRepository.update(u);
        // El bloqueo tiene que cerrar la sesión que ya estaba abierta. Sin esto, el operador bloqueaba a un
        // usuario abusivo y este ni se enteraba: seguía operando con su token hasta que caducaba.
        jwtRevocationService.revokeAllForClient(id.toString());
        return guardado;
    }

    @Override
    @Transactional
    public User unlock(UUID id) {
        User u = loadUser(id);
        u.setLockedUntil(null);
        u.setFailedLoginCount(0);
        return userRepository.update(u);
    }

    @Override
    @Transactional
    public User forceActivate(UUID id) {
        User u = loadUser(id);
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
                // Los borrados (auto-baja o borrado del admin) están anonimizados: no deben salir en la lista.
                .filter(u -> u.getDeletedAt() == null)
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
    public GoogleLoginOutcome resolveGoogleLogin(String email, String firstName, String lastName, String country) {
        String normalized = normalizeEmail(email);
        if (normalized == null || normalized.isBlank()) {
            // GoogleOAuth2SuccessHandler ya rechaza el login cuando el proveedor no devuelve correo, pero
            // este método no lo comprobaba y más abajo hace normalized.split("@") para el nombre visible:
            // llegar aquí sin correo creaba una cuenta sin identidad o reventaba con NullPointerException.
            throw new BusinessException("El proveedor no ha devuelto un correo con el que identificar la cuenta");
        }
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
        User user = User.builder().email(normalized).passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                .role(UserRole.USER).active(true).googleLinked(true)
                .displayName(display.isBlank() ? normalized.split("@")[0] : display).language("es")
                .country(normalizeCountry(country))
                // El alta social dejaba la cuenta SIN constancia de haber aceptado nada: la casilla se marca
                // en la pantalla de registro antes de ir al proveedor, pero eso solo vivía en el navegador y
                // aquí no llegaba nada. El resultado era un usuario en la base sin fecha ni versión, es decir
                // sin nada que enseñar el día que haya que acreditar el consentimiento (RGPD art. 7.1).
                // Se sella con el reloj del SERVIDOR y con la versión vigente que conoce el servidor, no la
                // que diga el cliente: en este flujo el cliente es el proveedor de identidad, que no sabe
                // nada de nuestros textos legales.
                .termsAcceptedAt(Instant.now()).termsAcceptedVersion(legalVersion).build();
        User saved = userRepository.save(user);
        auditLogger.log("auth.google.register", normalized, Map.of(USERID, saved.getId()));
        log.info("::> [GOOGLE-OAUTH2] New user registered userId={}", saved.getId());
        return new GoogleLoginOutcome(saved, false, normalized);
    }

    /**
     * País ISO-2 saneado para el alta social, o {@code null} si no es utilizable. Los CDN mandan "XX"/"T1"
     * (Tor) cuando no saben el país: se descartan para no persistir un país basura en el perfil.
     */
    private static String normalizeCountry(String country) {
        if (country == null) {
            return null;
        }
        String c = country.trim().toUpperCase();
        if (c.length() != 2 || "XX".equals(c) || "T1".equals(c)) {
            return null;
        }
        return c;
    }

    @Override
    @Transactional
    public void linkGoogleAccount(UUID id) {
        User user = loadUser(id);
        if (!user.isGoogleLinked()) {
            user.setGoogleLinked(true);
            userRepository.save(user);
            auditLogger.log("auth.google.link", user.getEmail(), Map.of(USERID, id));
            log.info("::> [GOOGLE-OAUTH2] Google identity linked to account userId={}", id);
        }
    }

    @Override
    @Transactional
    public void adminResetPassword(UUID id) {
        User user = loadUser(id);
        doRequestPasswordReset(user.getEmail());
        auditLogger.log("auth.admin.password_reset", user.getEmail(), Map.of(USERID, id));
    }

    @Override
    @Transactional
    public void deleteUser(UUID id) {
        User user = loadUser(id);
        if (user.getRole() == UserRole.ADMIN) {
            throw new BusinessException("No se puede eliminar una cuenta de administrador");
        }
        // Borrado SUAVE, igual que la auto-baja (confirmAccountDeletion). Un DELETE físico fallaba con una
        // violación de FK —"se hace referencia a un registro que no existe"— porque pedidos, facturas, el
        // libro de la wallet, favoritos y sesiones referencian al usuario; y esos datos deben CONSERVARSE
        // (obligación fiscal/contable, art. 17.3.b RGPD). Se anonimiza el PII, se desactiva, se borran las
        // direcciones (PII sin obligación de conservación) y se cierran todas sus sesiones.
        String emailOriginal = user.getEmail();
        anonymise(user, passwordEncoder.encode(randomToken(48)));
        userRepository.update(user);
        userAddressJpaRepository.deleteAll(userAddressJpaRepository.findByUser_IdOrderByIsDefaultDescCreatedAtDesc(id));
        jwtRevocationService.revokeAllForClient(id.toString());
        auditLogger.log("auth.admin.delete", emailOriginal, Map.of(USERID, id));
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
        User user = User.builder().email(normalized).passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                .role(r).active(false).activationCode(activationCode)
                .activationCodeExpiresAt(Instant.now().plus(ACTIVATION_TTL_HOURS, ChronoUnit.HOURS)).language("es")
                .build();
        User saved = userRepository.save(user);
        String inviteLang = InvoiceLabel.lang(saved.getLanguage());
        emailQueueService.enqueue(normalized, AuthEmailLabel.INVITE_SUBJECT.of(inviteLang), EMAILS_WELCOME,
                Map.of(TITLE, AuthEmailLabel.INVITE_TITLE.of(inviteLang), BODYHTML,
                        AuthEmailLabel.INVITE_BODY.of(inviteLang, ""), CTALABEL,
                        AuthEmailLabel.INVITE_CTA.of(inviteLang), CTAURL,
                        storefrontBaseUrl + "/activate?code=" + activationCode, "icon", CIRCLE_CHECK, FOOTERNOTE,
                        OrderEmailLabel.AUTO_NOTE.of(inviteLang), TAGLINE, BrandTagline.of(r, inviteLang)));
        auditLogger.log("auth.admin.invite", normalized, Map.of(USERID, saved.getId(), "role", r.name()));
        return saved;
    }

}
