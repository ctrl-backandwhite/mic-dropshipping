package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.ConflictException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.mapper.UserUpdateMapper;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.service.PasswordPolicy;
import com.nexaplatform.dropshipping.application.usecase.GoogleLoginOutcome;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas de cuenta que la suite existente no fija: baja de cuenta (borrado lógico con código), gestión
 * administrativa (bloqueo, borrado, invitación) y los filtros del listado del panel.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov10UserUseCaseImplTest {

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

    PasswordEncoder encoder = new BCryptPasswordEncoder(4); // coste bajo: los tests no son un banco
    PasswordPolicy policy = new PasswordPolicy();

    JwtRevocationService jwtRevocationService;
    UserUseCaseImpl useCase;

    @BeforeEach
    void setUp() throws Exception {
        jwtRevocationService = mock(JwtRevocationService.class);
        useCase = new UserUseCaseImpl(userRepository, resetTokenRepository, userJpaRepository, encoder, policy,
                emailQueueService, auditLogger, userUpdateMapper,
                mock(com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserAddressRepository.class),
                jwtRevocationService);
        Field baseUrl = UserUseCaseImpl.class.getDeclaredField("storefrontBaseUrl");
        baseUrl.setAccessible(true);
        baseUrl.set(useCase, "https://tienda.example");
        when(userRepository.update(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setId(UUID.randomUUID());
            }
            return u;
        });
    }

    /* ---------- alta ---------- */

    @Test
    void elNombreVisibleSeComponeConNombreYApellidosCuandoNoVieneDado() {
        User candidato = User.builder().email("ana@x.com").firstName(" Ana ").lastName1("Pérez ")
                .lastName2(" Gil").language("es").build();

        User creado = useCase.register(candidato, "Str0ngP@ssword!");

        // Sin esto la barra de navegación y los emails saludarían a un usuario sin nombre.
        assertThat(creado.getDisplayName()).isEqualTo("Ana Pérez Gil");
    }

    @Test
    void unAltaSinNingunNombreDejaElNombreVisibleVacioSinReventar() {
        User creado = useCase.register(User.builder().email("ana@x.com").build(), "Str0ngP@ssword!");

        assertThat(creado.getDisplayName()).isNull();
        // Sin idioma se asume español: el email tiene que salir en algún idioma.
        assertThat(creado.getLanguage()).isEqualTo("es");
    }

    @Test
    void elEnlaceDeActivacionApuntaAlDominioPublicoConfigurado() {
        useCase.register(User.builder().email("ana@x.com").displayName("Ana").build(), "Str0ngP@ssword!");

        assertThat(varsDelUltimoCorreo()).hasEntrySatisfying("ctaUrl",
                v -> assertThat((String) v).startsWith("https://tienda.example/activate?code="));
    }

    /* ---------- aviso de acceso ---------- */

    @Test
    void elAvisoDeAccesoLlevaLaFechaResueltaNoElMarcador() {
        when(userRepository.findByEmail("ana@x.com")).thenReturn(Optional.of(
                User.builder().id(UUID.randomUUID()).email("ana@x.com").displayName("Ana").language("es").build()));

        useCase.notifyLoginDetected(" Ana@X.com ");

        Map<String, Object> vars = varsDelUltimoCorreo();
        assertThat((String) vars.get("bodyHtml")).doesNotContain("{date}").contains("UTC");
        assertThat(vars).containsEntry("ctaUrl", "https://tienda.example/password-reset");
    }

    @Test
    void unAvisoDeAccesoDeUnCorreoDesconocidoNoEnviaNadaPeroSeAudita() {
        when(userRepository.findByEmail("nadie@x.com")).thenReturn(Optional.empty());

        useCase.notifyLoginDetected("nadie@x.com");

        verify(emailQueueService, never()).enqueue(anyString(), anyString(), anyString(), anyMap());
        verify(auditLogger).log(eq("auth.login_notify"), eq("nadie@x.com"), anyMap());
    }

    /* ---------- baja de cuenta ---------- */

    @Test
    void pedirLaBajaGeneraUnCodigoDeSeisDigitosConCaducidad() {
        UUID id = UUID.randomUUID();
        User u = User.builder().id(id).email("ana@x.com").displayName("Ana").language("es").build();
        when(userRepository.getById(id)).thenReturn(u);

        useCase.requestAccountDeletion(id);

        assertThat(u.getDeletionCode()).matches("\\d{6}");
        assertThat(u.getDeletionCodeExpiresAt()).isAfter(Instant.now());
        verify(emailQueueService).enqueue(eq("ana@x.com"), anyString(), eq("emails/account-deletion-code"), anyMap());
    }

    @Test
    void confirmarLaBajaConUnCodigoQueNoCoincideSeRechaza() {
        UUID id = UUID.randomUUID();
        User u = User.builder().id(id).email("ana@x.com").deletionCode("123456")
                .deletionCodeExpiresAt(Instant.now().plusSeconds(600)).active(true).build();
        when(userRepository.getById(id)).thenReturn(u);

        assertThatThrownBy(() -> useCase.confirmAccountDeletion(id, "999999"))
                .isInstanceOf(BusinessException.class);
        assertThat(u.getDeletedAt()).isNull();
        assertThat(u.isActive()).isTrue();
    }

    @Test
    void confirmarLaBajaConUnCodigoCaducadoSeRechaza() {
        UUID id = UUID.randomUUID();
        User u = User.builder().id(id).email("ana@x.com").deletionCode("123456")
                .deletionCodeExpiresAt(Instant.now().minusSeconds(1)).active(true).build();
        when(userRepository.getById(id)).thenReturn(u);

        assertThatThrownBy(() -> useCase.confirmAccountDeletion(id, "123456"))
                .isInstanceOf(BusinessException.class);
        assertThat(u.getDeletedAt()).isNull();
    }

    @Test
    void confirmarLaBajaSinHaberlaPedidoSeRechaza() {
        UUID id = UUID.randomUUID();
        User u = User.builder().id(id).email("ana@x.com").active(true).build();
        when(userRepository.getById(id)).thenReturn(u);

        assertThatThrownBy(() -> useCase.confirmAccountDeletion(id, "123456"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void laBajaEsLogicaLaFilaNoSeBorraSoloSeMarcaYDesactiva() {
        UUID id = UUID.randomUUID();
        User u = User.builder().id(id).email("ana@x.com").deletionCode("123456")
                .deletionCodeExpiresAt(Instant.now().plusSeconds(600)).active(true).build();
        when(userRepository.getById(id)).thenReturn(u);

        useCase.confirmAccountDeletion(id, "  123456  ");

        assertThat(u.getDeletedAt()).isNotNull();
        assertThat(u.isActive()).isFalse();
        assertThat(u.getDeletionCode()).isNull();
        assertThat(u.getDeletionCodeExpiresAt()).isNull();
        // Borrar la fila arrastraría pedidos, facturas y comisiones: la baja nunca es física.
        verify(userRepository, never()).delete(any());
    }

    /* ---------- contraseñas ---------- */

    @Test
    void unTokenDeRestablecimientoYaConsumidoNoSirveDosVeces() {
        PasswordResetTokenEntity prt = PasswordResetTokenEntity.builder()
                .user(UserEntity.builder().email("ana@x.com").build())
                .expiresAt(Instant.now().plusSeconds(600)).consumedAt(Instant.now()).build();
        when(resetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(prt));

        assertThatThrownBy(() -> useCase.confirmPasswordReset("token", "Str0ngP@ssword!"))
                .isInstanceOf(BusinessException.class);
        verify(userRepository, never()).update(any());
    }

    @Test
    void unTokenCaducadoNoRestableceLaContrasena() {
        PasswordResetTokenEntity prt = PasswordResetTokenEntity.builder()
                .user(UserEntity.builder().email("ana@x.com").build())
                .expiresAt(Instant.now().minusSeconds(1)).build();
        when(resetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(prt));

        assertThatThrownBy(() -> useCase.confirmPasswordReset("token", "Str0ngP@ssword!"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void restablecerLaContrasenaTambienDesbloqueaLaCuentaYQuemaElToken() {
        UUID id = UUID.randomUUID();
        UserEntity managed = UserEntity.builder().email("ana@x.com").build();
        managed.setId(id);
        PasswordResetTokenEntity prt = PasswordResetTokenEntity.builder().user(managed)
                .expiresAt(Instant.now().plusSeconds(600)).build();
        User u = User.builder().id(id).email("ana@x.com").failedLoginCount(5)
                .lockedUntil(Instant.now().plusSeconds(600)).build();
        when(resetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(prt));
        when(userRepository.getById(id)).thenReturn(u);

        useCase.confirmPasswordReset("token", "Str0ngP@ssword!");

        assertThat(encoder.matches("Str0ngP@ssword!", u.getPasswordHash())).isTrue();
        // Quien acaba de demostrar que controla el correo no puede quedarse fuera por el bloqueo previo.
        assertThat(u.getFailedLoginCount()).isZero();
        assertThat(u.getLockedUntil()).isNull();
        assertThat(prt.getConsumedAt()).isNotNull();
        verify(resetTokenRepository).save(prt);
    }

    @Test
    void cambiarLaContrasenaExigeLaPoliticaAntesDeTocarNada() {
        User u = User.builder().id(UUID.randomUUID()).email("ana@x.com").passwordHash("previo").build();

        assertThatThrownBy(() -> useCase.changePassword(u, "1234")).isInstanceOf(BusinessException.class);
        assertThat(u.getPasswordHash()).isEqualTo("previo");
        verify(userRepository, never()).update(any());
    }

    @Test
    void elAdminPuedeLanzarElRestablecimientoDeUnUsuario() {
        UUID id = UUID.randomUUID();
        User u = User.builder().id(id).email("ana@x.com").displayName("Ana").language("es").build();
        when(userRepository.getById(id)).thenReturn(u);
        when(userRepository.findByEmail("ana@x.com")).thenReturn(Optional.of(u));
        when(userJpaRepository.findById(id)).thenReturn(Optional.of(UserEntity.builder().email("ana@x.com").build()));

        useCase.adminResetPassword(id);

        verify(resetTokenRepository).save(any(PasswordResetTokenEntity.class));
        verify(auditLogger).log(eq("auth.admin.password_reset"), eq("ana@x.com"), anyMap());
    }

    /* ---------- gestión administrativa ---------- */

    @Test
    void cambiarElRolSinIndicarloSeRechaza() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> useCase.changeRole(id, null)).isInstanceOf(BusinessException.class);
        verify(userRepository, never()).update(any());
    }

    @Test
    void degradarAUnAdministradorInvalidaSusTokensEnCaliente() {
        UUID id = UUID.randomUUID();
        User u = User.builder().id(id).email("admin@x.com").role(UserRole.ADMIN).build();
        when(userRepository.getById(id)).thenReturn(u);

        useCase.changeRole(id, "user");

        assertThat(u.getRole()).isEqualTo(UserRole.USER);
        // El rol viaja en el token (60 min): sin revocar conservaría los permisos de admin hasta caducar.
        verify(jwtRevocationService).revokeAllForClient(id.toString());
    }

    @Test
    void bloquearYDesbloquearAjustanElCandadoYElContadorDeFallos() {
        UUID id = UUID.randomUUID();
        User u = User.builder().id(id).email("ana@x.com").failedLoginCount(5).build();
        when(userRepository.getById(id)).thenReturn(u);

        useCase.lock(id, 30);
        assertThat(u.getLockedUntil()).isAfter(Instant.now().plus(29, ChronoUnit.MINUTES));

        useCase.unlock(id);
        assertThat(u.getLockedUntil()).isNull();
        assertThat(u.getFailedLoginCount()).isZero();
    }

    @Test
    void activarAManoLimpiaElCodigoDeActivacionPendiente() {
        UUID id = UUID.randomUUID();
        User u = User.builder().id(id).email("ana@x.com").active(false).activationCode("CODE")
                .activationCodeExpiresAt(Instant.now().plusSeconds(60)).build();
        when(userRepository.getById(id)).thenReturn(u);

        useCase.forceActivate(id);

        assertThat(u.isActive()).isTrue();
        // Dejar el código vivo permitiría reactivar la cuenta con un enlace viejo.
        assertThat(u.getActivationCode()).isNull();
        assertThat(u.getActivationCodeExpiresAt()).isNull();
    }

    @Test
    void unaCuentaDeAdministradorNoSePuedeBorrar() {
        UUID id = UUID.randomUUID();
        when(userRepository.getById(id))
                .thenReturn(User.builder().id(id).email("admin@x.com").role(UserRole.ADMIN).build());

        assertThatThrownBy(() -> useCase.deleteUser(id)).isInstanceOf(BusinessException.class);
        verify(userRepository, never()).delete(any());
    }

    @Test
    void unaCuentaNormalSiSePuedeBorrar() {
        UUID id = UUID.randomUUID();
        when(userRepository.getById(id))
                .thenReturn(User.builder().id(id).email("ana@x.com").role(UserRole.USER).build());

        useCase.deleteUser(id);

        verify(userRepository).delete(id);
    }

    @Test
    void buscarUnUsuarioQueNoExisteDaNoEncontrado() {
        UUID id = UUID.randomUUID();
        when(userRepository.getById(id)).thenReturn(null);
        when(userRepository.findByEmail("nadie@x.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.findById(id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> useCase.findByEmail(" Nadie@X.com ")).isInstanceOf(NotFoundException.class);
    }

    /* ---------- invitaciones ---------- */

    @Test
    void noSePuedeInvitarAUnCorreoYaRegistrado() {
        when(userRepository.existsByEmail("ana@x.com")).thenReturn(true);

        assertThatThrownBy(() -> useCase.inviteUser(" Ana@X.com ", "USER")).isInstanceOf(ConflictException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void unRolInventadoEnLaInvitacionSeRechaza() {
        when(userRepository.existsByEmail("ana@x.com")).thenReturn(false);

        assertThatThrownBy(() -> useCase.inviteUser("ana@x.com", "SUPERJEFE")).isInstanceOf(BusinessException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void laInvitacionCreaUnaCuentaInactivaConCodigoYEnviaElEnlace() {
        when(userRepository.existsByEmail("ana@x.com")).thenReturn(false);

        User invitado = useCase.inviteUser(" Ana@X.com ", " operator ");

        assertThat(invitado.getEmail()).isEqualTo("ana@x.com");
        assertThat(invitado.getRole()).isEqualTo(UserRole.OPERATOR);
        assertThat(invitado.isActive()).isFalse();
        assertThat(invitado.getActivationCode()).isNotBlank();
        assertThat(invitado.getActivationCodeExpiresAt()).isAfter(Instant.now());
        // La contraseña se siembra aleatoria: nadie (ni el admin) puede entrar con la cuenta invitada.
        assertThat(invitado.getPasswordHash()).isNotBlank();
        assertThat(varsDelUltimoCorreo()).hasEntrySatisfying("ctaUrl",
                v -> assertThat((String) v).startsWith("https://tienda.example/activate?code="));
    }

    @Test
    void unaInvitacionSinRolCreaUnUsuarioNormal() {
        when(userRepository.existsByEmail("ana@x.com")).thenReturn(false);

        assertThat(useCase.inviteUser("ana@x.com", "  ").getRole()).isEqualTo(UserRole.USER);
    }

    /* ---------- listado del panel ---------- */

    @Test
    void elBuscadorDelPanelMiraCorreoNombreYEmpresa() {
        User porCorreo = User.builder().email("ana@x.com").role(UserRole.USER).build();
        User porNombre = User.builder().email("b@x.com").displayName("Ana Pérez").role(UserRole.USER).build();
        User porEmpresa = User.builder().email("c@x.com").companyName("ANA S.L.").role(UserRole.USER).build();
        User ajeno = User.builder().email("z@x.com").role(UserRole.USER).build();
        when(userRepository.findAll()).thenReturn(List.of(porCorreo, porNombre, porEmpresa, ajeno));

        assertThat(useCase.listUsers(null, " AnA ", null, 0, 25))
                .containsExactlyInAnyOrder(porCorreo, porNombre, porEmpresa);
    }

    @Test
    void elFiltroDePaisNoDistingueMayusculasYDescartaLosQueNoLoTienen() {
        User espana = User.builder().email("a@x.com").country("es").role(UserRole.USER).build();
        User sinPais = User.builder().email("b@x.com").role(UserRole.USER).build();
        when(userRepository.findAll()).thenReturn(List.of(espana, sinPais));

        assertThat(useCase.listUsers(null, null, "ES", 0, 25)).containsExactly(espana);
    }

    @Test
    void elListadoSaleDelMasNuevoAlMasViejoYLosSinFechaAlFinal() {
        User viejo = User.builder().email("v@x.com").role(UserRole.USER)
                .createdAt(Instant.now().minus(10, ChronoUnit.DAYS)).build();
        User nuevo = User.builder().email("n@x.com").role(UserRole.USER).createdAt(Instant.now()).build();
        User sinFecha = User.builder().email("s@x.com").role(UserRole.USER).build();
        when(userRepository.findAll()).thenReturn(List.of(viejo, sinFecha, nuevo));

        assertThat(useCase.listUsers(null, null, null, 0, 25)).containsExactly(nuevo, viejo, sinFecha);
    }

    @Test
    void unaPaginaMasAllaDelFinalDevuelveVacioEnVezDeReventar() {
        when(userRepository.findAll())
                .thenReturn(List.of(User.builder().email("a@x.com").role(UserRole.USER).build()));

        assertThat(useCase.listUsers(null, null, null, 9, 25)).isEmpty();
        assertThat(useCase.countUsers(null, null, null)).isEqualTo(1);
    }

    /* ---------- acceso con Google ---------- */

    @Test
    void googleSinCorreoNoPuedeCrearNiIdentificarUnaCuenta() {
        // Sin correo, más abajo se hace normalized.split("@") para el nombre visible: era una cuenta
        // sin identidad o un NullPointerException.
        assertThatThrownBy(() -> useCase.resolveGoogleLogin(null, "Ada", "Lovelace"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> useCase.resolveGoogleLogin("   ", "Ada", "Lovelace"))
                .isInstanceOf(BusinessException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void googleSinNombreUsaLaParteLocalDelCorreoComoNombreVisible() {
        when(userRepository.findByEmail("ada.lovelace@gmail.com")).thenReturn(Optional.empty());

        GoogleLoginOutcome outcome = useCase.resolveGoogleLogin("Ada.Lovelace@Gmail.com", null, null);

        assertThat(outcome.getUser().getDisplayName()).isEqualTo("ada.lovelace");
    }

    @Test
    void vincularUnaCuentaYaVinculadaNoRepiteNiGuardaNiAudita() {
        UUID id = UUID.randomUUID();
        when(userRepository.getById(id)).thenReturn(
                User.builder().id(id).email("ana@gmail.com").googleLinked(true).build());

        useCase.linkGoogleAccount(id);

        verify(userRepository, never()).save(any());
        verify(auditLogger, never()).log(eq("auth.google.link"), anyString(), anyMap());
    }

    @Test
    void actualizarUnUsuarioDelegaEnElRepositorio() {
        User u = User.builder().id(UUID.randomUUID()).email("ana@x.com").build();

        assertThat(useCase.updateUser(u)).isSameAs(u);
        verify(userRepository).update(u);
    }

    /* ---------- utilidades ---------- */

    private Map<String, Object> varsDelUltimoCorreo() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(emailQueueService).enqueue(anyString(), anyString(), anyString(), captor.capture());
        return captor.getValue();
    }
}
