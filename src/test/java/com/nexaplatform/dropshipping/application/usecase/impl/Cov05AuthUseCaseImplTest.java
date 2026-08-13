package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.dto.in.ActivateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.DeleteAccountConfirmDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.LoginDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.PasswordResetConfirmDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.PasswordResetRequestDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.RefreshTokenDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.UpdateProfileDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.MeDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.mapper.UserDtoMapper;
import com.nexaplatform.dropshipping.application.service.DeviceSessionService;
import com.nexaplatform.dropshipping.application.usecase.UserUseCase;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.infrastructure.security.jwt.UserTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Comportamiento del caso de uso de autenticación que NO cubre la suite existente: la resolución del
 * usuario autenticado, la edición de perfil (que recompone el nombre visible y normaliza el país), la
 * tolerancia del login a fallos de avisos, y las operaciones que exigen sesión (baja de cuenta).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov05AuthUseCaseImplTest {

    @Mock
    UserUseCase userUseCase;
    @Mock
    AuthenticationManager authenticationManager;
    @Mock
    PasswordEncoder passwordEncoder;
    @Mock
    UserDtoMapper mapper;
    @Mock
    DeviceSessionService deviceSessionService;
    @Mock
    UserTokenService userTokenService;
    @Mock
    com.nexaplatform.dropshipping.application.service.TotpService totpService;

    @InjectMocks
    AuthUseCaseImpl useCase;

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeEach
    void setUp() {
        when(mapper.toMeDtoOut(any(User.class), anySet())).thenReturn(MeDtoOut.builder().build());
        when(userTokenService.issue(any(), any(), any(), anySet()))
                .thenReturn(new UserTokenService.Tokens("access", "refresh", 3600L));
    }

    private static User user() {
        return User.builder().id(USER_ID).email("ana@example.com").role(UserRole.USER).active(true)
                .passwordHash("$2a$hash").build();
    }

    private static Authentication authOf(String name) {
        return new UsernamePasswordAuthenticationToken(name, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    /* ===================== quién soy ===================== */

    @Test
    @DisplayName("sin autenticación no se devuelve ningún perfil")
    void sinAutenticacionNoHayPerfil() {
        assertThat(useCase.me(null)).isNull();
        verify(userUseCase, never()).findById(any());
    }

    @Test
    @DisplayName("un sujeto sin nombre tampoco devuelve perfil")
    void unSujetoSinNombreTampocoDevuelvePerfil() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(null);

        assertThat(useCase.me(auth)).isNull();
    }

    @Test
    @DisplayName("el perfil se resuelve por el id del token cuando el sujeto es un UUID")
    void elPerfilSeResuelvePorIdCuandoElSujetoEsUuid() {
        when(userUseCase.findById(USER_ID)).thenReturn(user());

        assertThat(useCase.me(authOf(USER_ID.toString()))).isNotNull();
        verify(userUseCase).findById(USER_ID);
        verify(userUseCase, never()).findByEmail(any());
    }

    @Test
    @DisplayName("si el sujeto no es un UUID se resuelve por email (tokens heredados)")
    void elPerfilSeResuelvePorEmailCuandoElSujetoNoEsUuid() {
        when(userUseCase.findByEmail("ana@example.com")).thenReturn(user());
        when(userUseCase.findById(USER_ID)).thenReturn(user());

        assertThat(useCase.me(authOf("ana@example.com"))).isNotNull();
        verify(userUseCase).findByEmail("ana@example.com");
        verify(userUseCase).findById(USER_ID);
    }

    /* ===================== edición de perfil ===================== */

    private UpdateProfileDtoIn.UpdateProfileDtoInBuilder profile() {
        return UpdateProfileDtoIn.builder();
    }

    @Test
    @DisplayName("al cambiar alguna parte del nombre se recompone el nombre visible completo")
    void alCambiarElNombreSeRecomponeElNombreVisible() {
        User stored = user();
        when(userUseCase.findById(USER_ID)).thenReturn(stored);
        when(userUseCase.updateUser(any())).thenAnswer(i -> i.getArgument(0));
        when(mapper.fullName(stored)).thenReturn("Ana Gómez Ruiz");

        useCase.updateProfile(authOf(USER_ID.toString()),
                profile().firstName("  Ana  ").lastName1("Gómez").lastName2("Ruiz").displayName("IGNORADO").build());

        assertThat(stored.getFirstName()).isEqualTo("Ana");
        assertThat(stored.getLastName1()).isEqualTo("Gómez");
        assertThat(stored.getLastName2()).isEqualTo("Ruiz");
        // El nombre visible se DERIVA de las partes: si se aceptara el enviado, quedaría descuadrado.
        assertThat(stored.getDisplayName()).isEqualTo("Ana Gómez Ruiz");
    }

    @Test
    @DisplayName("el nombre visible enviado solo se usa si no llega ninguna parte del nombre")
    void elNombreVisibleEnviadoSoloSeUsaSinPartesDelNombre() {
        User stored = user();
        when(userUseCase.findById(USER_ID)).thenReturn(stored);
        when(userUseCase.updateUser(any())).thenAnswer(i -> i.getArgument(0));

        useCase.updateProfile(authOf(USER_ID.toString()), profile().displayName("  Anita  ").build());

        assertThat(stored.getDisplayName()).isEqualTo("Anita");
        verify(mapper, never()).fullName(any());
    }

    @Test
    @DisplayName("el país se guarda siempre en mayúsculas y sin espacios")
    void elPaisSeGuardaNormalizado() {
        User stored = user();
        when(userUseCase.findById(USER_ID)).thenReturn(stored);
        when(userUseCase.updateUser(any())).thenAnswer(i -> i.getArgument(0));

        useCase.updateProfile(authOf(USER_ID.toString()), profile().country("  es  ").build());

        // El país decide impuestos y envío: un "es " frente a "ES" partiría las tablas por país.
        assertThat(stored.getCountry()).isEqualTo("ES");
    }

    @Test
    @DisplayName("los campos que no se envían no se borran del perfil")
    void losCamposQueNoSeEnvianNoSeBorran() {
        User stored = user();
        stored.setFirstName("Ana");
        stored.setCompanyName("ACME");
        stored.setCountry("ES");
        stored.setLanguage("es");
        stored.setDisplayName("Ana");
        when(userUseCase.findById(USER_ID)).thenReturn(stored);
        when(userUseCase.updateUser(any())).thenAnswer(i -> i.getArgument(0));

        useCase.updateProfile(authOf(USER_ID.toString()), profile().build());

        assertThat(stored.getFirstName()).isEqualTo("Ana");
        assertThat(stored.getCompanyName()).isEqualTo("ACME");
        assertThat(stored.getCountry()).isEqualTo("ES");
        assertThat(stored.getLanguage()).isEqualTo("es");
        assertThat(stored.getDisplayName()).isEqualTo("Ana");
    }

    @Test
    @DisplayName("la empresa y el idioma se actualizan cuando llegan")
    void laEmpresaYElIdiomaSeActualizan() {
        User stored = user();
        when(userUseCase.findById(USER_ID)).thenReturn(stored);
        when(userUseCase.updateUser(any())).thenAnswer(i -> i.getArgument(0));

        useCase.updateProfile(authOf(USER_ID.toString()),
                profile().companyName("  Nexa SL  ").language("pt").build());

        assertThat(stored.getCompanyName()).isEqualTo("Nexa SL");
        assertThat(stored.getLanguage()).isEqualTo("pt");
    }

    @Test
    @DisplayName("el perfil devuelto es el que acaba de persistirse, no el de entrada")
    void elPerfilDevueltoEsElPersistido() {
        User stored = user();
        User persisted = user();
        persisted.setDisplayName("Ana Gómez");
        when(userUseCase.findById(USER_ID)).thenReturn(stored);
        when(userUseCase.updateUser(stored)).thenReturn(persisted);

        useCase.updateProfile(authOf(USER_ID.toString()), profile().companyName("ACME").build());

        verify(mapper).toMeDtoOut(eq(persisted), anySet());
    }

    /* ===================== login: efectos colaterales ===================== */

    @Test
    @DisplayName("un fallo al avisar del inicio de sesión no puede impedir entrar")
    void unFalloAlAvisarNoImpideEntrar() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getSession(false)).thenReturn(null);
        when(authenticationManager.authenticate(any())).thenReturn(authOf(USER_ID.toString()));
        when(userUseCase.findById(USER_ID)).thenReturn(user());
        when(mapper.toMeDtoOut(any(User.class), anySet())).thenReturn(MeDtoOut.builder().build());
        doThrow(new IllegalStateException("SMTP caído")).when(userUseCase).notifyLoginDetected("ana@example.com");
        LoginDtoIn req = LoginDtoIn.builder().email("ana@example.com").password("pw").build();

        assertThat(useCase.login(req, request, response).getToken()).isEqualTo("access");
    }

    @Test
    @DisplayName("el último acceso se registra con el email real, no con el id del token")
    void elUltimoAccesoSeRegistraConElEmailReal() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getSession(false)).thenReturn(null);
        when(authenticationManager.authenticate(any())).thenReturn(authOf(USER_ID.toString()));
        when(userUseCase.findById(USER_ID)).thenReturn(user());

        useCase.login(LoginDtoIn.builder().email("ana@example.com").password("pw").build(), request, response);

        verify(userUseCase).recordSuccessfulLogin("ana@example.com");
    }

    @Test
    @DisplayName("el vínculo social por token solo se aplica DESPUÉS de autenticar con contraseña")
    void elVinculoSocialSeAplicaTrasAutenticar() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getSession(false)).thenReturn(null);
        when(authenticationManager.authenticate(any())).thenReturn(authOf(USER_ID.toString()));
        when(userUseCase.findById(USER_ID)).thenReturn(user());
        LoginDtoIn req = LoginDtoIn.builder().email("ana@example.com").password("pw").linkSocial(true).build();

        useCase.login(req, request, response);

        verify(userUseCase).linkGoogleAccount(USER_ID);
    }

    @Test
    @DisplayName("sin petición explícita de vínculo social no se enlaza ninguna cuenta de Google")
    void sinPeticionDeVinculoNoSeEnlazaGoogle() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getSession(false)).thenReturn(null);
        when(authenticationManager.authenticate(any())).thenReturn(authOf(USER_ID.toString()));
        when(userUseCase.findById(USER_ID)).thenReturn(user());

        useCase.login(LoginDtoIn.builder().email("ana@example.com").password("pw").build(), request, response);

        verify(userUseCase, never()).linkGoogleAccount(any());
    }

    /* ===================== refresco ===================== */

    @Test
    @DisplayName("al refrescar, las autoridades salen del rol persistido y no del token viejo")
    void alRefrescarLasAutoridadesSalenDelRolPersistido() {
        User admin = user();
        admin.setRole(UserRole.ADMIN);
        when(userTokenService.validateAndRotate("rt")).thenReturn(USER_ID);
        when(userUseCase.findById(USER_ID)).thenReturn(admin);

        useCase.refresh(new RefreshTokenDtoIn("rt"));

        // Un token robado con autoridades infladas no puede reinyectarlas al refrescar.
        verify(userTokenService).issue(USER_ID, "ana@example.com", "ADMIN", Set.of("ROLE_ADMIN"));
    }

    /* ===================== activación y contraseña olvidada ===================== */

    @Test
    @DisplayName("activar delega el código tal cual en el caso de uso de usuarios")
    void activarDelegaElCodigo() {
        useCase.activate(ActivateDtoIn.builder().code("ABC123").build());

        verify(userUseCase).activate("ABC123");
    }

    @Test
    @DisplayName("pedir el restablecimiento delega el email")
    void pedirRestablecimientoDelegaElEmail() {
        useCase.requestReset(PasswordResetRequestDtoIn.builder().email("ana@example.com").build());

        verify(userUseCase).requestPasswordReset("ana@example.com");
    }

    @Test
    @DisplayName("confirmar el restablecimiento entrega token y contraseña nueva juntos")
    void confirmarRestablecimientoEntregaTokenYPassword() {
        useCase.confirmReset(PasswordResetConfirmDtoIn.builder().token("tok").newPassword("N3w-P@ssw0rd").build());

        verify(userUseCase).confirmPasswordReset("tok", "N3w-P@ssw0rd");
    }

    /* ===================== baja de cuenta ===================== */

    @Test
    @DisplayName("no se puede pedir la baja de la cuenta sin sesión")
    void noSePuedePedirLaBajaSinSesion() {
        assertThatThrownBy(() -> useCase.requestAccountDeletion(null)).isInstanceOf(BusinessException.class);
        verify(userUseCase, never()).requestAccountDeletion(any());
    }

    @Test
    @DisplayName("no se puede confirmar la baja de la cuenta sin sesión")
    void noSePuedeConfirmarLaBajaSinSesion() {
        DeleteAccountConfirmDtoIn req = DeleteAccountConfirmDtoIn.builder().code("123456").build();

        assertThatThrownBy(() -> useCase.confirmAccountDeletion(null, req)).isInstanceOf(BusinessException.class);
        verify(userUseCase, never()).confirmAccountDeletion(any(), any());
    }

    @Test
    @DisplayName("la baja se pide y se confirma siempre sobre el usuario de la sesión")
    void laBajaSeAplicaSobreElUsuarioDeLaSesion() {
        Authentication auth = authOf(USER_ID.toString());

        useCase.requestAccountDeletion(auth);
        useCase.confirmAccountDeletion(auth, DeleteAccountConfirmDtoIn.builder().code("123456").build());

        // El id sale del token, nunca de la petición: nadie puede dar de baja a otro.
        verify(userUseCase).requestAccountDeletion(USER_ID);
        verify(userUseCase).confirmAccountDeletion(USER_ID, "123456");
    }

    /* ===================== cierre de sesión ===================== */

    @Test
    @DisplayName("cerrar sesión con un sujeto sin nombre no revoca nada")
    void cerrarSesionSinNombreNoRevocaNada() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(null);

        assertThatCode(() -> useCase.logout(auth)).doesNotThrowAnyException();
        verify(userTokenService, never()).revokeAll(any());
    }

    @Test
    @DisplayName("el login emite el par de tokens con el rol del usuario persistido")
    void elLoginEmiteLosTokensConElRolDelUsuario() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getSession(false)).thenReturn(null);
        when(authenticationManager.authenticate(any())).thenReturn(authOf(USER_ID.toString()));
        when(userUseCase.findById(USER_ID)).thenReturn(user());

        useCase.login(LoginDtoIn.builder().email("ana@example.com").password("pw").build(), request, response);

        verify(userTokenService).issue(USER_ID, "ana@example.com", "USER", Set.of("ROLE_USER"));
    }
}
