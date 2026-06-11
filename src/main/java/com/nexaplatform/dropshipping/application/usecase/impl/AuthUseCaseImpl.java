package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.dto.in.ActivateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.ChangePasswordDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.LoginDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.PasswordResetConfirmDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.PasswordResetRequestDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.RegisterDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.UpdateProfileDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.MeDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.RegisterDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.mapper.UserDtoMapper;
import com.nexaplatform.dropshipping.application.service.AuthService;
import com.nexaplatform.dropshipping.application.usecase.AuthUseCase;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.StorageService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link AuthUseCase}: authentication, session/
 * SecurityContext handling and authenticated-user profile management. All logic
 * previously inlined in {@code AuthController} / {@code MeController} lives here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthUseCaseImpl implements AuthUseCase {

    private final AuthService authService;
    private final AuthenticationManager authenticationManager;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final StorageService storageService;
    private final UserDtoMapper mapper;

    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    @Override
    public RegisterDtoOut register(RegisterDtoIn req) {
        UserEntity user = authService.register(mapper.toRegisterRequest(req));
        return RegisterDtoOut.builder()
                .userId(user.getId())
                .message("Account created. Check your email to activate.")
                .build();
    }

    @Override
    public MeDtoOut login(LoginDtoIn req, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        // BadCredentialsException propagates to the global handler -> 401.
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.getEmail().toLowerCase().trim(), req.getPassword()));

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, httpRequest, httpResponse);

            UUID id = UUID.fromString(auth.getName());
            UserEntity user = authService.findById(id);
            return mapper.toMeDtoOut(user, authorities(auth));
        } catch (DisabledException e) {
            throw new BusinessException("Account not yet activated. Check your email.");
        } catch (LockedException e) {
            throw new BusinessException(
                    "Account temporarily locked due to repeated failed attempts. Try again later.");
        }
    }

    @Override
    public void activate(ActivateDtoIn req) {
        authService.activate(req.getCode());
    }

    @Override
    public void requestReset(PasswordResetRequestDtoIn req) {
        authService.requestPasswordReset(req.getEmail());
    }

    @Override
    public void confirmReset(PasswordResetConfirmDtoIn req) {
        authService.confirmPasswordReset(req.getToken(), req.getNewPassword());
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
            id = authService.findByEmail(authentication.getName()).getId();
        }
        UserEntity user = authService.findById(id);
        return mapper.toMeDtoOut(user, authorities(authentication));
    }

    @Override
    @Transactional
    public void changePassword(Authentication authentication, ChangePasswordDtoIn req) {
        if (authentication == null) {
            throw new BusinessException("Not authenticated");
        }
        UUID id = UUID.fromString(authentication.getName());
        UserEntity user = authService.findById(id);
        if (!passwordEncoder.matches(req.getCurrentPassword(), user.getPasswordHash())) {
            throw new BusinessException("Current password does not match");
        }
        authService.changePassword(user, req.getNewPassword());
    }

    @Override
    @Transactional
    public MeDtoOut updateProfile(Authentication authentication, UpdateProfileDtoIn req) {
        UUID id = UUID.fromString(authentication.getName());
        UserEntity user = authService.findById(id);
        if (req.getDisplayName() != null) user.setDisplayName(req.getDisplayName().trim());
        if (req.getCompanyName() != null) user.setCompanyName(req.getCompanyName().trim());
        if (req.getCountry() != null) user.setCountry(req.getCountry().trim().toUpperCase());
        if (req.getLanguage() != null) user.setLanguage(req.getLanguage());
        userRepository.save(user);
        return mapper.toMeDtoOut(user, authorities(authentication));
    }

    @Override
    @Transactional
    public MeDtoOut uploadAvatar(Authentication authentication, MultipartFile file) {
        if (authentication == null) throw new BusinessException("Not authenticated");
        if (file == null || file.isEmpty()) throw new BusinessException("Empty file");
        if (file.getSize() > 2L * 1024 * 1024) throw new BusinessException("Avatar exceeds 2 MB");
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String ext;
        switch (contentType) {
            case "image/jpeg":
            case "image/jpg":  ext = "jpg";  break;
            case "image/png":  ext = "png";  break;
            case "image/webp": ext = "webp"; break;
            default: throw new BusinessException("Only JPG/PNG/WEBP are allowed");
        }

        UUID id = UUID.fromString(authentication.getName());
        UserEntity user = authService.findById(id);

        try {
            // Cache-buster with epoch so the browser refreshes the image on change.
            String key = "avatars/" + user.getId() + "-" + Instant.now().getEpochSecond() + "." + ext;
            String url = storageService.putBytes(key, file.getBytes(), contentType);
            user.setAvatarUrl(url);
            userRepository.save(user);
        } catch (IOException e) {
            throw new BusinessException("Could not read uploaded file: " + e.getMessage());
        }

        return mapper.toMeDtoOut(user, authorities(authentication));
    }

    private static Set<String> authorities(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }
}
