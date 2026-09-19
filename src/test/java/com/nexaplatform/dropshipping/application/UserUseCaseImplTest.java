package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.application.mapper.UserUpdateMapper;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.service.PasswordPolicy;
import com.nexaplatform.dropshipping.application.usecase.impl.UserUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PasswordResetTokenEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PasswordResetTokenRepository;
import com.nexaplatform.dropshipping.infrastructure.security.oauth.JwtRevocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.mock;
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

    @Mock
    UserRepository userRepository;
    @Mock
    PasswordResetTokenRepository resetTokenRepository;
    @Mock
    com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository userJpaRepository;
    @Mock
    EmailQueueService emailQueueService;
    @Mock
    AuditLogger auditLogger;
    @Mock
    UserUpdateMapper userUpdateMapper;

    PasswordEncoder encoder = new BCryptPasswordEncoder(4); // low cost for tests
    PasswordPolicy policy = new PasswordPolicy();

    UserUseCaseImpl useCase;
    JwtRevocationService jwtRevocationService;

    @BeforeEach
    void setup() {
        jwtRevocationService = mock(JwtRevocationService.class);
        useCase = new UserUseCaseImpl(userRepository, resetTokenRepository, userJpaRepository, encoder, policy,
                emailQueueService, auditLogger, userUpdateMapper,
                mock(com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserAddressRepository.class),
                jwtRevocationService);
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

        User u = useCase.register(User.builder().email("User@Example.com").displayName("Alice").companyName("Co")
                .country("ES").language("es").build(), "Str0ngP@ssword!");

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
    @DisplayName("register: email duplicado NO revela nada (anti-enumeración) — no crea ni lanza")
    void register_duplicate_email() {
        when(userRepository.existsByEmail("a@b.com")).thenReturn(true);
        User candidate = User.builder().email("a@b.com").language("es").build();

        // Antes lanzaba ConflictException (→409, permitía enumerar). Ahora responde como el alta correcta:
        // devuelve un User (id efímero) SIN persistir ni enviar email de activación.
        User result = useCase.register(candidate, "Str0ngP@ssword!");
        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotNull();
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("register: rechaza contraseña débil sin guardar")
    void register_weak_password() {
        User candidate = User.builder().email("a@b.com").language("es").build();

        assertThatThrownBy(() -> useCase.register(candidate, "weak"))
                .isInstanceOf(BusinessException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("activate: marca activo y limpia el código")
    void activate_marks_user_active() {
        User u = User.builder().id(UUID.randomUUID()).email("a@b.com").role(UserRole.USER).active(false)
                .activationCode("CODE").activationCodeExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).build();
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
        User u = User.builder().email("a@b.com").role(UserRole.USER).active(false).activationCode("CODE")
                .activationCodeExpiresAt(Instant.now().minusSeconds(60)).build();
        when(userRepository.findByActivationCode("CODE")).thenReturn(Optional.of(u));
        assertThatThrownBy(() -> useCase.activate("CODE")).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("recordFailedLogin: lockea después de 5 fallos")
    void lockout_after_five_failures() {
        User u = User.builder().email("a@b.com").role(UserRole.USER).active(true).failedLoginCount(4).build();
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
        User u = User.builder().email("a@b.com").role(UserRole.USER).active(true).failedLoginCount(3)
                .lockedUntil(Instant.now().plusSeconds(60)).build();
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
        when(userRepository.findByEmail("known@x.com")).thenReturn(
                Optional.of(User.builder().id(knownId).email("known@x.com").role(UserRole.USER).active(true).build()));
        when(userRepository.findByEmail("unknown@x.com")).thenReturn(Optional.empty());
        when(userJpaRepository.findById(knownId))
                .thenReturn(Optional.of(UserEntity
                        .builder().email("known@x.com").role(UserRole.USER).active(true).build()));

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
        User u = useCase.createAdminUser(User.builder().email("op@x.com").displayName("Op").build(), "Str0ngP@ssword!",
                "OPERATOR");
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
    @DisplayName("un administrador NO puede cambiarse el rol a sí mismo y quedarse fuera")
    void changeRole_noSePuedeUnoDegradarASiMismo() {
        // Cambiar de rol revoca TODOS los tokens del usuario para que el rol viejo no siga vivo en el
        // token. Si el administrador se degrada a sí mismo, esa revocación lo expulsa en el acto y ya no
        // puede volver a entrar a deshacerlo: la única salida es tocar la base de datos a mano. Y si era
        // el último administrador, la plataforma se queda sin nadie que pueda administrarla.
        //
        // `deleteUser` ya protege las cuentas de administrador; esto cierra la misma puerta por el otro
        // lado, que tiene el mismo efecto y encima es irreversible desde la aplicación.
        // No hace falta preparar el repositorio: el rechazo llega ANTES de cargar a nadie, que es
        // justo lo que se quiere —ni se toca la base ni se revoca ningún token—.
        UUID id = UUID.randomUUID();
        autenticadoComo(id);

        assertThatThrownBy(() -> useCase.changeRole(id, "user"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("propio rol");

        verify(userRepository, never()).update(any());
        verify(jwtRevocationService, never()).revokeAllForClient(anyString());
    }

    @Test
    @DisplayName("cambiarle el rol a OTRA persona sigue funcionando igual")
    void changeRole_aOtroSigueFuncionando() {
        UUID otro = UUID.randomUUID();
        User u = User.builder().id(otro).email("u@x.com").role(UserRole.USER).build();
        when(userRepository.getById(otro)).thenReturn(u);
        when(userRepository.update(u)).thenReturn(u);
        autenticadoComo(UUID.randomUUID());

        useCase.changeRole(otro, "admin");

        assertThat(u.getRole()).isEqualTo(UserRole.ADMIN);
    }

    @org.junit.jupiter.api.AfterEach
    void limpiarLaSesion() {
        // El contexto de seguridad es un ThreadLocal: sin limpiarlo, la sesión de una prueba se cuela en
        // la siguiente y el resultado depende del orden en que se ejecuten.
        SecurityContextHolder.clearContext();
    }

    /** Deja en el contexto de seguridad al usuario indicado, como haría el filtro del token. */
    private static void autenticadoComo(UUID id) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id.toString(), "n/a", List.of()));
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

    @Test
    @DisplayName("resolveGoogleLogin: email nuevo crea cuenta activa, vinculada y rol USER")
    void googleLogin_newEmail_createsLinkedUser() {
        when(userRepository.findByEmail("new@gmail.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        var outcome = useCase.resolveGoogleLogin("New@Gmail.com", "Ada", "Lovelace", "us");

        assertThat(outcome.isLinkRequired()).isFalse();
        assertThat(outcome.getUser()).isNotNull();
        assertThat(outcome.getUser().getEmail()).isEqualTo("new@gmail.com");
        assertThat(outcome.getUser().getRole()).isEqualTo(UserRole.USER);
        assertThat(outcome.getUser().isActive()).isTrue();
        assertThat(outcome.getUser().isGoogleLinked()).isTrue();
        assertThat(outcome.getUser().getDisplayName()).isEqualTo("Ada Lovelace");
        // El país por IP (CDN) se persiste en el alta social, normalizado a mayúsculas.
        assertThat(outcome.getUser().getCountry()).isEqualTo("US");
    }

    @Test
    @DisplayName("resolveGoogleLogin: cuenta ya vinculada inicia sesión sin pedir confirmación")
    void googleLogin_alreadyLinked_signsIn() {
        var linked = User.builder().id(UUID.randomUUID()).email("me@gmail.com").role(UserRole.USER).active(true)
                .googleLinked(true).build();
        when(userRepository.findByEmail("me@gmail.com")).thenReturn(Optional.of(linked));

        var outcome = useCase.resolveGoogleLogin("me@gmail.com", "Me", null, null);

        assertThat(outcome.isLinkRequired()).isFalse();
        assertThat(outcome.getUser()).isSameAs(linked);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("resolveGoogleLogin: cuenta local sin vincular exige confirmación, no inicia sesión ni crea")
    void googleLogin_existingLocalAccount_requiresLink() {
        var local = User.builder().id(UUID.randomUUID()).email("local@gmail.com").role(UserRole.USER).active(true)
                .googleLinked(false).build();
        when(userRepository.findByEmail("local@gmail.com")).thenReturn(Optional.of(local));

        var outcome = useCase.resolveGoogleLogin("local@gmail.com", "L", "Ocal", "es");

        assertThat(outcome.isLinkRequired()).isTrue();
        assertThat(outcome.getUser()).isNull();
        assertThat(outcome.getEmail()).isEqualTo("local@gmail.com");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("linkGoogleAccount: marca googleLinked y persiste")
    void linkGoogleAccount_setsFlagAndSaves() {
        UUID id = UUID.randomUUID();
        var u = User.builder().id(id).email("u@gmail.com").role(UserRole.USER).active(true).googleLinked(false).build();
        when(userRepository.getById(id)).thenReturn(u);
        when(userRepository.save(u)).thenReturn(u);

        useCase.linkGoogleAccount(id);

        assertThat(u.isGoogleLinked()).isTrue();
        verify(userRepository).save(u);
        verify(auditLogger).log(eq("auth.google.link"), eq("u@gmail.com"), anyMap());
    }
}
