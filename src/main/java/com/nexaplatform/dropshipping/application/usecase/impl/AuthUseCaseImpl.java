package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.dto.in.ActivateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.ChangePasswordDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.LoginDtoIn;
import com.nexaplatform.dropshipping.application.service.DeviceSessionService;
import com.nexaplatform.dropshipping.api.dto.in.PasswordResetConfirmDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.PasswordResetRequestDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.RefreshTokenDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.RegisterDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.UpdateProfileDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.LoginDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.RegisterDtoOut;
import com.nexaplatform.dropshipping.infrastructure.security.jwt.UserTokenService;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.mapper.UserDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.AuthUseCase;
import com.nexaplatform.dropshipping.application.usecase.UserUseCase;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.infrastructure.security.oauth.GoogleOAuth2SuccessHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link AuthUseCase}: authentication, session/
 * SecurityContext handling and authenticated-user profile management. Operates
 * on the {@link User} domain model and delegates all persistence/auth logic to
 * the {@link UserUseCase} (which replaced the former {@code AuthService}). The
 * {@link UserDtoMapper} translates the model into the transport DtoOuts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthUseCaseImpl implements AuthUseCase {

    private final UserUseCase userUseCase;
    private final AuthenticationManager authenticationManager;
    private final PasswordEncoder passwordEncoder;
    private final UserDtoMapper mapper;
    private final DeviceSessionService deviceSessionService;
    private final UserTokenService userTokenService;

    @Override
    public RegisterDtoOut register(RegisterDtoIn req) {
        User user = userUseCase.register(mapper.toDomain(req), req.getPassword());
        return RegisterDtoOut.builder().userId(user.getId()).message("Account created. Check your email to activate.")
                .build();
    }

    @Override
    public LoginDtoOut login(LoginDtoIn req, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        // Toda excepción de autenticación (credenciales inválidas, usuario inexistente, cuenta
        // no activada o bloqueada) se deja propagar como AuthenticationException → 401 genérico
        // idéntico. Así NO se puede enumerar qué emails existen ni su estado de cuenta.
        // (DisabledException/LockedException extienden AuthenticationException.)
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.getEmail().toLowerCase().trim(), req.getPassword()));

        UUID id = UUID.fromString(auth.getName());
        User user = userUseCase.findById(id);
        completePendingGoogleLink(httpRequest, user);
        // Vínculo social por TOKEN (cross-origin): la sesión PENDING_* no viaja, así que si el usuario
        // llegó desde el flujo OAuth (?link=required) y ahora prueba su contraseña, vinculamos aquí. El
        // control se mantiene: solo se vincula tras autenticar con éxito la cuenta local.
        if (req.isLinkSocial()) {
            userUseCase.linkGoogleAccount(id);
        }
        // Registro de dispositivo: auditoría best-effort (la cookie nx_device no viaja
        // cross-site, pero la fila sirve para histórico de IP/agente).
        deviceSessionService.recordLogin(id, httpRequest, httpResponse);
        return buildLogin(user, authorities(auth));
    }

    @Override
    public LoginDtoOut refresh(RefreshTokenDtoIn req) {
        UUID id = userTokenService.validateAndRotate(req.getRefreshToken());
        User user = userUseCase.findById(id);
        return buildLogin(user, Set.of(user.getRole().authority()));
    }

    @Override
    public void logout(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return;
        }
        userTokenService.revokeAll(authentication.getName());
    }

    /** Emite el par de tokens para {@code user} y arma la respuesta de login. */
    private LoginDtoOut buildLogin(User user, Set<String> authorities) {
        UserTokenService.Tokens tokens = userTokenService.issue(user.getId(), user.getEmail(),
                user.getRole().name(), authorities);
        return LoginDtoOut.builder().token(tokens.accessToken()).refreshToken(tokens.refreshToken())
                .tokenType("Bearer").expiresIn(tokens.expiresInSeconds()).user(mapper.toMeDtoOut(user, authorities))
                .build();
    }

    /**
     * If a Google login for this account was parked awaiting password confirmation
     * (see {@link GoogleOAuth2SuccessHandler}), the just-completed password login is
     * that proof of ownership: confirm the link and clear the pending marker. The
     * email match guards against linking the wrong account if the session was reused.
     */
    private void completePendingGoogleLink(HttpServletRequest httpRequest, User user) {
        HttpSession session = httpRequest.getSession(false);
        if (session == null) {
            return;
        }
        Object pendingEmail = session.getAttribute(GoogleOAuth2SuccessHandler.PENDING_GOOGLE_LINK_EMAIL);
        if (pendingEmail != null && pendingEmail.toString().equalsIgnoreCase(user.getEmail())) {
            userUseCase.linkGoogleAccount(user.getId());
            session.removeAttribute(GoogleOAuth2SuccessHandler.PENDING_GOOGLE_LINK_EMAIL);
        }
    }

    @Override
    public void activate(ActivateDtoIn req) {
        userUseCase.activate(req.getCode());
    }

    @Override
    public void requestReset(PasswordResetRequestDtoIn req) {
        userUseCase.requestPasswordReset(req.getEmail());
    }

    @Override
    public void confirmReset(PasswordResetConfirmDtoIn req) {
        userUseCase.confirmPasswordReset(req.getToken(), req.getNewPassword());
    }

    @Override
    public MeDtoOut me(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return null;
        }
        UUID id;
        try {
            id = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            id = userUseCase.findByEmail(authentication.getName()).getId();
        }
        User user = userUseCase.findById(id);
        return mapper.toMeDtoOut(user, authorities(authentication));
    }

    @Override
    @Transactional
    public void changePassword(Authentication authentication, ChangePasswordDtoIn req) {
        if (authentication == null) {
            throw new BusinessException("Not authenticated");
        }
        UUID id = UUID.fromString(authentication.getName());
        User user = userUseCase.findById(id);
        if (!passwordEncoder.matches(req.getCurrentPassword(), user.getPasswordHash())) {
            throw new BusinessException("Current password does not match");
        }
        userUseCase.changePassword(user, req.getNewPassword());
    }

    @Override
    @Transactional
    public MeDtoOut updateProfile(Authentication authentication, UpdateProfileDtoIn req) {
        UUID id = UUID.fromString(authentication.getName());
        User user = userUseCase.findById(id);
        if (req.getFirstName() != null)
            user.setFirstName(req.getFirstName().trim());
        if (req.getLastName1() != null)
            user.setLastName1(req.getLastName1().trim());
        if (req.getLastName2() != null)
            user.setLastName2(req.getLastName2().trim());
        boolean nameParts = req.getFirstName() != null || req.getLastName1() != null || req.getLastName2() != null;
        if (nameParts)
            // El displayName (nombre completo) se recompone a partir de las partes actualizadas.
            user.setDisplayName(mapper.fullName(user));
        else if (req.getDisplayName() != null)
            user.setDisplayName(req.getDisplayName().trim());
        if (req.getCompanyName() != null)
            user.setCompanyName(req.getCompanyName().trim());
        if (req.getCountry() != null)
            user.setCountry(req.getCountry().trim().toUpperCase());
        if (req.getLanguage() != null)
            user.setLanguage(req.getLanguage());
        User saved = userUseCase.updateUser(user);
        return mapper.toMeDtoOut(saved, authorities(authentication));
    }

    private static Set<String> authorities(Authentication authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }
}
