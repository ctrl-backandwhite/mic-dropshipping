package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.MeApi;
import com.nexaplatform.dropshipping.api.dto.in.ChangePasswordDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.DeleteAccountConfirmDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.UpdateProfileDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.MeDtoOut;
import com.nexaplatform.dropshipping.application.service.DeviceSessionService;
import com.nexaplatform.dropshipping.application.usecase.AuthUseCase;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Authenticated user's profile controller. Pure implementation of {@link MeApi}:
 * no business logic and no manual mapping — delegates to {@link AuthUseCase}.
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController implements MeApi {

    private final AuthUseCase authUseCase;
    private final DeviceSessionService deviceSessionService;

    @Override
    public ResponseEntity<MeDtoOut> me(Authentication authentication) {
        return new ResponseEntity<>(authUseCase.me(authentication), HttpStatus.OK);
    }

    /** Dispositivos/sesiones conectados del usuario autenticado. */
    @GetMapping("/sessions")
    public ResponseEntity<List<DeviceSessionService.SessionView>> sessions(Authentication authentication,
            HttpServletRequest request) {
        return ResponseEntity.ok(deviceSessionService.list(UUID.fromString(authentication.getName()), request));
    }

    /** Revoca (cierra) una sesión/dispositivo del usuario. */
    @PostMapping("/sessions/{id}/revoke")
    public ResponseEntity<Void> revokeSession(Authentication authentication, @PathVariable UUID id) {
        deviceSessionService.revoke(UUID.fromString(authentication.getName()), id);
        return ResponseEntity.noContent().build();
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
    public ResponseEntity<Void> requestAccountDeletion(Authentication authentication) {
        authUseCase.requestAccountDeletion(authentication);
        return new ResponseEntity<>(HttpStatus.ACCEPTED);
    }

    @Override
    public ResponseEntity<Void> confirmAccountDeletion(Authentication authentication, DeleteAccountConfirmDtoIn req) {
        authUseCase.confirmAccountDeletion(authentication, req);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }
}
