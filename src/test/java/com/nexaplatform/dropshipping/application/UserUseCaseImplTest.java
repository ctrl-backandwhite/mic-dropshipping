package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.ConflictException;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.mapper.UserUpdateMapper;
import com.nexaplatform.dropshipping.application.service.PasswordPolicy;
import com.nexaplatform.dropshipping.application.usecase.impl.UserUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PasswordResetTokenEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PasswordResetTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mirrors {@code PriceRuleUseCaseImplTest}: unit tests for the consolidated
 * {@link UserUseCaseImpl}, mocking the {@link UserRepository} domain port and
 * the auth collaborators. Covers the logic that previously lived in
 * {@code AuthServiceTest} and {@code AdminUserQueryServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class UserUseCaseImplTest {

    @Mock UserRepository userRepository;
    @Mock PasswordResetTokenRepository resetTokenRepository;
    @Mock com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository userJpaRepository;
    @Mock EmailQueueService emailQueueService;
    @Mock AuditLogger auditLogger;
    @Mock UserUpdateMapper userUpdateMapper;

    PasswordEncoder encoder = new BCryptPasswordEncoder(4); // low cost for tests
    PasswordPolicy policy = new PasswordPolicy();

    UserUseCaseImpl useCase;

    @BeforeEach
    void setup() {
        useCase = new UserUseCaseImpl(userRepository, resetTokenRepository, userJpaRepository,
                encoder, policy, emailQueueService, auditLogger, userUpdateMapper);
    }

    @Test
    @DisplayName("register: crea usuario inactivo con código y rol USER")
    void register_creates_inactive_user_with_code() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        User u = useCase.register(
                User.builder().email("User@Example.com").displayName("Alice")
                        .companyName("Co").country("ES").language("es").build(),
                "Str0ngP@ssword!");

        assertThat(u.getEmail()).isEqualTo("user@example.com"); // normalized
        assertThat(u.getRole()).isEqualTo(UserRole.USER);
        assertThat(u.isActive()).isFalse();
        assertThat(u.getActivationCode()).isNotBlank();
        assertThat(u.getActivationCodeExpiresAt()).isAfter(Instant.now());
        assertThat(encoder.matches("Str0ngP@ssword!", u.getPasswordHash())).isTrue();

        verify(emailQueueService).enqueue(eq("user@example.com"), any(), eq("emails/welcome"), anyMap());
        verify(auditLogger).log(eq("auth.register"), eq("user@example.com"), anyMap());
    }

    @Test
    @DisplayName("register: rechaza email duplicado")
    void register_duplicate_email() {
        when(userRepository.existsByEmail("a@b.com")).thenReturn(true);
        assertThatThrownBy(() -> useCase.register(
                User.builder().email("a@b.com").language("es").build(), "Str0ngP@ssword!"))
                .isInstanceOf(ConflictException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("register: rechaza contraseña débil sin guardar")
    void register_weak_password() {
        assertThatThrownBy(() -> useCase.register(
                User.builder().email("a@b.com").language("es").build(), "weak"))
                .isInstanceOf(BusinessException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("activate: marca activo y limpia el código")
    void activate_marks_user_active() {
        User u = User.builder()
                .id(UUID.randomUUID())
                .email("a@b.com").role(UserRole.USER).active(false)
                .activationCode("CODE")
                .activationCodeExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        when(userRepository.findByActivationCode("CODE")).thenReturn(Optional.of(u));
        when(userRepository.update(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User activated = useCase.activate("CODE");
        assertThat(activated.isActive()).isTrue();
        assertThat(activated.getActivationCode()).isNull();
        assertThat(activated.getActivationCodeExpiresAt()).isNull();
    }

    @Test
    @DisplayName("activate: rechaza código expirado")
    void activate_rejects_expired() {
        User u = User.builder()
                .email("a@b.com").role(UserRole.USER).active(false)
                .activationCode("CODE")
                .activationCodeExpiresAt(Instant.now().minusSeconds(60))
                .build();
        when(userRepository.findByActivationCode("CODE")).thenReturn(Optional.of(u));
        assertThatThrownBy(() -> useCase.activate("CODE"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("recordFailedLogin: lockea después de 5 fallos")
    void lockout_after_five_failures() {
        User u = User.builder()
                .email("a@b.com").role(UserRole.USER).active(true).failedLoginCount(4).build();
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(u));
        when(userRepository.update(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        useCase.recordFailedLogin("a@b.com");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).update(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getFailedLoginCount()).isEqualTo(5);
        assertThat(saved.getLockedUntil()).isAfter(Instant.now());
        verify(auditLogger).log(eq("auth.lockout"), eq("a@b.com"), anyMap());
    }

    @Test
    @DisplayName("recordSuccessfulLogin: reinicia contador y lockedUntil")
    void successful_login_resets() {
        User u = User.builder()
                .email("a@b.com").role(UserRole.USER).active(true)
                .failedLoginCount(3)
                .lockedUntil(Instant.now().plusSeconds(60))
                .build();
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(u));
        when(userRepository.update(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        useCase.recordSuccessfulLogin("a@b.com");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).update(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getFailedLoginCount()).isZero();
        assertThat(saved.getLockedUntil()).isNull();
        assertThat(saved.getLastLogin()).isNotNull();
    }

    @Test
    @DisplayName("requestPasswordReset: misma respuesta exista o no el email (no enumeración)")
    void password_reset_no_enumeration() {
        UUID knownId = UUID.randomUUID();
        when(userRepository.findByEmail("known@x.com")).thenReturn(Optional.of(
                User.builder().id(knownId).email("known@x.com").role(UserRole.USER).active(true).build()));
        when(userRepository.findByEmail("unknown@x.com")).thenReturn(Optional.empty());
        when(userJpaRepository.findById(knownId)).thenReturn(Optional.of(
                com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity.builder()
                        .email("known@x.com").role(UserRole.USER).active(true).build()));

        useCase.requestPasswordReset("known@x.com");
        useCase.requestPasswordReset("unknown@x.com");

        verify(resetTokenRepository, times(1)).save(any(PasswordResetTokenEntity.class));
        verify(auditLogger, times(2)).log(eq("auth.password_reset.request"), matches(".*@x\\.com"), anyMap());
    }

    @Test
    @DisplayName("confirmPasswordReset: rechaza token consumido o expirado")
    void confirm_reset_invalid_token() {
        when(resetTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.confirmPasswordReset("xxx", "Str0ngP@ssword!"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("createAdminUser: OPERATOR role + active=true")
    void admin_creates_user() {
        when(userRepository.existsByEmail("op@x.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        User u = useCase.createAdminUser(
                User.builder().email("op@x.com").displayName("Op").build(), "Str0ngP@ssword!", "OPERATOR");
        assertThat(u.getRole()).isEqualTo(UserRole.OPERATOR);
        assertThat(u.isActive()).isTrue();
    }

    @Test
    @DisplayName("listUsers/countUsers: filtra por rol y pagina (newest-first)")
    void listUsers_filtersByRoleAndPaginates() {
        var admin = User.builder().email("a@x.com").role(UserRole.ADMIN).build();
        var user = User.builder().email("u@x.com").role(UserRole.USER).build();
        when(userRepository.findAll()).thenReturn(List.of(admin, user));

        List<User> page = useCase.listUsers("admin", null, null, 0, 25);
        int total = useCase.countUsers("admin", null, null);

        assertThat(page).containsExactly(admin);
        assertThat(total).isEqualTo(1);
    }

    @Test
    @DisplayName("changeRole: actualiza rol y persiste")
    void changeRole_updatesRoleAndSaves() {
        UUID id = UUID.randomUUID();
        var u = User.builder().id(id).email("u@x.com").role(UserRole.USER).build();
        when(userRepository.getById(id)).thenReturn(u);
        when(userRepository.update(u)).thenReturn(u);

        useCase.changeRole(id, "admin");

        assertThat(u.getRole()).isEqualTo(UserRole.ADMIN);
        verify(userRepository).update(u);
    }

    @Test
    @DisplayName("editUser: aplica parcial + flag active y persiste")
    void editUser_appliesPartialUpdateAndActiveFlagThenSaves() {
        UUID id = UUID.randomUUID();
        var u = User.builder().id(id).email("u@x.com").role(UserRole.USER).active(false).build();
        when(userRepository.getById(id)).thenReturn(u);
        when(userRepository.update(u)).thenReturn(u);
        var patch = User.builder().displayName("New Name").build();

        useCase.editUser(id, patch, true);

        verify(userUpdateMapper).updateFromModel(patch, u);
        assertThat(u.isActive()).isTrue();
        verify(userRepository).update(u);
    }
}
