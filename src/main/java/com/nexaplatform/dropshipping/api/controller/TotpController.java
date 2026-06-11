package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.TotpApi;
import com.nexaplatform.dropshipping.api.dto.in.TotpDisableDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.TotpVerifyDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.TotpBackupCodesDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.TotpEnableDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.TotpSetupDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.TotpStatusDtoOut;
import com.nexaplatform.dropshipping.api.mapper.TotpDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.TotpUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * TOTP two-factor authentication controller. Pure implementation of
 * {@link TotpApi}: injects the {@link TotpDtoMapper} + {@link TotpUseCase};
 * delegates each operation and maps the result to the DtoOut. No business logic,
 * no manual mapping.
 */
@RestController
@RequestMapping("/api/me/2fa")
@RequiredArgsConstructor
public class TotpController implements TotpApi {

    private final TotpDtoMapper mapper;
    private final TotpUseCase useCase;

    @Override
    public ResponseEntity<TotpSetupDtoOut> setup(Authentication auth) {
        return new ResponseEntity<>(mapper.toSetupDtoOut(useCase.setup(UUID.fromString(auth.getName()))),
                HttpStatus.OK);
    }

    @Override
    public ResponseEntity<TotpEnableDtoOut> verifyAndEnable(Authentication auth, TotpVerifyDtoIn req) {
        return new ResponseEntity<>(
                mapper.toEnableDtoOut(useCase.verifyAndEnable(UUID.fromString(auth.getName()), req.getOtp())),
                HttpStatus.OK);
    }

    @Override
    public ResponseEntity<Void> disable(Authentication auth, TotpDisableDtoIn req) {
        useCase.disableWithPassword(UUID.fromString(auth.getName()), req.getPassword());
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @Override
    public ResponseEntity<TotpBackupCodesDtoOut> regenerate(Authentication auth) {
        return new ResponseEntity<>(
                mapper.toBackupCodesDtoOut(useCase.regenerateBackupCodes(UUID.fromString(auth.getName()))),
                HttpStatus.OK);
    }

    @Override
    public ResponseEntity<TotpStatusDtoOut> status(Authentication auth) {
        return new ResponseEntity<>(mapper.toStatusDtoOut(useCase.isEnabled(UUID.fromString(auth.getName()))),
                HttpStatus.OK);
    }
}
