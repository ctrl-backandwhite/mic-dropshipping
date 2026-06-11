package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.AuthDtos.CreateAdminUserRequest;
import com.nexaplatform.dropshipping.api.dto.AuthDtos.RegisterRequest;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.ConflictException;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.service.AuthService;
import com.nexaplatform.dropshipping.application.service.PasswordPolicy;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PasswordResetTokenEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PasswordResetTokenRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordResetTokenRepository resetTokenRepository;
    @Mock EmailQueueService emailQueueService;
    @Mock AuditLogger auditLogger;
    @Mock com.nexaplatform.dropshipping.api.mapper.AdminUserCreatedDtoMapper adminUserCreatedDtoMapper;

    PasswordEncoder encoder = new BCryptPasswordEncoder(4); // low cost for tests
    PasswordPolicy policy = new PasswordPolicy();

    AuthService authService;

    @BeforeEach
    void setup() {
        authService = new AuthService(userRepository, resetTokenRepository, encoder, policy, emailQueueService,
                auditLogger, adminUserCreatedDtoMapper);
    }

    @Test
    @DisplayName("register: crea usuario inactivo con código y rol USER")
    void register_creates_inactive_user_with_code() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        UserEntity u = authService.register(new RegisterRequest(
                "User@Example.com", "Str0ngP@ssword!", "Alice", "Co", "ES", "es"));

        assertThat(u.getEmail()).isEqualTo("user@example.com"); // normalized
        assertThat(u.getRole()).isEqualTo(UserRole.USER);
        assertThat(u.isActive()).isFalse();
        assertThat(u.getActivationCode()).isNotBlank();
        assertThat(u.getActivationCodeExpiresAt()).isAfter(Instant.now());
        assertThat(encoder.matches("Str0ngP@ssword!", u.getPasswordHash())).isTrue();

        // welcome email enqueued
        verify(emailQueueService).enqueue(eq("user@example.com"), any(), eq("emails/welcome"), anyMap());
        verify(auditLogger).log(eq("auth.register"), eq("user@example.com"), anyMap());
    }

    @Test
    @DisplayName("register: rechaza email duplicado")
    void register_duplicate_email() {
        when(userRepository.existsByEmail("a@b.com")).thenReturn(true);
        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("a@b.com", "Str0ngP@ssword!", null, null, null, "es")))
                .isInstanceOf(ConflictException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("register: rechaza contraseña débil sin guardar")
    void register_weak_password() {
        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("a@b.com", "weak", null, null, null, "es")))
                .isInstanceOf(BusinessException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("activate: marca activo y limpia el código")
    void activate_marks_user_active() {
        UserEntity u = UserEntity.builder()
                .email("a@b.com").role(UserRole.USER).active(false)
                .activationCode("CODE")
                .activationCodeExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        u.setId(UUID.randomUUID());
        when(userRepository.findByActivationCode("CODE")).thenReturn(Optional.of(u));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserEntity activated = authService.activate("CODE");
        assertThat(activated.isActive()).isTrue();
        assertThat(activated.getActivationCode()).isNull();
        assertThat(activated.getActivationCodeExpiresAt()).isNull();
    }

    @Test
    @DisplayName("activate: rechaza código expirado")
    void activate_rejects_expired() {
        UserEntity u = UserEntity.builder()
                .email("a@b.com").role(UserRole.USER).active(false)
                .activationCode("CODE")
                .activationCodeExpiresAt(Instant.now().minusSeconds(60))
                .build();
        when(userRepository.findByActivationCode("CODE")).thenReturn(Optional.of(u));
        assertThatThrownBy(() -> authService.activate("CODE"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("recordFailedLogin: lockea después de 5 fallos")
    void lockout_after_five_failures() {
        UserEntity u = UserEntity.builder()
                .email("a@b.com").role(UserRole.USER).active(true).failedLoginCount(4).build();
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(u));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.recordFailedLogin("a@b.com");

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());
        UserEntity saved = captor.getValue();
        assertThat(saved.getFailedLoginCount()).isEqualTo(5);
        assertThat(saved.getLockedUntil()).isAfter(Instant.now());
        verify(auditLogger).log(eq("auth.lockout"), eq("a@b.com"), anyMap());
    }

    @Test
    @DisplayName("recordSuccessfulLogin: reinicia contador y lockedUntil")
    void successful_login_resets() {
        UserEntity u = UserEntity.builder()
                .email("a@b.com").role(UserRole.USER).active(true)
                .failedLoginCount(3)
                .lockedUntil(Instant.now().plusSeconds(60))
                .build();
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(u));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.recordSuccessfulLogin("a@b.com");

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());
        UserEntity saved = captor.getValue();
        assertThat(saved.getFailedLoginCount()).isZero();
        assertThat(saved.getLockedUntil()).isNull();
        assertThat(saved.getLastLogin()).isNotNull();
    }

    @Test
    @DisplayName("requestPasswordReset: misma respuesta exista o no el email (no enumeración)")
    void password_reset_no_enumeration() {
        when(userRepository.findByEmail("known@x.com")).thenReturn(Optional.of(
                UserEntity.builder().email("known@x.com").role(UserRole.USER).active(true).build()));
        when(userRepository.findByEmail("unknown@x.com")).thenReturn(Optional.empty());

        authService.requestPasswordReset("known@x.com");
        authService.requestPasswordReset("unknown@x.com");

        // Token persisted only for known user
        verify(resetTokenRepository, times(1)).save(any(PasswordResetTokenEntity.class));
        // Audit fired for both
        verify(auditLogger, times(2)).log(eq("auth.password_reset.request"), matches(".*@x\\.com"), anyMap());
    }

    @Test
    @DisplayName("confirmPasswordReset: rechaza token consumido o expirado")
    void confirm_reset_invalid_token() {
        when(resetTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> authService.confirmPasswordReset("xxx", "Str0ngP@ssword!"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("createAdminUser: ADMIN role + active=true")
    void admin_creates_user() {
        when(userRepository.existsByEmail("op@x.com")).thenReturn(false);
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        UserEntity u = authService.createAdminUser(new CreateAdminUserRequest(
                "op@x.com", "Str0ngP@ssword!", "OPERATOR", "Op"));
        assertThat(u.getRole()).isEqualTo(UserRole.OPERATOR);
        assertThat(u.isActive()).isTrue();
    }
}
