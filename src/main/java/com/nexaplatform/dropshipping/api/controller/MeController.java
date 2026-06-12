package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.MeApi;
import com.nexaplatform.dropshipping.api.dto.in.ChangePasswordDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.UpdateProfileDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.MeDtoOut;
import com.nexaplatform.dropshipping.application.usecase.AuthUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Authenticated user's profile controller. Pure implementation of {@link MeApi}:
 * no business logic and no manual mapping — delegates to {@link AuthUseCase}.
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController implements MeApi {

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
