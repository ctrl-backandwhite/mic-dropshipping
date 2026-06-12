package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AuthApi;
import com.nexaplatform.dropshipping.api.dto.in.ActivateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.LoginDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.PasswordResetConfirmDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.PasswordResetRequestDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.RegisterDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.MeDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.RegisterDtoOut;
import com.nexaplatform.dropshipping.application.usecase.AuthUseCase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public ResponseEntity<MeDtoOut> login(LoginDtoIn req, HttpServletRequest httpRequest,
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
