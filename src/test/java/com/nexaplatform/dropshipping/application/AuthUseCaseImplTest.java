package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.in.ChangePasswordDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.LoginDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.RefreshTokenDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.RegisterDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.LoginDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.RegisterDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.mapper.UserDtoMapper;
import com.nexaplatform.dropshipping.application.service.DeviceSessionService;
import com.nexaplatform.dropshipping.application.usecase.UserUseCase;
import com.nexaplatform.dropshipping.application.usecase.impl.AuthUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.infrastructure.security.jwt.UserTokenService;
import com.nexaplatform.dropshipping.infrastructure.security.oauth.GoogleOAuth2SuccessHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthUseCaseImpl}: the security-relevant behaviour of the
 * auth orchestration layer. Mocks every injected collaborator (the
 * {@link AuthenticationManager} that actually verifies credentials, the
 * {@link UserUseCase} domain port, the {@link UserTokenService} that rotates
 * refresh tokens, etc.) and focuses on email normalization, the generic 401
 * (anti-enumeration), refresh-token rotation, the pending Google account link
 * and the current-password check on change-password.
 */
@ExtendWith(MockitoExtension.class)
class AuthUseCaseImplTest {

    @Mock
    UserUseCase userUseCase;
    @Mock
    AuthenticationManager authenticationManager;
    @Mock
    org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Mock
    UserDtoMapper mapper;
    @Mock
    DeviceSessionService deviceSessionService;
    @Mock
    UserTokenService userTokenService;

    @InjectMocks
    AuthUseCaseImpl useCase;

    private static User user(UUID id, String email) {
        return User.builder().id(id).email(email).role(UserRole.USER).active(true).passwordHash("$2a$hash").build();
    }

    private static UserTokenService.Tokens tokens() {
        return new UserTokenService.Tokens("access-jwt", "refresh-jwt", 3600L);
    }

    /* ============ register ============ */

    @Test
    @DisplayName("register: delega en UserUseCase con el raw password y devuelve userId + mensaje de activación")
    void register_delegatesAndBuildsResponse() {
        UUID id = UUID.randomUUID();
        RegisterDtoIn req = RegisterDtoIn.builder().email("User@Example.com").password("Str0ngP@ssword!")
                .displayName("Alice").language("es").build();
        User mapped = User.builder().email("User@Example.com").displayName("Alice").build();
        when(mapper.toDomain(req)).thenReturn(mapped);
        when(userUseCase.register(mapped, "Str0ngP@ssword!")).thenReturn(user(id, "user@example.com"));

        RegisterDtoOut out = useCase.register(req);

        assertThat(out.getUserId()).isEqualTo(id);
        assertThat(out.getMessage()).contains("activate");
        verify(userUseCase).register(mapped, "Str0ngP@ssword!");
    }

    /* ============ login: normalización + 401 genérico ============ */

    @Test
    @DisplayName("login: normaliza el email (lowercase + trim) antes de autenticar")
    void login_normalizesEmailBeforeAuthenticating() {
        UUID id = UUID.randomUUID();
        LoginDtoIn req = LoginDtoIn.builder().email("  User@Example.COM  ").password("pw").build();
        HttpServletRequest httpRequest = org.mockito.Mockito.mock(HttpServletRequest.class);
        HttpServletResponse httpResponse = org.mockito.Mockito.mock(HttpServletResponse.class);

        Authentication auth = new UsernamePasswordAuthenticationToken(id.toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(userUseCase.findById(id)).thenReturn(user(id, "user@example.com"));
        when(httpRequest.getSession(false)).thenReturn(null);
        when(userTokenService.issue(eq(id), any(), eq("USER"), anySet())).thenReturn(tokens());
        when(mapper.toMeDtoOut(any(User.class), anySet())).thenReturn(MeDtoOut.builder().build());

        useCase.login(req, httpRequest, httpResponse);

        ArgumentCaptor<UsernamePasswordAuthenticationToken> captor =
                ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());
        // El principal pasado al AuthenticationManager va normalizado.
        assertThat(captor.getValue().getName()).isEqualTo("user@example.com");
        assertThat(captor.getValue().getCredentials()).isEqualTo("pw");
    }

    @Test
    @DisplayName("login: emite el par de tokens y arma el LoginDtoOut con las authorities del Authentication")
    void login_issuesTokensAndBuildsResponse() {
        UUID id = UUID.randomUUID();
        LoginDtoIn req = LoginDtoIn.builder().email("user@example.com").password("pw").build();
        HttpServletRequest httpRequest = org.mockito.Mockito.mock(HttpServletRequest.class);
        HttpServletResponse httpResponse = org.mockito.Mockito.mock(HttpServletResponse.class);

        Authentication auth = new UsernamePasswordAuthenticationToken(id.toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(userUseCase.findById(id)).thenReturn(user(id, "user@example.com"));
        when(httpRequest.getSession(false)).thenReturn(null);
        when(userTokenService.issue(eq(id), eq("user@example.com"), eq("USER"), anySet())).thenReturn(tokens());
        when(mapper.toMeDtoOut(any(User.class), anySet())).thenReturn(MeDtoOut.builder().build());

        LoginDtoOut out = useCase.login(req, httpRequest, httpResponse);

        assertThat(out.getToken()).isEqualTo("access-jwt");
        assertThat(out.getRefreshToken()).isEqualTo("refresh-jwt");
        assertThat(out.getTokenType()).isEqualTo("Bearer");
        assertThat(out.getExpiresIn()).isEqualTo(3600L);
        verify(deviceSessionService).recordLogin(id, httpRequest, httpResponse);
    }

    @Test
    @DisplayName("login: usuario inexistente -> propaga BadCredentials genérico (anti-enumeración)")
    void login_unknownUser_propagatesGenericBadCredentials() {
        LoginDtoIn req = LoginDtoIn.builder().email("ghost@example.com").password("pw").build();
        HttpServletRequest httpRequest = org.mockito.Mockito.mock(HttpServletRequest.class);
        HttpServletResponse httpResponse = org.mockito.Mockito.mock(HttpServletResponse.class);
        // El AuthenticationManager no revela si el usuario existe: mismo BadCredentials.
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> useCase.login(req, httpRequest, httpResponse))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Bad credentials");

        // No se filtra estado: no se carga usuario, ni se emiten tokens, ni se registra dispositivo.
        verify(userUseCase, never()).findById(any());
        verify(userTokenService, never()).issue(any(), any(), any(), anySet());
        verify(deviceSessionService, never()).recordLogin(any(), any(), any());
    }

    @Test
    @DisplayName("login: password incorrecta -> MISMA excepción y mensaje que usuario inexistente")
    void login_wrongPassword_sameGenericError() {
        LoginDtoIn req = LoginDtoIn.builder().email("real@example.com").password("wrong").build();
        HttpServletRequest httpRequest = org.mockito.Mockito.mock(HttpServletRequest.class);
        HttpServletResponse httpResponse = org.mockito.Mockito.mock(HttpServletResponse.class);
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        // Idéntico tipo y mensaje que el caso "usuario inexistente": indistinguible para el cliente.
        assertThatThrownBy(() -> useCase.login(req, httpRequest, httpResponse))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Bad credentials");
        verify(userTokenService, never()).issue(any(), any(), any(), anySet());
    }

    /* ============ login: enlace de cuenta Google pendiente ============ */

    @Test
    @DisplayName("login: completa el enlace Google pendiente si el email de sesión coincide")
    void login_completesPendingGoogleLink_whenEmailMatches() {
        UUID id = UUID.randomUUID();
        LoginDtoIn req = LoginDtoIn.builder().email("me@example.com").password("pw").build();
        HttpServletRequest httpRequest = org.mockito.Mockito.mock(HttpServletRequest.class);
        HttpServletResponse httpResponse = org.mockito.Mockito.mock(HttpServletResponse.class);
        HttpSession session = org.mockito.Mockito.mock(HttpSession.class);

        Authentication auth = new UsernamePasswordAuthenticationToken(id.toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(userUseCase.findById(id)).thenReturn(user(id, "me@example.com"));
        when(httpRequest.getSession(false)).thenReturn(session);
        when(session.getAttribute(GoogleOAuth2SuccessHandler.PENDING_GOOGLE_LINK_EMAIL))
                .thenReturn("ME@example.com"); // case-insensitive match
        when(userTokenService.issue(eq(id), any(), any(), anySet())).thenReturn(tokens());
        when(mapper.toMeDtoOut(any(User.class), anySet())).thenReturn(MeDtoOut.builder().build());

        useCase.login(req, httpRequest, httpResponse);

        verify(userUseCase).linkGoogleAccount(id);
        verify(session).removeAttribute(GoogleOAuth2SuccessHandler.PENDING_GOOGLE_LINK_EMAIL);
    }

    @Test
    @DisplayName("login: NO enlaza Google si el email de sesión es de otra cuenta (sesión reutilizada)")
    void login_doesNotLinkGoogle_whenEmailMismatch() {
        UUID id = UUID.randomUUID();
        LoginDtoIn req = LoginDtoIn.builder().email("me@example.com").password("pw").build();
        HttpServletRequest httpRequest = org.mockito.Mockito.mock(HttpServletRequest.class);
        HttpServletResponse httpResponse = org.mockito.Mockito.mock(HttpServletResponse.class);
        HttpSession session = org.mockito.Mockito.mock(HttpSession.class);

        Authentication auth = new UsernamePasswordAuthenticationToken(id.toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(userUseCase.findById(id)).thenReturn(user(id, "me@example.com"));
        when(httpRequest.getSession(false)).thenReturn(session);
        when(session.getAttribute(GoogleOAuth2SuccessHandler.PENDING_GOOGLE_LINK_EMAIL))
                .thenReturn("someone-else@example.com");
        when(userTokenService.issue(eq(id), any(), any(), anySet())).thenReturn(tokens());
        when(mapper.toMeDtoOut(any(User.class), anySet())).thenReturn(MeDtoOut.builder().build());

        useCase.login(req, httpRequest, httpResponse);

        verify(userUseCase, never()).linkGoogleAccount(any());
        verify(session, never()).removeAttribute(any());
    }

    /* ============ refresh: rotación del refresh token ============ */

    @Test
    @DisplayName("refresh: valida+rota el refresh token vía UserTokenService y emite un nuevo par")
    void refresh_validatesAndRotatesThenIssues() {
        UUID id = UUID.randomUUID();
        RefreshTokenDtoIn req = new RefreshTokenDtoIn("the-refresh-jwt");
        when(userTokenService.validateAndRotate("the-refresh-jwt")).thenReturn(id);
        when(userUseCase.findById(id)).thenReturn(user(id, "user@example.com"));
        when(userTokenService.issue(eq(id), eq("user@example.com"), eq("USER"), anySet())).thenReturn(tokens());
        when(mapper.toMeDtoOut(any(User.class), anySet())).thenReturn(MeDtoOut.builder().build());

        LoginDtoOut out = useCase.refresh(req);

        assertThat(out.getRefreshToken()).isEqualTo("refresh-jwt");
        verify(userTokenService).validateAndRotate("the-refresh-jwt");
    }

    @Test
    @DisplayName("refresh: token inválido/reusado -> BadCredentials, sin emitir tokens nuevos")
    void refresh_invalidToken_rejectedNoIssue() {
        RefreshTokenDtoIn req = new RefreshTokenDtoIn("stolen");
        when(userTokenService.validateAndRotate("stolen"))
                .thenThrow(new BadCredentialsException("Refresh token reuse detected"));

        assertThatThrownBy(() -> useCase.refresh(req)).isInstanceOf(BadCredentialsException.class);
        verify(userUseCase, never()).findById(any());
        verify(userTokenService, never()).issue(any(), any(), any(), anySet());
    }

    /* ============ logout ============ */

    @Test
    @DisplayName("logout: revoca todos los tokens del sujeto")
    void logout_revokesAll() {
        Authentication auth = new UsernamePasswordAuthenticationToken("sub-123", null, List.of());

        useCase.logout(auth);

        verify(userTokenService).revokeAll("sub-123");
    }

    @Test
    @DisplayName("logout: Authentication nulo -> no-op (no revoca)")
    void logout_nullAuth_noOp() {
        useCase.logout(null);
        verify(userTokenService, never()).revokeAll(any());
    }

    /* ============ changePassword: verifica la password antigua ============ */

    @Test
    @DisplayName("changePassword: cambia la contraseña tras verificar la actual")
    void changePassword_verifiesCurrentThenChanges() {
        UUID id = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(id.toString(), null, List.of());
        User user = user(id, "user@example.com");
        ChangePasswordDtoIn req = ChangePasswordDtoIn.builder().currentPassword("oldPw")
                .newPassword("Str0ngN3wP@ssw0rd!").build();
        when(userUseCase.findById(id)).thenReturn(user);
        when(passwordEncoder.matches("oldPw", "$2a$hash")).thenReturn(true);

        useCase.changePassword(auth, req);

        verify(passwordEncoder).matches("oldPw", "$2a$hash");
        verify(userUseCase).changePassword(user, "Str0ngN3wP@ssw0rd!");
    }

    @Test
    @DisplayName("changePassword: password actual incorrecta -> BusinessException, sin cambiar")
    void changePassword_wrongCurrent_rejected() {
        UUID id = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(id.toString(), null, List.of());
        User user = user(id, "user@example.com");
        ChangePasswordDtoIn req = ChangePasswordDtoIn.builder().currentPassword("wrong")
                .newPassword("Str0ngN3wP@ssw0rd!").build();
        when(userUseCase.findById(id)).thenReturn(user);
        when(passwordEncoder.matches("wrong", "$2a$hash")).thenReturn(false);

        assertThatThrownBy(() -> useCase.changePassword(auth, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Current password");
        verify(userUseCase, never()).changePassword(any(), any());
    }

    @Test
    @DisplayName("changePassword: sin autenticación -> BusinessException antes de tocar el repositorio")
    void changePassword_notAuthenticated_rejected() {
        ChangePasswordDtoIn req = ChangePasswordDtoIn.builder().currentPassword("x").newPassword("y").build();

        assertThatThrownBy(() -> useCase.changePassword(null, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Not authenticated");
        verify(userUseCase, never()).findById(any());
    }
}
