package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AuthApi;
import com.nexaplatform.dropshipping.api.MeApi;
import com.nexaplatform.dropshipping.api.dto.in.ActivateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.ChangePasswordDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.LoginDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.PasswordResetConfirmDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.PasswordResetRequestDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.RegisterDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.UpdateProfileDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.MeDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.RegisterDtoOut;
import com.nexaplatform.dropshipping.application.usecase.AuthUseCase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Authentication controller. Pure implementation of {@link AuthApi}: no business
 * logic and no manual mapping — delegates to {@link AuthUseCase} and wraps the
 * result in a {@link ResponseEntity}.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController implements AuthApi {

    private final AuthUseCase authUseCase;

    @Override
    public ResponseEntity<RegisterDtoOut> register(RegisterDtoIn req) {
        return new ResponseEntity<>(authUseCase.register(req), HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<MeDtoOut> login(LoginDtoIn req,
                                          HttpServletRequest httpRequest,
                                          HttpServletResponse httpResponse) {
        return new ResponseEntity<>(authUseCase.login(req, httpRequest, httpResponse), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<Void> activate(ActivateDtoIn req) {
        authUseCase.activate(req);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @Override
    public ResponseEntity<Void> requestReset(PasswordResetRequestDtoIn req) {
        authUseCase.requestReset(req);
        return new ResponseEntity<>(HttpStatus.ACCEPTED);
    }

    @Override
    public ResponseEntity<Void> confirmReset(PasswordResetConfirmDtoIn req) {
        authUseCase.confirmReset(req);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }
}

/**
 * Authenticated user's profile controller. Pure implementation of {@link MeApi}:
 * no business logic and no manual mapping — delegates to {@link AuthUseCase}.
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
class MeController implements MeApi {

    private final AuthUseCase authUseCase;

    @Override
    public ResponseEntity<MeDtoOut> me(Authentication authentication) {
        return new ResponseEntity<>(authUseCase.me(authentication), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<Void> changePassword(Authentication authentication, ChangePasswordDtoIn req) {
        authUseCase.changePassword(authentication, req);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @Override
    public ResponseEntity<MeDtoOut> updateProfile(Authentication authentication, UpdateProfileDtoIn req) {
        return new ResponseEntity<>(authUseCase.updateProfile(authentication, req), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<MeDtoOut> uploadAvatar(Authentication authentication, MultipartFile file) {
        return new ResponseEntity<>(authUseCase.uploadAvatar(authentication, file), HttpStatus.OK);
    }
}
